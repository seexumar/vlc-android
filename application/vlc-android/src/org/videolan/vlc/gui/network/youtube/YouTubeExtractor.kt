/*****************************************************************************
 * YouTubeExtractor.kt
 *
 * Resolves a YouTube link into downloadable stream options using
 * NewPipeExtractor.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *****************************************************************************/
package org.videolan.vlc.gui.network.youtube

import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/** Browser-like UA used both for extraction and for fetching the media itself. */
const val YT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

/** One entry of the quality picker. */
@Parcelize
data class YouTubeDownloadOption(
        val label: String,
        /** Progressive stream (audio+video) or audio-only stream, or the video track when [audioUrl] is set. */
        val url: String,
        /** When set, [url] is a video-only MP4 track that must be muxed with this M4A audio track. */
        val audioUrl: String? = null,
        val extension: String,
        val mimeType: String,
        val isAudioOnly: Boolean = false
) : Parcelable

data class YouTubeVideo(val title: String, val options: List<YouTubeDownloadOption>)

object YouTubeExtractor {

    private val linkRegex = Regex(
            "^(https?://)?((www|m|music)\\.)?(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/.+",
            RegexOption.IGNORE_CASE)

    @Volatile private var initialized = false

    fun isYouTubeLink(text: String?): Boolean = !text.isNullOrBlank() && linkRegex.matches(text.trim())

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader)
                initialized = true
            }
        }
    }

    /** Network + parsing; call off the main thread (it switches to IO itself). */
    suspend fun resolve(link: String): YouTubeVideo = withContext(Dispatchers.IO) {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, link.trim())
        val options = mutableListOf<YouTubeDownloadOption>()

        // Best original-language M4A track, used both for HD muxing and the audio-only option
        val audioCandidates = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .ifEmpty { info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        val bestM4a: AudioStream? = audioCandidates.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }

        // HD: H.264 video-only MP4 tracks, muxed on-device with the M4A audio (MediaMuxer only accepts AVC + AAC in MP4)
        if (bestM4a != null) {
            info.videoOnlyStreams
                    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .filter { it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc1") == true }
                    .distinctBy { it.height }
                    .sortedByDescending { it.height }
                    .forEach {
                        options.add(YouTubeDownloadOption(
                                label = "${it.resolution} MP4",
                                url = it.content,
                                audioUrl = bestM4a.content,
                                extension = "mp4",
                                mimeType = "video/mp4"))
                    }
        }

        // Progressive streams already contain audio + video (usually 360p only)
        info.videoStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.isVideoOnly }
                .forEach {
                    val ext = it.format?.suffix ?: "mp4"
                    val label = "${it.resolution} ${ext.uppercase()}"
                    if (options.none { o -> o.label == label }) options.add(YouTubeDownloadOption(
                            label = label,
                            url = it.content,
                            extension = ext,
                            mimeType = it.format?.mimeType ?: "video/mp4"))
                }

        // Audio only
        audioCandidates.maxByOrNull { if (it.format == MediaFormat.M4A) it.averageBitrate + 1 else it.averageBitrate }?.let {
            val ext = it.format?.suffix ?: "m4a"
            val kbps = if (it.averageBitrate > 0) " ${it.averageBitrate} kbps" else ""
            options.add(YouTubeDownloadOption(
                    label = "Audio only$kbps ${ext.uppercase()}",
                    url = it.content,
                    extension = ext,
                    mimeType = it.format?.mimeType ?: "audio/mp4",
                    isAudioOnly = true))
        }

        YouTubeVideo(info.name ?: "YouTube video", options)
    }

    /** NewPipeExtractor needs an HTTP client; this one is backed by the OkHttp VLC already ships. */
    private object OkHttpDownloader : Downloader() {
        val client: OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        override fun execute(request: Request): Response {
            val data = request.dataToSend()
            val body = when {
                data != null -> data.toRequestBody()
                request.httpMethod() == "POST" -> ByteArray(0).toRequestBody()
                else -> null
            }
            val builder = okhttp3.Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), body)
                    .header("User-Agent", YT_USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(response.code, response.message, response.headers.toMultimap(),
                        response.body?.string(), response.request.url.toString())
            }
        }
    }
}
