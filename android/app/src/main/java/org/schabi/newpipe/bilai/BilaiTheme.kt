package org.schabi.newpipe.bilai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

internal data class BilaiColors(
    val bg: Color, val surface: Color, val elevated: Color, val elevatedHover: Color,
    val border: Color, val text: Color, val muted: Color, val chipActiveBg: Color,
    val chipActiveText: Color, val sheetBg: Color, val outerBg: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
)

internal val LocalBilaiColors = staticCompositionLocalOf<BilaiColors> { error("BilaiTheme required") }
internal val BilaiFont = FontFamily.SansSerif

internal fun palette(dark: Boolean, red: Boolean): BilaiColors {
    val accent = Color(if (red) 0xFFFF0033 else 0xFFF59E0B)
    val onAccent = if (red) Color.White else Color(0xFF1A1205)
    return if (dark) BilaiColors(
        Color(0xFF0F0F0F), Color(0xFF181716), Color(0xFF272523), Color(0xFF363330),
        Color.White.copy(alpha = .09f), Color(0xFFF1F1F1), Color(0xFFAAAAAA),
        Color(0xFFF1F1F1), Color(0xFF0F0F0F), Color(0xFF1E1D1B), Color(0xFF070707),
        accent, accent.copy(alpha = .14f), onAccent,
    ) else BilaiColors(
        Color.White, Color.White, Color(0xFFF2F2F2), Color(0xFFE5E5E5),
        Color.Black.copy(alpha = .08f), Color(0xFF0F0F0F), Color(0xFF606060),
        Color(0xFF0F0F0F), Color.White, Color.White, Color(0xFFE9E7E4),
        accent, accent.copy(alpha = .14f), onAccent,
    )
}

@Composable
private fun animated(color: Color): Color = animateColorAsState(color, tween(220)).value

@Composable
internal fun BilaiTheme(dark: Boolean, red: Boolean, content: @Composable () -> Unit) {
    val p = palette(dark, red)
    val colors = BilaiColors(
        animated(p.bg), animated(p.surface), animated(p.elevated), animated(p.elevatedHover),
        animated(p.border), animated(p.text), animated(p.muted), animated(p.chipActiveBg),
        animated(p.chipActiveText), animated(p.sheetBg), animated(p.outerBg),
        animated(p.accent), animated(p.accentSoft), animated(p.onAccent),
    )
    CompositionLocalProvider(LocalBilaiColors provides colors, content = content)
}
