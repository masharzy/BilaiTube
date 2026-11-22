package org.schabi.newpipe.fragments.detail

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.graphics.RectF
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import org.schabi.newpipe.views.PlayerSheetLayout
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.fragments.list.comments.CommentReplyFragment
import org.schabi.newpipe.fragments.list.comments.CommentsFragment
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.PicassoHelper

/**
 * The bottom sheet holding the detail page, and the mini player bar shown on top of the page
 * while the sheet is collapsed.
 *
 * The sheet itself belongs to the activity: this class only exists while the page has views and
 * removes all of its callbacks in [release].
 */
class MiniPlayerSheetController(
    private val fragment: Fragment,
    private val binding: FragmentVideoDetailBinding,
    private val playerView: DetailPlayerViewHost,
    private val host: Host,
) {
    interface Host {
        val player: Player?
        val currentInfo: StreamInfo?

        /** The last stable state of the sheet, saved by the detail page across recreations. */
        var savedSheetState: Int

        /** The page was swiped away. */
        fun onSheetHidden()

        fun onPlayPauseClicked()

        fun openUploaderChannel()
    }

    private val activity get() = fragment.requireActivity()
    private val overlay get() = binding.overlayLayout
    private val sheet: PlayerSheetLayout = activity.findViewById(R.id.player_sheet)
    private var pendingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val sheetCallback = object : PlayerSheetLayout.Callback {
        override fun onStateChanged(state: Int) = onSheetStateChanged(state)

        override fun onSlide(expandedFraction: Float) = Unit
    }

    private val miniActions = object : PlayerSheetLayout.MiniActions {
        override fun onPlayPause() = host.onPlayPauseClicked()

        override fun onClose() = hide()

        override fun onExpand() = Unit
    }

    /** Collapses the page when the user opens another page from the detail page. */
    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        val top = activity.supportFragmentManager.fragments.lastOrNull()
            ?: return@OnBackStackChangedListener
        // comments and replies are opened inside the detail page
        if (top !is CommentsFragment && top !is CommentReplyFragment
            && sheet.state == PlayerSheetLayout.STATE_EXPANDED) {
            sheet.state = PlayerSheetLayout.STATE_COLLAPSED
        }
    }

    val state: Int get() = sheet.state

    init {
        // The old mini bar is replaced by the live floating mini player.
        overlay.root.visibility = View.GONE
        val restoredState = sanitizeSheetState(host.savedSheetState)
        host.savedSheetState = restoredState
        // The player may already be in fullscreen when this view is (re)created
        // (e.g. after a rotation while playing fullscreen): keep the drag disabled in that case.
        updateDraggableForFullscreen()
        sheet.videoRectProvider = PlayerSheetLayout.VideoRectProvider { s, out -> provideVideoRect(s, out) }
        sheet.miniActions = miniActions
        sheet.setStateNoAnimation(restoredState)
        syncStateAfterLayout(restoredState)

        sheet.addCallback(sheetCallback)
        activity.supportFragmentManager.addOnBackStackChangedListener(backStackListener)
    }

    private val tmpRect = android.graphics.Rect()

    private fun provideVideoRect(s: PlayerSheetLayout, out: RectF): Boolean {
        val sticky = binding.stickyPlayerContainer
        val target: View = if (sticky.visibility == View.VISIBLE && sticky.height > 0) {
            sticky
        } else {
            binding.detailThumbnailRootLayout.root
        }
        if (target.width == 0 || target.height == 0) return false
        tmpRect.set(0, 0, target.width, target.height)
        return try {
            s.offsetDescendantRectToMyCoords(target, tmpRect)
            out.set(tmpRect)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun expand() {
        sheet.state = PlayerSheetLayout.STATE_EXPANDED
    }

    fun hide() {
        sheet.state = PlayerSheetLayout.STATE_HIDDEN
    }

    /**
     * While the player is in fullscreen the sheet drag (which collapses the whole page into the
     * mini player) must never start: a vertical swipe in fullscreen belongs to the player's own
     * gestures. The exception is swipe-down-to-minimize in fullscreen, which is exactly that drag.
     */
    fun updateDraggableForFullscreen() {
        val inFullscreen = host.player?.isFullscreen == true
        val minimizeBySheet = inFullscreen && PlayerHelper.getMinimizeGestureMode(activity) ==
            PlayerHelper.MinimizeGestureMode.MINIMIZE_GESTURE_FULLSCREEN_AND_NON_FULLSCREEN
        sheet.isDraggable = !inFullscreen || minimizeBySheet
    }

    fun updateOverlayData(title: String?, uploader: String?, thumbnailUrl: String?) {
        overlay.overlayTitleTextView.text = title.orEmpty()
        overlay.overlayChannelTextView.text = uploader.orEmpty()
        overlay.overlayThumbnail.setImageResource(R.drawable.dummy_thumbnail_dark)
        PicassoHelper.loadScaledDownThumbnail(fragment.context, thumbnailUrl)
            .tag(PICASSO_VIDEO_DETAILS_TAG)
            .into(overlay.overlayThumbnail)
    }

    fun setPlayPauseImage(playing: Boolean) {
        sheet.miniPlaying = playing
        overlay.overlayPlayPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    /**
     * For Android TV: moves the focus to the main fragment, or to the player otherwise.
     */
    fun moveFocusToMainFragment(toMain: Boolean) {
        playerView.setupBrightness()
        val mainFragment = activity.findViewById<ViewGroup>(R.id.fragment_holder)
        // Hamburger button steals the focus even under the bottom sheet
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        val page = fragment.requireView() as ViewGroup
        if (toMain) {
            mainFragment.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            toolbar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Only focus the main fragment if neither it (e.g. search results) nor the toolbar
            // (e.g. the search field) has the focus, see #7490
            if (!mainFragment.hasFocus() && !toolbar.hasFocus()) {
                mainFragment.requestFocus()
            }
        } else {
            mainFragment.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            toolbar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (!binding.root.hasFocus()) {
                binding.detailThumbnailRootLayout.root.requestFocus()
            }
        }
    }

    fun release() {
        sheet.removeCallback(sheetCallback)
        sheet.videoRectProvider = null
        sheet.miniActions = null
        activity.supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        pendingLayoutListener?.let {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        pendingLayoutListener = null
    }

    private fun onSheetStateChanged(newState: Int) {
        if (newState == PlayerSheetLayout.STATE_DRAGGING ||
            newState == PlayerSheetLayout.STATE_SETTLING) {
            val player = host.player
            if (player?.isFullscreen == true) {
                playerView.showSystemUi()
            }
            if (player?.isControlsVisible == true) {
                player.hideControls(0, 0)
            }
            return
        }
        host.savedSheetState = newState
        val player = host.player
        when (newState) {
            PlayerSheetLayout.STATE_HIDDEN -> {
                moveFocusToMainFragment(true)
                host.onSheetHidden()
            }
            PlayerSheetLayout.STATE_EXPANDED -> {
                moveFocusToMainFragment(false)
                playerView.hideSystemUiIfNeeded()
                if (player != null && player.isPlaying && player.videoPlayerSelected()) {
                    player.startBCPlayer()
                }
            }
            PlayerSheetLayout.STATE_COLLAPSED -> {
                moveFocusToMainFragment(true)
                if (player != null) {
                    if (player.isFullscreen) {
                        // Minimized by swiping down inside fullscreen: the player view must leave
                        // fullscreen too, otherwise the full-size layout is squeezed into the
                        // mini player. Keep the orientation: rotating back is unwanted.
                        player.exitFullscreenLayoutOnly()
                    }
                    player.closeItemsList()
                    player.pauseBCPlayer()
                }
            }
        }
    }

    /**
     * Re-apply the saved state once the views have been laid out, since the page may have been
     * restored while the sheet was still being measured.
     */
    private fun syncStateAfterLayout(state: Int) {
        val observer = binding.root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                pendingLayoutListener = null
                host.savedSheetState = state
                if (sheet.state != state) {
                    sheet.setStateNoAnimation(state)
                }
            }
        }
        pendingLayoutListener = listener
        observer.addOnGlobalLayoutListener(listener)
    }

    companion object {
        /** Settling or dragging states can't be restored: the target state is unknown. */
        @JvmStatic
        fun sanitizeSheetState(state: Int): Int = when (state) {
            PlayerSheetLayout.STATE_COLLAPSED, PlayerSheetLayout.STATE_EXPANDED,
            PlayerSheetLayout.STATE_HIDDEN -> state
            else -> PlayerSheetLayout.STATE_COLLAPSED
        }
    }
}
