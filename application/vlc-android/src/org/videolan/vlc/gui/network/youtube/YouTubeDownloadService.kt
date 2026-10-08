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
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.resources.util.parcelable
import org.videolan.tools.KEY_SUBTITLE_PREFERRED_LANGUAGE
import org.videolan.tools.KEY_YOUTUBE_DOWNLOAD_QUALITY
import org.videolan.tools.KEY_YOUTUBE_DOWNLOAD_SUBTITLES
import org.videolan.tools.Settings
import org.videolan.tools.getLocaleLanguages
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.NotificationHelper
import org.videolan.vlc.reloadLibrary
import org.videolan.vlc.repository.SlaveRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

private const val TAG = "VLC/YouTubeDownload"
private const val PROGRESS_NOTIFICATION_ID = 0x7954
private const val ACTION_CANCEL = "org.videolan.vlc.youtube.CANCEL"
private const val EXTRA_TITLE = "yt_title"
private const val EXTRA_OPTION = "yt_option"
private const val EXTRA_SUBTITLE = "yt_subtitle"
private const val EXTRA_PLAYLIST_URLS = "yt_playlist_urls"
private const val EXTRA_QUALITY = "yt_quality"
private const val EXTRA_WITH_SUBTITLES = "yt_with_subtitles"

/** googlevideo.com throttles or rejects large un-ranged requests, so fetch in chunks. */
private const val CHUNK_SIZE = 10L * 1024 * 1024
/** Retries per chunk when the connection drops; the download resumes from the last byte written */
private const val MAX_RETRIES = 5
/** Pause between playlist entries, to stay clear of YouTube's rate limiting */
private const val PLAYLIST_DELAY_MS = 1500L

/** Quality remembered in settings, see [YouTubeQuality] */
fun Context.youTubeQualityPreference(): String =
        Settings.getInstance(this).getString(KEY_YOUTUBE_DOWNLOAD_QUALITY, YouTubeQuality.ASK) ?: YouTubeQuality.ASK

fun Context.youTubeSubtitlesEnabled(): Boolean = Settings.getInstance(this).getBoolean(KEY_YOUTUBE_DOWNLOAD_SUBTITLES, true)

/** VLC's preferred subtitle language first, then the device languages */
fun Context.youTubeSubtitleLanguages(): List<String> =
        (listOf(Settings.getInstance(this).getString(KEY_SUBTITLE_PREFERRED_LANGUAGE, "").orEmpty()) + getLocaleLanguages())
                .filter { it.isNotBlank() }.distinct()

/** A non-retriable HTTP error, e.g. 403 when the stream URL has expired */
private class HttpStatusException(val code: Int) : IOException("HTTP $code")

class YouTubeDownloadService : LifecycleService() {

    companion object {
        fun start(context: Context, title: String, option: YouTubeDownloadOption, subtitle: YouTubeSubtitle?) {
            val intent = Intent(context, YouTubeDownloadService::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_OPTION, option)
                    .putExtra(EXTRA_SUBTITLE, subtitle)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Each video is resolved right before its download, as YouTube stream URLs expire after a few hours */
        fun startPlaylist(context: Context, title: String, videoUrls: List<String>, quality: String, withSubtitles: Boolean) {
            val intent = Intent(context, YouTubeDownloadService::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putStringArrayListExtra(EXTRA_PLAYLIST_URLS, ArrayList(videoUrls))
                    .putExtra(EXTRA_QUALITY, quality)
                    .putExtra(EXTRA_WITH_SUBTITLES, withSubtitles)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    private val queueLock = Mutex()
    private val pending = AtomicInteger(0)
    @Volatile private var currentJob: Job? = null
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var resultNotificationId = PROGRESS_NOTIFICATION_ID + 1
    private val cancelIntent by lazy {
        PendingIntent.getService(this, 0, Intent(this, YouTubeDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (AndroidUtil.isOOrLater) NotificationHelper.createNotificationChannels(applicationContext)
        if (intent?.action == ACTION_CANCEL) {
            // Stops the running download (or the rest of a playlist); queued ones still run
            currentJob?.cancel()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val option = intent?.parcelable<YouTubeDownloadOption>(EXTRA_OPTION)
        val playlist = intent?.getStringArrayListExtra(EXTRA_PLAYLIST_URLS)
        goForeground(progressNotification(getString(R.string.yt_download_started, title ?: ""), -1, option?.isAudioOnly == true))
        if (title == null || (option == null && playlist.isNullOrEmpty())) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val subtitle = intent.parcelable<YouTubeSubtitle>(EXTRA_SUBTITLE)
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: YouTubeQuality.BEST
        val withSubtitles = intent.getBooleanExtra(EXTRA_WITH_SUBTITLES, false)
        pending.incrementAndGet()
        lifecycleScope.launch {
            try {
                // One download at a time keeps bandwidth and the notification simple
                queueLock.withLock {
                    currentJob = coroutineContext.job
                    try {
                        if (option != null) runSingle(title, option, subtitle)
                        else runPlaylist(title, playlist!!, quality, withSubtitles)
                    } finally {
                        currentJob = null
                    }
                }
            } finally {
                pending.decrementAndGet()
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSingle(title: String, option: YouTubeDownloadOption, subtitle: YouTubeSubtitle?) {
        try {
            downloadVideo(title, option, subtitle) { percent -> getString(R.string.yt_download_progress, title, percent) }
            reloadLibrary()
            notifyResult(getString(R.string.yt_download_done, title), option.isAudioOnly)
        } catch (e: CancellationException) {
            notifyResult(getString(R.string.yt_download_cancelled, title), option.isAudioOnly)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            notifyResult(getString(R.string.yt_download_failed, e.message ?: e.javaClass.simpleName), option.isAudioOnly)
        }
    }

    private suspend fun runPlaylist(title: String, urls: List<String>, quality: String, withSubtitles: Boolean) {
        val languages = if (withSubtitles) youTubeSubtitleLanguages() else emptyList()
        var saved = 0
        try {
            urls.forEachIndexed { index, url ->
                coroutineContext.ensureActive()
                if (index > 0) delay(PLAYLIST_DELAY_MS)
                try {
                    val video = YouTubeExtractor.resolve(url, languages)
                    val option = YouTubeExtractor.pickOption(video.options, quality) ?: return@forEachIndexed
                    val videoTitle = video.title.ifBlank { getString(R.string.yt_download) }
                    downloadVideo(videoTitle, option, if (withSubtitles) video.subtitle else null) { percent ->
                        getString(R.string.yt_download_playlist_progress, index + 1, urls.size, videoTitle, percent)
                    }
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A private or removed video shouldn't stop the rest of the playlist
                    Log.e(TAG, "Playlist entry failed: $url", e)
                }
            }
        } catch (e: CancellationException) {
            if (saved > 0) reloadLibrary()
            notifyResult(getString(R.string.yt_download_cancelled, title), false)
            return
        }
        if (saved > 0) reloadLibrary()
        notifyResult(getString(R.string.yt_download_playlist_done, saved, urls.size), false)
    }

    /** Download, merge if needed, save to shared storage and attach the subtitle. Throws on failure or cancellation. */
    private suspend fun downloadVideo(title: String, option: YouTubeDownloadOption, subtitle: YouTubeSubtitle?, progressText: (Int) -> String) {
        val workDir = File(cacheDir, "yt_download").apply { mkdirs() }
        val main = File(workDir, "main.tmp")
        val audio = File(workDir, "audio.tmp")
        val muxed = File(workDir, "muxed.mp4")
        val showProgress = { percent: Int -> updateProgress(progressText(percent), percent, option.isAudioOnly) }
        val showRetry = { attempt: Int ->
            notificationManager.notify(PROGRESS_NOTIFICATION_ID,
                    progressNotification(getString(R.string.yt_download_retrying, attempt, MAX_RETRIES), -1, option.isAudioOnly))
        }
        try {
            withContext(Dispatchers.IO) {
                // Muxed options are only offered on API 18+ (see YouTubeExtractor)
                val hasAudioTrack = option.audioUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                // Weight progress: video track is most of the bytes when muxing
                download(option.url, main, { p -> showProgress(if (hasAudioTrack) p * 85 / 100 else p) }, showRetry)
                val finalFile = if (hasAudioTrack) {
                    download(option.audioUrl!!, audio, { p -> showProgress(85 + p * 10 / 100) }, showRetry)
                    coroutineContext.ensureActive()
                    notificationManager.notify(PROGRESS_NOTIFICATION_ID,
                            progressNotification(getString(R.string.yt_download_merging), -1, false))
                    mux(main, audio, muxed)
                    muxed
                } else main
                coroutineContext.ensureActive()
                val saved = publish(finalFile, title, option)
                if (subtitle != null && saved != null) attachSubtitle(saved, subtitle)
            }
        } finally {
            main.delete(); audio.delete(); muxed.delete()
        }
    }

    // ---- Networking ---------------------------------------------------------------------------

    /**
     * Ranged download into [dest]. Network errors are retried up to [MAX_RETRIES] times with backoff,
     * resuming from the last byte written; cancelling the coroutine aborts the HTTP call right away.
     */
    private suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit, onRetry: (Int) -> Unit) {
        var position = 0L
        var total = -1L
        var lastPercent = -1
        var attempt = 0
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (total < 0 || position < total) {
                coroutineContext.ensureActive()
                val request = Request.Builder().url(url)
                        .header("User-Agent", YT_USER_AGENT)
                        .header("Range", "bytes=$position-${position + CHUNK_SIZE - 1}")
                        .build()
                val call = client.newCall(request)
                val cancelHandle = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
                val chunkStart = position
                try {
                    val finished = call.execute().use { response ->
                        if (response.code == 416) return@use true // asked past the end: we're done
                        if (!response.isSuccessful) throw HttpStatusException(response.code)
                        val body = response.body ?: throw IOException("Empty response")
                        if (response.code == 206) {
                            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { total = it }
                        } else {
                            // Server ignored the range and sends the whole file: start over from byte 0
                            if (position > 0) { out.channel.truncate(0); out.channel.position(0); position = 0 }
                            total = body.contentLength()
                        }
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                position += n
                                if (total > 0) {
                                    val percent = (position * 100 / total).toInt()
                                    if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                                }
                            }
                        }
                        if (response.code == 200 && total < 0) total = position
                        position == chunkStart // empty chunk: nothing left to read
                    }
                    attempt = 0
                    if (finished) break
                } catch (e: IOException) {
                    coroutineContext.ensureActive() // a cancelled call surfaces as IOException
                    val retriable = e !is HttpStatusException || e.code == 429 || e.code >= 500
                    if (!retriable || ++attempt > MAX_RETRIES) throw e
                    Log.w(TAG, "Chunk failed at $position, retry $attempt/$MAX_RETRIES", e)
                    onRetry(attempt)
                    delay(2000L shl (attempt - 1)) // 2s, 4s, 8s, 16s, 32s
                } finally {
                    cancelHandle.dispose()
                }
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

    /** Characters that file systems reject, plus ones the media library escapes differently than [Uri.fromFile] */
    private fun safeFileName(title: String) =
            title.replace(Regex("[\\\\/:*?\"<>|#%&+,;=@$\\[\\]\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "YouTube video" }

    /** Save [file] to Movies/VLC or Music/VLC, and return its path when it can be known */
    private fun publish(file: File, title: String, option: YouTubeDownloadOption): File? {
        val safeName = safeFileName(title)
        val displayName = "$safeName.${option.extension}"
        val folder = if (option.isAudioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (option.isAudioOnly) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                             else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, option.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/VLC")
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
            // MediaStore may have renamed the file to avoid a clash, so ask it for the real path
            @Suppress("DEPRECATION")
            return contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { File(it) } else null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), "VLC")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create ${dir.path}")
            var target = File(dir, displayName)
            var i = 1
            while (target.exists()) target = File(dir, "$safeName (${i++}).${option.extension}")
            file.copyTo(target)
            MediaScannerConnection.scanFile(this, arrayOf(target.path), arrayOf(option.mimeType), null)
            return target
        }
    }

    /**
     * Same approach as VLC's subtitle downloader: keep the file in the app's subtitles folder
     * and register it as a subtitle slave of the media, so the player loads it automatically.
     * A subtitle failure never fails the video download.
     */
    private suspend fun attachSubtitle(video: File, subtitle: YouTubeSubtitle) {
        try {
            val dir = File(getExternalFilesDir(null), "subtitles").apply { mkdirs() }
            val subFile = File(dir, "${video.nameWithoutExtension}.${subtitle.extension}")
            val request = Request.Builder().url(subtitle.url).header("User-Agent", YT_USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                val body = response.body ?: throw IOException("Empty response")
                subFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            }
            SlaveRepository.getInstance(applicationContext)
                    .saveSlave(Uri.fromFile(video).toString(), IMedia.Slave.Type.Subtitle, 2, Uri.fromFile(subFile).toString())
                    .join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Subtitle download failed", e)
        }
    }

    // ---- Notifications ------------------------------------------------------------------------

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, notification, type)
    }

    private fun progressNotification(text: String, percent: Int, audio: Boolean) =
            NotificationHelper.createYouTubeDownloadNotification(this, text, percent, ongoing = true, audio = audio, cancelIntent = cancelIntent)

    private fun updateProgress(text: String, percent: Int, audio: Boolean) {
        notificationManager.notify(PROGRESS_NOTIFICATION_ID, progressNotification(text, percent, audio))
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
