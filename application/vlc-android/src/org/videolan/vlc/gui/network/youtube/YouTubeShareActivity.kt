/*****************************************************************************
 * YouTubeShareActivity.kt
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

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import org.videolan.vlc.R
import org.videolan.vlc.gui.BaseActivity
import org.videolan.vlc.gui.dialogs.YouTubeDownloadDialog

/**
 * "Download with VLC" share target: receives a link shared from the YouTube app (or a browser)
 * and opens the download sheet over whatever app the user was in.
 * Kept separate from [org.videolan.vlc.gui.DialogActivity] so that activity stays unexported.
 */
class YouTubeShareActivity : BaseActivity() {

    override fun getSnackAnchorView(overAudioPlayer: Boolean): View? = findViewById(android.R.id.content)

    override fun forcedTheme() = R.style.Theme_VLC_TransparentSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        // Registered before super.onCreate so it also catches the sheet restored after a rotation
        supportFragmentManager.addFragmentOnAttachListener { _, fragment ->
            // Close this invisible host together with the sheet
            (fragment as? YouTubeDownloadDialog)?.onDismissListener = DialogInterface.OnDismissListener {
                if (!isChangingConfigurations) finish()
            }
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.transparent)
        // The sheet is restored by the fragment manager after a configuration change
        if (savedInstanceState != null) return
        val link = findYouTubeLink(intent)
        if (link == null) {
            Toast.makeText(this, R.string.yt_download_not_youtube, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        YouTubeDownloadDialog.newInstance(link).show(supportFragmentManager, "fragment_youtube_download")
    }

    /** Shared text is often "Video title https://youtu.be/…", so look for the link inside it */
    private fun findYouTubeLink(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://\\S+").findAll(text).map { it.value.trimEnd('.', ',', ')') }
                .firstOrNull { YouTubeExtractor.isYouTubeLink(it) }
    }
}
