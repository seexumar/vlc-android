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
import org.videolan.vlc.gui.network.youtube.YouTubeVideo
import org.videolan.vlc.viewmodels.StreamsModel
import org.videolan.vlc.viewmodels.YouTubeResolveState

/**
 * Bottom sheet listing the downloadable qualities of a YouTube link.
 * Resolution is done by [StreamsModel.resolveYouTube]; this sheet only renders its state.
 */
class YouTubeDownloadDialog : VLCBottomSheetDialogFragment() {

    companion object {
        private const val KEY_LINK = "yt_link"

        fun newInstance(link: String) = YouTubeDownloadDialog().apply {
            arguments = Bundle().apply { putString(KEY_LINK, link) }
        }
    }

    private lateinit var viewModel: StreamsModel
    private lateinit var optionList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var videoTitle: TextView

    override fun getDefaultState() = STATE_EXPANDED

    override fun needToManageOrientation() = true

    override fun initialFocusedView(): View = optionList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(requireActivity(), StreamsModel.Factory(requireContext()))[StreamsModel::class.java]
        // Only start a new resolution the first time, not when the sheet is re-created on rotation
        if (savedInstanceState == null) arguments?.getString(KEY_LINK)?.let { viewModel.resolveYouTube(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_youtube_download, container)
        optionList = view.findViewById(R.id.yt_option_list)
        loading = view.findViewById(R.id.yt_loading)
        message = view.findViewById(R.id.yt_message)
        videoTitle = view.findViewById(R.id.yt_video_title)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        optionList.layoutManager = LinearLayoutManager(requireContext())
        ViewCompat.setNestedScrollingEnabled(optionList, false)
        viewModel.youTubeState.observe(viewLifecycleOwner) { render(it) }
    }

    private fun render(state: YouTubeResolveState) {
        when (state) {
            is YouTubeResolveState.Idle, is YouTubeResolveState.Loading -> {
                loading.visibility = View.VISIBLE
                message.visibility = View.VISIBLE
                message.setText(R.string.yt_download_resolving)
                optionList.visibility = View.GONE
            }
            is YouTubeResolveState.Error -> {
                loading.visibility = View.GONE
                message.visibility = View.VISIBLE
                message.text = getString(R.string.yt_download_resolve_failed, state.message)
                optionList.visibility = View.GONE
            }
            is YouTubeResolveState.Loaded -> {
                loading.visibility = View.GONE
                videoTitle.text = state.video.title
                videoTitle.visibility = if (state.video.title.isBlank()) View.GONE else View.VISIBLE
                if (state.video.options.isEmpty()) {
                    message.visibility = View.VISIBLE
                    message.setText(R.string.yt_download_no_streams)
                    optionList.visibility = View.GONE
                } else {
                    message.visibility = View.GONE
                    optionList.visibility = View.VISIBLE
                    optionList.adapter = OptionAdapter(state.video)
                    optionList.requestFocus()
                }
            }
        }
    }

    private fun labelOf(option: YouTubeDownloadOption) = when {
        option.isAudioOnly && option.quality.isEmpty() -> getString(R.string.yt_download_audio_option_no_bitrate)
        option.isAudioOnly -> getString(R.string.yt_download_audio_option, option.quality)
        option.audioUrl != null -> getString(R.string.yt_download_hd_option, option.quality, option.extension.uppercase())
        else -> getString(R.string.yt_download_video_option, option.quality, option.extension.uppercase())
    }

    private fun onOptionSelected(video: YouTubeVideo, option: YouTubeDownloadOption) {
        val title = video.title.ifBlank { getString(R.string.yt_download) }
        YouTubeDownloadService.start(requireContext().applicationContext, title, option)
        UiTools.snacker(requireActivity(), getString(R.string.yt_download_started, title))
        viewModel.observableSearchText.set("")
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Stop a pending resolution if the user closes the sheet, unless it's only being re-created
        if (!requireActivity().isChangingConfigurations) viewModel.cancelYouTube()
        super.onDismiss(dialog)
    }

    inner class OptionAdapter(private val video: YouTubeVideo) : RecyclerView.Adapter<OptionViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
            val binding = DataBindingUtil.inflate<ItemYoutubeDownloadOptionBinding>(LayoutInflater.from(parent.context),
                    R.layout.item_youtube_download_option, parent, false)
            return OptionViewHolder(binding) { position -> onOptionSelected(video, video.options[position]) }
        }

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
            val option = video.options[position]
            holder.binding.label = labelOf(option)
            holder.binding.optionIcon.setImageResource(if (option.isAudioOnly) R.drawable.ic_menu_audio else R.drawable.ic_download)
            holder.binding.executePendingBindings()
        }

        override fun getItemCount() = video.options.size
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
