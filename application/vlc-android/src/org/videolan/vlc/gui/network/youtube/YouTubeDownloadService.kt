/*****************************************************************************
 * YouTubeDownloadService.kt
 *****************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/
package org.videolan.vlc.gui.network.youtube

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.videolan.libvlc.util.AndroidUtil
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.videolan.resources.util.parcelable
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.NotificationHelper
import org.videolan.vlc.reloadLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

private const val TAG = "VLC/YouTubeDownload"
private const val PROGRESS_NOTIFICATION_ID = 0x7954
private const val EXTRA_TITLE = "yt_title"
private const val EXTRA_OPTION = "yt_option"

/** googlevideo.com throttles or rejects large un-ranged requests, so fetch in chunks. */
private const val CHUNK_SIZE = 10L * 1024 * 1024

class YouTubeDownloadService : LifecycleService() {

    companion object {
        fun start(context: Context, title: String, option: YouTubeDownloadOption) {
            val intent = Intent(context, YouTubeDownloadService::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_OPTION, option)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    private val queueLock = Mutex()
    private val pending = AtomicInteger(0)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var resultNotificationId = PROGRESS_NOTIFICATION_ID + 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (AndroidUtil.isOOrLater) NotificationHelper.createNotificationChannels(applicationContext)
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val option = intent?.parcelable<YouTubeDownloadOption>(EXTRA_OPTION)
        goForeground(progressNotification(getString(R.string.yt_download_started, title ?: ""), -1, option?.isAudioOnly == true))
        if (title == null || option == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        pending.incrementAndGet()
        lifecycleScope.launch {
            // One download at a time keeps bandwidth and the notification simple
            queueLock.withLock { runDownload(title, option) }
            pending.decrementAndGet()
            stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(title: String, option: YouTubeDownloadOption) {
        val workDir = File(cacheDir, "yt_download").apply { mkdirs() }
        val main = File(workDir, "main.tmp")
        val audio = File(workDir, "audio.tmp")
        val muxed = File(workDir, "muxed.mp4")
        try {
            withContext(Dispatchers.IO) {
                // Muxed options are only offered on API 18+ (see YouTubeExtractor)
                val hasAudioTrack = option.audioUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                // Weight progress: video track is most of the bytes when muxing
                download(option.url, main) { p -> updateProgress(title, if (hasAudioTrack) p * 85 / 100 else p, option.isAudioOnly) }
                val finalFile = if (hasAudioTrack) {
                    download(option.audioUrl!!, audio) { p -> updateProgress(title, 85 + p * 10 / 100, false) }
                    notificationManager.notify(PROGRESS_NOTIFICATION_ID,
                            progressNotification(getString(R.string.yt_download_merging), -1, false))
                    mux(main, audio, muxed)
                    muxed
                } else main
                publish(finalFile, title, option)
            }
            reloadLibrary()
            notifyResult(getString(R.string.yt_download_done, title), option.isAudioOnly)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            notifyResult(getString(R.string.yt_download_failed, e.message ?: e.javaClass.simpleName), option.isAudioOnly)
        } finally {
            main.delete(); audio.delete(); muxed.delete()
        }
    }

    // ---- Networking ---------------------------------------------------------------------------

    private suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        var position = 0L
        var total = -1L
        var lastPercent = -1
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (total < 0 || position < total) {
                coroutineContext.ensureActive()
                val request = Request.Builder().url(url)
                        .header("User-Agent", YT_USER_AGENT)
                        .header("Range", "bytes=$position-${position + CHUNK_SIZE - 1}")
                        .build()
                val received = client.newCall(request).execute().use { response ->
                    if (response.code == 416) return@use 0L // asked past the end: we're done
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    if (response.code == 206) {
                        response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { total = it }
                    } else {
                        // Server ignored the range and is sending the whole file
                        if (position > 0) throw IOException("Server does not support resuming")
                        total = body.contentLength()
                    }
                    var read = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = ((position + read) * 100 / total).toInt()
                                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                            }
                        }
                    }
                    if (response.code == 200 && total < 0) total = position + read
                    read
                }
                if (received <= 0L) break
                position += received
            }
        }
        if (position == 0L) throw IOException("Nothing was downloaded")
    }

    // ---- Muxing -------------------------------------------------------------------------------

    /** Combine an H.264 video-only MP4 and an AAC M4A into one MP4, without re-encoding. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private fun mux(videoFile: File, audioFile: File, output: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.path) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.path) }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoIndex = findTrack(videoExtractor, "video/")
            val audioIndex = findTrack(audioExtractor, "audio/")
            videoExtractor.selectTrack(videoIndex)
            audioExtractor.selectTrack(audioIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioIndex)
            val muxVideo = muxer.addTrack(videoFormat)
            val muxAudio = muxer.addTrack(audioFormat)
            muxer.start()
            copySamples(videoExtractor, muxer, muxVideo, maxInputSize(videoFormat))
            copySamples(audioExtractor, muxer, muxAudio, maxInputSize(audioFormat))
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Exception) {}
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) return i
        }
        throw IOException("No $mimePrefix track found")
    }

    private fun maxInputSize(format: MediaFormat) =
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
            else 4 * 1024 * 1024

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, track: Int, bufferSize: Int) {
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, extractor.sampleTime, flags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    // ---- Saving to shared storage -------------------------------------------------------------

    private fun publish(file: File, title: String, option: YouTubeDownloadOption) {
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "YouTube video" }
        val displayName = "$safeName.${option.extension}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (option.isAudioOnly) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                             else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, option.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (option.isAudioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/VLC")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Couldn't write the file")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                    if (option.isAudioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "VLC")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create ${dir.path}")
            var target = File(dir, displayName)
            var i = 1
            while (target.exists()) target = File(dir, "$safeName (${i++}).${option.extension}")
            file.copyTo(target)
            MediaScannerConnection.scanFile(this, arrayOf(target.path), arrayOf(option.mimeType), null)
        }
    }

    // ---- Notifications ------------------------------------------------------------------------

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, notification, type)
    }

    private fun progressNotification(text: String, percent: Int, audio: Boolean) =
            NotificationHelper.createYouTubeDownloadNotification(this, text, percent, ongoing = true, audio = audio)

    private fun updateProgress(title: String, percent: Int, audio: Boolean) {
        notificationManager.notify(PROGRESS_NOTIFICATION_ID,
                progressNotification(getString(R.string.yt_download_progress, title, percent), percent, audio))
    }

    private fun notifyResult(text: String, audio: Boolean) {
        notificationManager.notify(resultNotificationId++,
                NotificationHelper.createYouTubeDownloadNotification(this, text, 0, ongoing = false, audio = audio))
    }

    private fun stopIfIdle() {
        if (pending.get() > 0) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
