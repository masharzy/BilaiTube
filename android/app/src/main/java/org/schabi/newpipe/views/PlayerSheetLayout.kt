package org.schabi.newpipe.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.schabi.newpipe.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts the video detail page and implements a YouTube style player sheet:
 *
 * - dragging the video down continuously morphs the whole page into a floating mini player
 *   (scale, translation, clip and corner radius all follow one progress value),
 * - releasing springs to the expanded or minimized state, taking the fling velocity into account,
 * - the animation can be caught and redirected at any time,
 * - the minimized player floats freely in 2D and springs to the nearest of the four corners,
 * - a horizontal fling dismisses it, a tap expands it.
 *
 * The page keeps its full size at all times; only view transforms and the outline clip change,
 * so no layout pass is needed while animating.
 */
class PlayerSheetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Callback {
        fun onStateChanged(state: Int)

        /** @param expandedFraction 1 when fully expanded, 0 when minimized. */
        fun onSlide(expandedFraction: Float)
    }

    interface MiniActions {
        fun onPlayPause()
        fun onClose()
        fun onExpand()
    }

    /** Fills the rect with the bounds of the video area, in this view's coordinates. */
    fun interface VideoRectProvider {
        fun provide(sheet: PlayerSheetLayout, outRect: RectF): Boolean
    }

    var videoRectProvider: VideoRectProvider? = null
    var miniActions: MiniActions? = null
    var isDraggable: Boolean = true
    var miniPlaying: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val callbacks = ArrayList<Callback>()
    private var currentState = STATE_HIDDEN

    var state: Int
        get() = currentState
        set(value) = requestState(value, animate = true)

    fun setStateNoAnimation(value: Int) = requestState(value, animate = false)

    fun addCallback(callback: Callback) {
        if (!callbacks.contains(callback)) callbacks.add(callback)
    }

    fun removeCallback(callback: Callback) {
        callbacks.remove(callback)
    }

    // ---- model -------------------------------------------------------------------------------

    /** 0 = expanded page, 1 = floating mini player. */
    private var progress = 0f

    /** Top-left of the mini video in this view's parent space (used at progress 1). */
    private var miniX = 0f
    private var miniY = 0f
    private var corner = CORNER_BOTTOM_RIGHT

    /** 0 = shown, 1 = slid out of the bottom. */
    private var hideFraction = 0f

    /** 0..1 horizontal dismissal progress and its direction. */
    private var dismissFraction = 0f
    private var dismissDir = 1f

    private val videoRect = RectF()
    private val clipRect = RectF()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingDismissVelocity = dp(1100f)
    private val flingVerticalVelocity = dp(700f)
    private val flingExpandVelocity = dp(1400f)

    // ---- animation ---------------------------------------------------------------------------

    private var progressAnim: SpringAnimation? = null
    private var xAnim: SpringAnimation? = null
    private var yAnim: SpringAnimation? = null
    private var hideAnim: SpringAnimation? = null
    private var dismissAnim: SpringAnimation? = null

    private val isAnimating: Boolean
        get() = progressAnim?.isRunning == true || xAnim?.isRunning == true ||
            yAnim?.isRunning == true || hideAnim?.isRunning == true ||
            dismissAnim?.isRunning == true

    private fun cancelAnimations() {
        for (a in listOf(progressAnim, xAnim, yAnim, hideAnim, dismissAnim)) {
            if (a != null && a.isRunning) a.cancel()
        }
    }

    // ---- drawing of the mini controls --------------------------------------------------------

    private val scrimPaint = Paint().apply { color = Color.BLACK }
    private val playDrawable: Drawable = tinted(R.drawable.ic_play_arrow)
    private val pauseDrawable: Drawable = tinted(R.drawable.ic_pause)
    private val closeDrawable: Drawable = tinted(R.drawable.ic_close)
    private val playRect = RectF()
    private val closeRect = RectF()

    private fun tinted(res: Int): Drawable {
        val d = DrawableCompat.wrap(ContextCompat.getDrawable(context, res)!!.mutate())
        DrawableCompat.setTint(d, Color.WHITE)
        return d
    }

    // ---- gesture state -----------------------------------------------------------------------

    private enum class Mode { NONE, VERTICAL, MINI_DRAG }

    private var mode = Mode.NONE
    private var passThrough = false
    private var candidateVertical = false
    private var dragStarted = false
    private var startRawX = 0f
    private var startRawY = 0f
    private var startProgress = 0f
    private var startMiniX = 0f
    private var startMiniY = 0f
    private var tracker: VelocityTracker? = null

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val p = progress.coerceIn(0f, 1f)
                computeClip(clipRect)
                val s = scaleX.coerceAtLeast(0.01f)
                val radius = if (p <= 0f) 0f else dp(MINI_CORNER_DP) * p / s
                outline.setRoundRect(
                    clipRect.left.toInt(), clipRect.top.toInt(),
                    clipRect.right.toInt(), clipRect.bottom.toInt(), radius
                )
            }
        }
        pivotX = 0f
        pivotY = 0f
        setWillNotDraw(false)
    }

    // ---- geometry ----------------------------------------------------------------------------

    private fun refreshVideoRect() {
        val w = width.toFloat()
        val ok = videoRectProvider?.provide(this, videoRect) == true && videoRect.width() > 1f &&
            videoRect.height() > 1f
        if (!ok) videoRect.set(0f, 0f, w, w * 9f / 16f)
    }

    private fun miniWidth(): Float = min(width * 0.58f, dp(280f))

    private fun miniHeight(): Float {
        val ratio = (videoRect.height() / videoRect.width()).coerceIn(9f / 16f, 1f)
        return miniWidth() * ratio
    }

    private fun miniScale(): Float = miniWidth() / max(1f, videoRect.width())

    private fun computeClip(out: RectF) {
        val p = progress.coerceIn(0f, 1f)
        val miniBottom = videoRect.top + (videoRect.width() * (miniHeight() / miniWidth()))
        out.set(
            lerp(0f, videoRect.left, p),
            lerp(0f, videoRect.top, p),
            lerp(width.toFloat(), videoRect.right, p),
            lerp(height.toFloat(), min(videoRect.bottom, miniBottom), p)
        )
    }

    private fun safeTop(): Float {
        val parentView = parent as? ViewGroup
        val toolbar = parentView?.findViewById<View>(R.id.toolbar_layout)
        val base = if (toolbar != null && toolbar.visibility == View.VISIBLE) toolbar.bottom.toFloat() else 0f
        return base + dp(MINI_MARGIN_DP)
    }

    private fun safeBottom(): Float {
        val parentView = parent as? ViewGroup
        val nav = parentView?.findViewById<View>(R.id.main_bottom_nav)
        if (nav != null && nav.visibility == View.VISIBLE && nav.height > 0) {
            return nav.top.toFloat() - dp(MINI_MARGIN_DP)
        }
        var bottom = height.toFloat()
        val insets = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        if (insets != null) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val screenBottom = loc[1] + height
            val rootBottom = rootView.height
            val overlap = max(0, insets.bottom - (rootBottom - screenBottom))
            bottom -= overlap
        }
        return bottom - dp(MINI_MARGIN_DP)
    }

    private fun cornerPoint(c: Int): Pair<Float, Float> {
        val m = dp(MINI_MARGIN_DP)
        val left = m
        val right = width - miniWidth() - m
        val top = safeTop()
        val bottom = max(top, safeBottom() - miniHeight())
        return when (c) {
            CORNER_TOP_LEFT -> left to top
            CORNER_TOP_RIGHT -> right to top
            CORNER_BOTTOM_LEFT -> left to bottom
            else -> right to bottom
        }
    }

    private fun placeAtCorner(c: Int) {
        corner = c
        val (x, y) = cornerPoint(c)
        miniX = x
        miniY = y
    }

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = CORNER_BOTTOM_RIGHT
        var bestDist = Float.MAX_VALUE
        for (c in 0..3) {
            val (cx, cy) = cornerPoint(c)
            val d = hypot(cx - x, cy - y)
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    /** Vertical distance the video top travels between expanded and minimized. */
    private fun dragDistance(): Float {
        val (_, y) = cornerPoint(corner)
        return max(dp(120f), y - videoRect.top)
    }

    // ---- transform ---------------------------------------------------------------------------

    private fun applyTransform() {
        if (width == 0 || height == 0) return
        val p = progress
        val sMini = miniScale()
        val s = lerp(1f, sMini, p)
        scaleX = s
        scaleY = s
        val dismissDx = dismissDir * dismissFraction * (width + miniWidth())
        translationX = lerp(0f, miniX - sMini * videoRect.left, p) + dismissDx
        translationY = lerp(0f, miniY - sMini * videoRect.top, p) + hideFraction * height
        elevation = lerp(0f, dp(MINI_ELEVATION_DP), p.coerceIn(0f, 1f))
        alpha = (1f - dismissFraction).coerceIn(0f, 1f)
        invalidateOutline()
        invalidate()
        val expandedFraction = (1f - p).coerceIn(0f, 1f)
        for (cb in callbacks.toList()) cb.onSlide(expandedFraction)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- state -------------------------------------------------------------------------------

    private fun notifyState(newState: Int) {
        if (currentState == newState) return
        currentState = newState
        for (cb in callbacks.toList()) cb.onStateChanged(newState)
    }

    private fun requestState(target: Int, animate: Boolean) {
        if (width == 0 || height == 0) {
            pendingState = target
            if (target != STATE_HIDDEN) visibility = View.VISIBLE
            return
        }
        pendingState = NO_PENDING
        when (target) {
            STATE_EXPANDED, STATE_COLLAPSED -> showTo(target, animate)
            STATE_HIDDEN -> hideSheet(animate)
            else -> Unit
        }
    }

    private var pendingState = NO_PENDING

    private fun showTo(target: Int, animate: Boolean) {
        val wasHidden = currentState == STATE_HIDDEN || visibility != View.VISIBLE
        cancelAnimations()
        visibility = View.VISIBLE
        refreshVideoRectIfExpanded()
        dismissFraction = 0f
        if (wasHidden) {
            progress = if (target == STATE_COLLAPSED) 1f else 0f
            placeAtCorner(corner)
            hideFraction = if (animate) 1f else 0f
        }
        val targetP = if (target == STATE_COLLAPSED) 1f else 0f
        if (!animate) {
            progress = targetP
            hideFraction = 0f
            applyTransform()
            settle()
            return
        }
        applyTransform()
        notifyState(STATE_SETTLING)
        if (hideFraction > 0f) springHide(0f)
        if (progress != targetP) springProgress(targetP, 0f)
        if (!isAnimating) settle()
    }

    private fun hideSheet(animate: Boolean) {
        cancelAnimations()
        if (!animate || currentState == STATE_HIDDEN) {
            hideFraction = 1f
            applyTransform()
            settle()
            return
        }
        notifyState(STATE_SETTLING)
        springHide(1f)
    }

    private fun refreshVideoRectIfExpanded() {
        if (progress < 0.01f) refreshVideoRect() else if (videoRect.isEmpty) refreshVideoRect()
    }

    private fun settle() {
        when {
            hideFraction > 0.99f || dismissFraction > 0.99f -> {
                hideFraction = 1f
                dismissFraction = 0f
                alpha = 1f
                visibility = View.INVISIBLE
                notifyState(STATE_HIDDEN)
            }
            progress < 0.01f -> {
                progress = 0f
                hideFraction = 0f
                applyTransform()
                notifyState(STATE_EXPANDED)
            }
            progress > 0.99f -> {
                progress = 1f
                hideFraction = 0f
                applyTransform()
                notifyState(STATE_COLLAPSED)
            }
        }
    }

    // ---- springs -----------------------------------------------------------------------------

    private fun springProgress(target: Float, velocity: Float) {
        progressAnim?.cancel()
        val toMini = target > 0.5f
        progressAnim = SpringAnimation(this, PROGRESS, target).apply {
            spring = SpringForce(target)
                .setDampingRatio(if (toMini) 0.74f else 0.9f)
                .setStiffness(if (toMini) 420f else 480f)
            setStartVelocity(velocity)
            setMinValue(0f)
            setMaxValue(1.08f)
            addEndListener { _, canceled, _, _ -> if (!canceled) onSpringEnd() }
            start()
        }
    }

    private fun springHide(target: Float) {
        hideAnim?.cancel()
        hideAnim = SpringAnimation(this, HIDE, target).apply {
            spring = SpringForce(target).setDampingRatio(0.9f).setStiffness(380f)
            setMinValue(0f)
            setMaxValue(1f)
            addEndListener { _, canceled, _, _ -> if (!canceled) onSpringEnd() }
            start()
        }
    }

    private fun springMini(tx: Float, ty: Float, vx: Float, vy: Float) {
        xAnim?.cancel()
        yAnim?.cancel()
        xAnim = SpringAnimation(this, MINI_X, tx).apply {
            spring = SpringForce(tx).setDampingRatio(0.72f).setStiffness(320f)
            setStartVelocity(vx)
            addEndListener { _, canceled, _, _ -> if (!canceled) onSpringEnd() }
            start()
        }
        yAnim = SpringAnimation(this, MINI_Y, ty).apply {
            spring = SpringForce(ty).setDampingRatio(0.72f).setStiffness(320f)
            setStartVelocity(vy)
            addEndListener { _, canceled, _, _ -> if (!canceled) onSpringEnd() }
            start()
        }
    }

    private fun springDismiss(velocityFractionPerSecond: Float) {
        dismissAnim?.cancel()
        dismissAnim = SpringAnimation(this, DISMISS, 1f).apply {
            spring = SpringForce(1f).setDampingRatio(1f).setStiffness(260f)
            setStartVelocity(velocityFractionPerSecond)
            setMinValue(0f)
            setMaxValue(1f)
            addEndListener { _, canceled, _, _ -> if (!canceled) onSpringEnd() }
            start()
        }
    }

    private fun onSpringEnd() {
        if (!isAnimating) settle()
    }

    // ---- touch -------------------------------------------------------------------------------

    private fun miniActive() = currentState == STATE_COLLAPSED ||
        (progress >= 0.995f && hideFraction < 0.5f && dismissFraction < 0.5f)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
            passThrough = false
            mode = Mode.NONE
            candidateVertical = false
            dragStarted = false
            if (currentState == STATE_HIDDEN || visibility != View.VISIBLE) {
                passThrough = true
            } else {
                computeClip(clipRect)
                val inside = clipRect.contains(ev.x, ev.y)
                if (progress > 0.001f && !inside) passThrough = true
            }
            if (passThrough) return false
            onGestureDown(ev)
        } else if (passThrough) {
            return false
        }
        feedTracker(ev)
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker?.recycle()
            tracker = null
        }
        return handled
    }

    private fun feedTracker(ev: MotionEvent) {
        val t = tracker ?: return
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(ev.rawX - ev.x, ev.rawY - ev.y)
        t.addMovement(copy)
        copy.recycle()
    }

    private fun onGestureDown(ev: MotionEvent) {
        startRawX = ev.rawX
        startRawY = ev.rawY
        startMiniX = miniX
        startMiniY = miniY
        startProgress = progress
        val wasAnimating = isAnimating
        if (wasAnimating) {
            // catch the running animation and continue from where it is
            cancelAnimations()
            if (hideFraction < 0.5f && dismissFraction < 0.5f) {
                beginGesture(if (progress >= 0.995f) Mode.MINI_DRAG else Mode.VERTICAL)
                dragStarted = true
                notifyState(STATE_DRAGGING)
            }
        } else if (currentState == STATE_COLLAPSED) {
            beginGesture(Mode.MINI_DRAG)
        } else if (currentState == STATE_EXPANDED && isDraggable) {
            refreshVideoRect()
            candidateVertical = videoRect.contains(ev.x, ev.y)
        }
    }

    private fun beginGesture(newMode: Mode) {
        mode = newMode
        startProgress = progress
        startMiniX = miniX
        startMiniY = miniY
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (currentState == STATE_HIDDEN) return false
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> mode != Mode.NONE
            MotionEvent.ACTION_MOVE -> {
                if (mode != Mode.NONE) return true
                if (candidateVertical && isDraggable) {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (dy > touchSlop && abs(dy) > abs(dx) * 1.3f) {
                        beginGesture(Mode.VERTICAL)
                        dragStarted = true
                        notifyState(STATE_DRAGGING)
                        return true
                    }
                }
                false
            }
            else -> false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_UP -> onUp(ev, cancelled = false)
            MotionEvent.ACTION_CANCEL -> onUp(ev, cancelled = true)
        }
        return true
    }

    private fun onMove(ev: MotionEvent) {
        val dx = ev.rawX - startRawX
        val dy = ev.rawY - startRawY
        when (mode) {
            Mode.VERTICAL -> {
                progress = (startProgress + dy / dragDistance()).coerceIn(0f, 1f)
                applyTransform()
            }
            Mode.MINI_DRAG -> {
                if (!dragStarted) {
                    if (hypot(dx, dy) < touchSlop) return
                    dragStarted = true
                    notifyState(STATE_DRAGGING)
                }
                miniX = startMiniX + dx
                miniY = startMiniY + dy
                applyTransform()
            }
            Mode.NONE -> Unit
        }
    }

    private fun onUp(ev: MotionEvent, cancelled: Boolean) {
        val t = tracker
        t?.computeCurrentVelocity(1000)
        val vx = t?.xVelocity ?: 0f
        val vy = t?.yVelocity ?: 0f
        val currentMode = mode
        mode = Mode.NONE
        when (currentMode) {
            Mode.VERTICAL -> releaseVertical(vy, cancelled)
            Mode.MINI_DRAG -> {
                if (!dragStarted && !cancelled) {
                    onMiniTap(ev.x, ev.y)
                } else {
                    releaseMini(vx, vy, cancelled)
                }
            }
            Mode.NONE -> Unit
        }
    }

    private fun releaseVertical(vy: Float, cancelled: Boolean) {
        val toMini = when {
            cancelled -> progress > 0.5f
            abs(vy) > flingVerticalVelocity -> vy > 0
            else -> progress > 0.5f
        }
        notifyState(STATE_SETTLING)
        springProgress(if (toMini) 1f else 0f, vy / dragDistance())
    }

    private fun releaseMini(vx: Float, vy: Float, cancelled: Boolean) {
        val width = width.toFloat()
        val centerX = miniX + miniWidth() / 2f
        val horizontalFling = abs(vx) > flingDismissVelocity && abs(vx) > abs(vy) * 1.2f
        val pastEdge = centerX < -miniWidth() * 0.05f || centerX > width + miniWidth() * 0.05f
        if (!cancelled && abs(vy) > flingExpandVelocity && vy < 0 && abs(vy) > abs(vx)) {
            // fast upward flick: back to the full page
            notifyState(STATE_SETTLING)
            springProgress(0f, 0f)
            return
        }
        if (!cancelled && (pastEdge || (horizontalFling && (vx > 0) == (centerX > width / 2f)))) {
            dismissDir = if (vx != 0f && horizontalFling) (if (vx > 0) 1f else -1f)
            else (if (centerX > width / 2f) 1f else -1f)
            notifyState(STATE_SETTLING)
            springDismiss(abs(vx) / (width + miniWidth()))
            return
        }
        // project momentum, then snap to the nearest safe corner
        val projX = miniX + vx * MOMENTUM_SECONDS
        val projY = miniY + vy * MOMENTUM_SECONDS
        val c = nearestCorner(projX, projY)
        corner = c
        val (tx, ty) = cornerPoint(c)
        notifyState(STATE_SETTLING)
        springMini(tx, ty, vx, vy)
    }

    private fun onMiniTap(x: Float, y: Float) {
        val pad = dp(10f) / miniScale()
        val actions = miniActions
        when {
            RectF(closeRect).apply { inset(-pad, -pad) }.contains(x, y) -> actions?.onClose()
            RectF(playRect).apply { inset(-pad, -pad) }.contains(x, y) -> actions?.onPlayPause()
            else -> {
                actions?.onExpand()
                state = STATE_EXPANDED
            }
        }
    }

    // ---- layout / draw -----------------------------------------------------------------------

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (width == 0 || height == 0) return
        if (!isAnimating && mode == Mode.NONE) {
            refreshVideoRectIfExpanded()
        }
        val pending = pendingState
        if (pending != NO_PENDING) {
            pendingState = NO_PENDING
            post { requestState(pending, animate = false) }
        } else if (changed && !isAnimating) {
            placeAtCorner(corner)
            applyTransform()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val fade = ((progress - 0.8f) / 0.2f).coerceIn(0f, 1f) * (1f - dismissFraction)
        if (fade <= 0f) return
        val clip = RectF()
        computeClip(clip)
        val sMini = miniScale()
        scrimPaint.alpha = (fade * 0.38f * 255f).toInt()
        canvas.drawRect(clip, scrimPaint)
        val size = dp(40f) / sMini
        playRect.set(
            clip.centerX() - size / 2f, clip.centerY() - size / 2f,
            clip.centerX() + size / 2f, clip.centerY() + size / 2f
        )
        val closeSize = dp(30f) / sMini
        val inset = dp(4f) / sMini
        closeRect.set(
            clip.right - inset - closeSize, clip.top + inset,
            clip.right - inset, clip.top + inset + closeSize
        )
        val icon = if (miniPlaying) pauseDrawable else playDrawable
        icon.alpha = (fade * 255).toInt()
        icon.setBounds(playRect.left.toInt(), playRect.top.toInt(), playRect.right.toInt(), playRect.bottom.toInt())
        icon.draw(canvas)
        closeDrawable.alpha = (fade * 255).toInt()
        closeDrawable.setBounds(
            closeRect.left.toInt(), closeRect.top.toInt(), closeRect.right.toInt(), closeRect.bottom.toInt()
        )
        closeDrawable.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        // Same values as BottomSheetBehavior, so existing state handling keeps working.
        const val STATE_DRAGGING = 1
        const val STATE_SETTLING = 2
        const val STATE_EXPANDED = 3
        const val STATE_COLLAPSED = 4
        const val STATE_HIDDEN = 5

        private const val NO_PENDING = -1
        private const val CORNER_TOP_LEFT = 0
        private const val CORNER_TOP_RIGHT = 1
        private const val CORNER_BOTTOM_LEFT = 2
        private const val CORNER_BOTTOM_RIGHT = 3

        private const val MINI_CORNER_DP = 14f
        private const val MINI_MARGIN_DP = 12f
        private const val MINI_ELEVATION_DP = 12f
        private const val MOMENTUM_SECONDS = 0.22f

        private val PROGRESS = object : FloatPropertyCompat<PlayerSheetLayout>("progress") {
            override fun getValue(o: PlayerSheetLayout) = o.progress
            override fun setValue(o: PlayerSheetLayout, v: Float) {
                o.progress = v
                o.applyTransform()
            }
        }
        private val HIDE = object : FloatPropertyCompat<PlayerSheetLayout>("hide") {
            override fun getValue(o: PlayerSheetLayout) = o.hideFraction
            override fun setValue(o: PlayerSheetLayout, v: Float) {
                o.hideFraction = v
                o.applyTransform()
            }
        }
        private val DISMISS = object : FloatPropertyCompat<PlayerSheetLayout>("dismiss") {
            override fun getValue(o: PlayerSheetLayout) = o.dismissFraction
            override fun setValue(o: PlayerSheetLayout, v: Float) {
                o.dismissFraction = v
                o.applyTransform()
            }
        }
        private val MINI_X = object : FloatPropertyCompat<PlayerSheetLayout>("miniX") {
            override fun getValue(o: PlayerSheetLayout) = o.miniX
            override fun setValue(o: PlayerSheetLayout, v: Float) {
                o.miniX = v
                o.applyTransform()
            }
        }
        private val MINI_Y = object : FloatPropertyCompat<PlayerSheetLayout>("miniY") {
            override fun getValue(o: PlayerSheetLayout) = o.miniY
            override fun setValue(o: PlayerSheetLayout, v: Float) {
                o.miniY = v
                o.applyTransform()
            }
        }
    }
}
