/*****************************************************************************
 * YouTubeDownloadModel.kt
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
package org.videolan.vlc.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.vlc.gui.network.youtube.YouTubeExtractor
import org.videolan.vlc.gui.network.youtube.YouTubePlaylist
import org.videolan.vlc.gui.network.youtube.YouTubeVideo
import org.videolan.vlc.gui.network.youtube.youTubeSubtitleLanguages
import org.videolan.vlc.gui.network.youtube.youTubeSubtitlesEnabled

private const val TAG = "VLC/YouTubeDownloadModel"

/**
 * Resolves YouTube links for [org.videolan.vlc.gui.dialogs.YouTubeDownloadDialog].
 * Kept apart from [StreamsModel] so the share entry point doesn't need the media library.
 */
class YouTubeDownloadModel(private val context: Context) : ViewModel() {

    private val _state = MutableLiveData<YouTubeResolveState>(YouTubeResolveState.Idle)
    /** State of the link being resolved, observed by the download sheet */
    val state: LiveData<YouTubeResolveState> = _state
    private var job: Job? = null

    /**
     * Resolve the available download qualities of a YouTube video [link]
     * Result is published in [state]
     */
    fun resolveVideo(link: String) = launchResolution(YouTubeResolveState.Loading) {
        val languages = if (context.youTubeSubtitlesEnabled()) context.youTubeSubtitleLanguages() else emptyList()
        YouTubeResolveState.Loaded(YouTubeExtractor.resolve(link, languages))
    }

    /**
     * List the videos of the YouTube playlist [link]
     * Result is published in [state]
     */
    fun resolvePlaylist(link: String) = launchResolution(YouTubeResolveState.LoadingPlaylist) {
        YouTubeResolveState.PlaylistLoaded(YouTubeExtractor.resolvePlaylist(link))
    }

    fun cancel() {
        job?.cancel()
        _state.value = YouTubeResolveState.Idle
    }

    private fun launchResolution(loading: YouTubeResolveState, block: suspend () -> YouTubeResolveState) {
        job?.cancel()
        _state.value = loading
        job = viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "YouTube extraction failed", e)
                YouTubeResolveState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return YouTubeDownloadModel(context.applicationContext) as T
        }
    }
}

sealed class YouTubeResolveState {
    object Idle : YouTubeResolveState()
    object Loading : YouTubeResolveState()
    class Loaded(val video: YouTubeVideo) : YouTubeResolveState()
    object LoadingPlaylist : YouTubeResolveState()
    class PlaylistLoaded(val playlist: YouTubePlaylist) : YouTubeResolveState()
    class Error(val message: String) : YouTubeResolveState()
}
