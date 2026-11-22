package org.schabi.newpipe.bilai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close

/** Material icon in the app's own tint (filled/rounded set, like the YouTube app). */
@Composable
internal fun MIcon(vector: ImageVector, tint: Color, size: Dp = 24.dp, label: String? = null) {
    M3Icon(vector, label, Modifier.size(size), tint = tint)
}

/** Round icon button with a ripple, used on the player and in sheets. */
@Composable
internal fun RoundIconButton(vector: ImageVector, label: String, tint: Color, bg: Color,
                             size: Dp = 40.dp, iconSize: Dp = 22.dp, enabled: Boolean = true,
                             modifier: Modifier = Modifier, click: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(bg)
        .then(if (enabled) Modifier.clickable(onClick = click) else Modifier)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        MIcon(vector, tint.copy(alpha = if (enabled) tint.alpha else tint.alpha * .4f), iconSize)
    }
}

@Composable
internal fun BText(text: String, size: Float, color: Color, weight: FontWeight = FontWeight.Normal,
                   maxLines: Int = 1, modifier: Modifier = Modifier, lineHeight: Float = size * 1.35f) {
    BasicText(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontSize = size.sp, lineHeight = lineHeight.sp,
            fontWeight = weight, fontFamily = BilaiFont))
}

/**
 * Bottom sheet that slides up with a spring when first shown, follows the finger when its handle
 * is dragged, and slides away (velocity aware) before [dismiss] is called.
 * Call [SheetController.close] from content to animate out.
 */
internal class SheetController(internal val close: () -> Unit)

@Composable
internal fun AnimatedSheet(dismiss: () -> Unit, scrim: Float = .55f, topRadius: Dp = 16.dp,
                           maxHeightFraction: Float = .9f, fixedHeight: Dp? = null,
                           content: @Composable ColumnScope.(SheetController) -> Unit) {
    val p = LocalBilaiColors.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(1f) }      // fraction of sheet height pushed below the screen
    var sheetHeight by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { offset.animateTo(0f, spring(dampingRatio = .86f, stiffness = 420f)) }
    val latestDismiss by rememberUpdatedState(dismiss)
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            offset.animateTo(1f, tween(220))
            latestDismiss()
        }
    }
    val controller = remember { SheetController(::close) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = (1f - offset.value).coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = scrim))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier.heightIn(max = maxHeight * maxHeightFraction))
            .onSizeChanged { sheetHeight = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer { translationY = offset.value * sheetHeight }
            .clip(RoundedCornerShape(topStart = topRadius, topEnd = topRadius))
            .background(p.sheetBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            // Drag handle area
            Box(Modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y / density
                        if (offset.value * sheetHeight / density > 110f || v > 650f) close()
                        else scope.launch { offset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, delta ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + delta / sheetHeight).coerceIn(0f, 1f)) }
                }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).background(p.muted.copy(alpha = .4f), CircleShape))
            }
            content(controller)
        }
    }
}

/** A YouTube-style sheet row: leading icon, title, optional trailing value. */
@Composable
internal fun SheetRow(icon: ImageVector?, title: String, value: String? = null, valueColor: Color? = null,
                      trailing: (@Composable () -> Unit)? = null, enabled: Boolean = true, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = click) else Modifier)
        .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            MIcon(icon, if (enabled) p.text else p.muted, 22.dp)
            Spacer(Modifier.width(18.dp))
        }
        BText(title, 15f, if (enabled) p.text else p.muted, modifier = Modifier.weight(1f))
        if (value != null) BText(value, 13f, valueColor ?: p.muted, FontWeight.Medium)
        trailing?.invoke()
    }
}

@Composable
internal fun SheetTitle(title: String, back: (() -> Unit)? = null, close: (() -> Unit)? = null) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().padding(start = if (back != null) 8.dp else 20.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (back != null) RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack,
            "Back", p.text, Color.Transparent, 40.dp, 22.dp, click = back)
        BText(title, 16f, p.text, FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = if (back != null) 4.dp else 0.dp))
        if (close != null) RoundIconButton(Icons.Rounded.Close,
            "Close", p.text, Color.Transparent, 40.dp, 22.dp, click = close)
    }
}

internal fun youtubeId(url: String?): String? =
    url?.let { Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1) }

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.ROOT, "%d KB", (bytes shr 10).coerceAtLeast(1))
}
