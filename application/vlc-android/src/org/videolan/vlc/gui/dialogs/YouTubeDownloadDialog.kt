/*****************************************************************************
 * YouTubeDownloadDialog.kt
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
package org.videolan.vlc.gui.dialogs

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ItemYoutubeDownloadOptionBinding
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.network.youtube.YouTubeDownloadOption
import org.videolan.vlc.gui.network.youtube.YouTubeDownloadService
import org.videolan.vlc.gui.network.youtube.YouTubeExtractor
import org.videolan.vlc.gui.network.youtube.YouTubePlaylist
import org.videolan.vlc.gui.network.youtube.YouTubeQuality
import org.videolan.vlc.gui.network.youtube.YouTubeVideo
import org.videolan.vlc.gui.network.youtube.youTubeQualityPreference
import org.videolan.vlc.gui.network.youtube.youTubeSubtitlesEnabled
import org.videolan.vlc.viewmodels.YouTubeDownloadModel
import org.videolan.vlc.viewmodels.YouTubeResolveState

/**
 * Bottom sheet listing the downloadable qualities of a YouTube link, or of a whole playlist.
 * Resolution is done by [YouTubeDownloadModel]; this sheet only renders its state.
 * When a quality is remembered in settings it is applied right away, unless [newInstance] is called with forceAsk.
 */
class YouTubeDownloadDialog : VLCBottomSheetDialogFragment() {

    companion object {
        /** Fragment result sent on the activity's fragment manager once a download is started */
        const val YOUTUBE_DOWNLOAD_STARTED = "youtube_download_started"
        private const val KEY_LINK = "yt_link"
        private const val KEY_FORCE_ASK = "yt_force_ask"

        fun newInstance(link: String, forceAsk: Boolean = false) = YouTubeDownloadDialog().apply {
            arguments = Bundle().apply {
                putString(KEY_LINK, link)
                putBoolean(KEY_FORCE_ASK, forceAsk)
            }
        }
    }

    /** One row of the picker */
    private class Row(val label: String, val audio: Boolean, val onClick: () -> Unit)

    private lateinit var viewModel: YouTubeDownloadModel
    private lateinit var optionList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var videoTitle: TextView
    private lateinit var subtitles: CheckBox
    private lateinit var playlistRow: TextView
    private val link by lazy { arguments?.getString(KEY_LINK).orEmpty() }
    private val forceAsk by lazy { arguments?.getBoolean(KEY_FORCE_ASK) == true }
    /** Set when the user picks "Whole playlist", so the remembered quality isn't applied without asking */
    private var playlistChosenManually = false

    override fun getDefaultState() = STATE_EXPANDED

    override fun needToManageOrientation() = true

    override fun initialFocusedView(): View = optionList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(requireActivity(), YouTubeDownloadModel.Factory(requireContext()))[YouTubeDownloadModel::class.java]
        // Only start a new resolution the first time, not when the sheet is re-created on rotation
        if (savedInstanceState == null) {
            if (YouTubeExtractor.isPlaylistOnlyLink(link)) viewModel.resolvePlaylist(link)
            else viewModel.resolveVideo(link)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_youtube_download, container)
        optionList = view.findViewById(R.id.yt_option_list)
        loading = view.findViewById(R.id.yt_loading)
        message = view.findViewById(R.id.yt_message)
        videoTitle = view.findViewById(R.id.yt_video_title)
        subtitles = view.findViewById(R.id.yt_subtitles)
        playlistRow = view.findViewById(R.id.yt_playlist_row)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        optionList.layoutManager = LinearLayoutManager(requireContext())
        ViewCompat.setNestedScrollingEnabled(optionList, false)
        playlistRow.setOnClickListener {
            playlistChosenManually = true
            viewModel.resolvePlaylist(link)
        }
        viewModel.state.observe(viewLifecycleOwner) { render(it) }
    }

    private fun render(state: YouTubeResolveState) {
        when (state) {
            is YouTubeResolveState.Idle, is YouTubeResolveState.Loading -> showLoading(R.string.yt_download_resolving)
            is YouTubeResolveState.LoadingPlaylist -> showLoading(R.string.yt_download_playlist_resolving)
            is YouTubeResolveState.Error -> {
                loading.visibility = View.GONE
                showMessage(getString(R.string.yt_download_resolve_failed, state.message))
            }
            is YouTubeResolveState.Loaded -> renderVideo(state.video)
            is YouTubeResolveState.PlaylistLoaded -> renderPlaylist(state.playlist)
        }
    }

    private fun showLoading(textRes: Int) {
        loading.visibility = View.VISIBLE
        subtitles.visibility = View.GONE
        playlistRow.visibility = View.GONE
        showMessage(getString(textRes))
    }

    private fun showMessage(text: String) {
        message.visibility = View.VISIBLE
        message.text = text
        optionList.visibility = View.GONE
    }

    private fun renderVideo(video: YouTubeVideo) {
        loading.visibility = View.GONE
        videoTitle.text = video.title
        videoTitle.visibility = if (video.title.isBlank()) View.GONE else View.VISIBLE
        playlistRow.visibility = if (YouTubeExtractor.isPlaylistLink(link)) View.VISIBLE else View.GONE
        if (video.options.isEmpty()) {
            subtitles.visibility = View.GONE
            showMessage(getString(R.string.yt_download_no_streams))
            return
        }
        val subtitle = video.subtitle
        if (subtitle != null) {
            val language = if (subtitle.autoGenerated) getString(R.string.yt_download_subtitles_auto, subtitle.languageName) else subtitle.languageName
            subtitles.text = getString(R.string.yt_download_subtitles_option, language)
            subtitles.isChecked = true
            subtitles.visibility = View.VISIBLE
        } else subtitles.visibility = View.GONE

        val quality = requireContext().youTubeQualityPreference()
        if (!forceAsk && quality != YouTubeQuality.ASK) {
            YouTubeExtractor.pickOption(video.options, quality)?.let { startVideo(video, it); return }
        }
        showRows(video.options.map { option -> Row(labelOf(option), option.isAudioOnly) { startVideo(video, option) } })
    }

    private fun renderPlaylist(playlist: YouTubePlaylist) {
        loading.visibility = View.GONE
        playlistRow.visibility = View.GONE
        val count = resources.getQuantityString(R.plurals.yt_download_playlist_count, playlist.videoUrls.size, playlist.videoUrls.size)
        videoTitle.text = if (playlist.title.isBlank()) count else "${playlist.title} · $count"
        videoTitle.visibility = View.VISIBLE
        if (playlist.videoUrls.isEmpty()) {
            subtitles.visibility = View.GONE
            showMessage(getString(R.string.yt_download_no_streams))
            return
        }
        // Caption languages vary per video, so this only follows the setting
        subtitles.text = getString(R.string.yt_download_subtitles_pref)
        subtitles.isChecked = requireContext().youTubeSubtitlesEnabled()
        subtitles.visibility = View.VISIBLE

        val quality = requireContext().youTubeQualityPreference()
        if (!forceAsk && !playlistChosenManually && quality != YouTubeQuality.ASK) {
            startPlaylist(playlist, quality)
            return
        }
        // Each video offers different streams: choose a target quality, matched per video at download time
        val names = resources.getStringArray(R.array.youtube_download_quality)
        val values = resources.getStringArray(R.array.youtube_download_quality_values)
        showRows(values.indices.filter { values[it] != YouTubeQuality.ASK }.map { i ->
            Row(names[i], values[i] == YouTubeQuality.AUDIO) { startPlaylist(playlist, values[i]) }
        })
    }

    private fun showRows(rows: List<Row>) {
        message.visibility = View.GONE
        optionList.visibility = View.VISIBLE
        optionList.adapter = OptionAdapter(rows)
        optionList.requestFocus()
    }

    private fun labelOf(option: YouTubeDownloadOption) = when {
        option.isAudioOnly && option.quality.isEmpty() -> getString(R.string.yt_download_audio_option_no_bitrate)
        option.isAudioOnly -> getString(R.string.yt_download_audio_option, option.quality)
        option.audioUrl != null -> getString(R.string.yt_download_hd_option, option.quality, option.extension.uppercase())
        else -> getString(R.string.yt_download_video_option, option.quality, option.extension.uppercase())
    }

    private fun startVideo(video: YouTubeVideo, option: YouTubeDownloadOption) {
        val title = video.title.ifBlank { getString(R.string.yt_download) }
        val subtitle = if (subtitles.visibility == View.VISIBLE && subtitles.isChecked) video.subtitle else null
        YouTubeDownloadService.start(requireContext().applicationContext, title, option, subtitle)
        UiTools.snacker(requireActivity(), getString(R.string.yt_download_started, title))
        finish()
    }

    private fun startPlaylist(playlist: YouTubePlaylist, quality: String) {
        val title = playlist.title.ifBlank { getString(R.string.yt_download_playlist) }
        YouTubeDownloadService.startPlaylist(requireContext().applicationContext, title, playlist.videoUrls, quality, subtitles.isChecked)
        UiTools.snacker(requireActivity(), getString(R.string.yt_download_playlist_started, playlist.videoUrls.size, title))
        finish()
    }

    private fun finish() {
        activity?.supportFragmentManager?.setFragmentResult(YOUTUBE_DOWNLOAD_STARTED, bundleOf())
        dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Stop a pending resolution if the user closes the sheet, unless it's only being re-created
        if (activity?.isChangingConfigurations != true) viewModel.cancel()
        super.onDismiss(dialog)
    }

    private inner class OptionAdapter(private val rows: List<Row>) : RecyclerView.Adapter<OptionViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
            val binding = DataBindingUtil.inflate<ItemYoutubeDownloadOptionBinding>(LayoutInflater.from(parent.context),
                    R.layout.item_youtube_download_option, parent, false)
            return OptionViewHolder(binding) { position -> rows[position].onClick() }
        }

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
            val row = rows[position]
            holder.binding.label = row.label
            holder.binding.optionIcon.setImageResource(if (row.audio) R.drawable.ic_menu_audio else R.drawable.ic_download)
            holder.binding.executePendingBindings()
        }

        override fun getItemCount() = rows.size
    }

    inner class OptionViewHolder(val binding: ItemYoutubeDownloadOptionBinding, private val listener: (Int) -> Unit) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.holder = this
        }

        fun onClick(@Suppress("UNUSED_PARAMETER") v: View) {
            listener(layoutPosition)
        }
    }
}
