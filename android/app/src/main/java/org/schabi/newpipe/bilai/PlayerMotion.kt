package org.schabi.newpipe.bilai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal class PlayerMotion {
    var morph by mutableFloatStateOf(.85f)
        private set
    var x by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set
    var width = 0f
        private set
    var height = 0f
        private set
    val fullHeight get() = (width * 9 / 16).roundToInt().toFloat()
    val miniWidth get() = min(186f, width * .47f).roundToInt().toFloat()
    val miniHeight get() = (miniWidth * 9 / 16).roundToInt().toFloat()
    val travel get() = maxOf(240f, height - fullHeight - 90f)
    private var right = true
    private var bottom = true
    private var targetM = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var vm = -2.4f
    private var vx = 0f
    private var vy = 0f
    private var downM = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    val minimized get() = targetM == 1f

    fun resize(w: Float, h: Float) {
        if (width == w && height == h) return
        width = w
        height = h
        updateCorner()
        x = targetX
        y = targetY
    }
    fun opened() {
        dragging = false
        updateCorner()
        x = targetX; y = targetY
        morph = .85f
        expand(-2.4f)
    }
    fun expand(velocity: Float) { targetM = 0f; vm = velocity }
    fun minimize(velocity: Float) {
        targetM = 1f; vm = velocity
        updateCorner(); vx = 0f; vy = 0f
    }
    private fun updateCorner() {
        targetX = if (right) width - miniWidth - 12f else 12f
        targetY = if (bottom) height - miniHeight - 68f else 58f
    }
    fun down() { dragging = true; downM = morph; downX = x; downY = y }
    fun move(dx: Float, dy: Float) {
        if (downM < .5f) {
            val raw = downM + dy / travel
            morph = when { raw < 0 -> raw * .18f; raw > 1 -> 1 + (raw - 1) * .22f; else -> raw }
        } else { x = downX + dx; y = downY + dy }
    }
    fun release(dx: Float, dy: Float, velocityX: Float, velocityY: Float): Boolean {
        dragging = false
        if (dx * dx + dy * dy < 64f) {
            if (minimized) expand(-1.4f)
            return false
        }
        if (downM < .5f) {
            if (velocityY > 420 || (morph > .34f && velocityY > -250)) minimize(maxOf(.8f, velocityY / travel))
            else expand(min(-.8f, velocityY / travel))
        } else {
            if (x < -miniWidth * .42f || velocityX < -1100 || x > width - miniWidth * .58f || velocityX > 1100) return true
            if (velocityY < -420 && dy < -8) { expand(-1.4f); updateCorner(); return false }
            right = x + velocityX * .18f > (width - miniWidth) / 2
            bottom = y + velocityY * .18f > (height - miniHeight - 10f) / 2
            updateCorner()
            vx = velocityX * .65f
            vy = velocityY * .65f
        }
        return false
    }
    suspend fun run() {
        var last = withFrameNanos { it }
        while (!dragging && (morph != targetM || x != targetX || y != targetY || vm != 0f || vx != 0f || vy != 0f)) {
            val now = withFrameNanos { it }
            if (dragging) return
            val dt = min(.032f, (now - last) / 1e9f)
            last = now
            vm += (-320f * (morph - targetM) - 28f * vm) * dt
            morph += vm * dt
            if (abs(morph - targetM) <= .0015f && abs(vm) <= .005f) { morph = targetM; vm = 0f }
            vx += (-320f * (x - targetX) - 28f * vx) * dt
            x += vx * dt
            if (abs(x - targetX) <= .3f && abs(vx) <= 1.5f) { x = targetX; vx = 0f }
            vy += (-320f * (y - targetY) - 28f * vy) * dt
            y += vy * dt
            if (abs(y - targetY) <= .3f && abs(vy) <= 1.5f) { y = targetY; vy = 0f }
        }
    }
}
