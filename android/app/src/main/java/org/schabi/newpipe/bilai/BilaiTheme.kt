package org.schabi.newpipe.bilai

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The BilaiTube "Warm Amber" palette. */
object Bilai {
    val Background = Color(0xFF111010)
    val Amoled = Color(0xFF000000)
    val Surface = Color(0xFF1B1917)
    val Pill = Color(0xFF262320)
    val Amber = Color(0xFFF59E0B)
    val Highlight = Color(0xFFFBBF24)
    val OnAmber = Color(0xFF1A1205)
    val TextPrimary = Color(0xFFF5F5F4)
    val TextSecondary = Color(0xFFA8A29E)
    val Live = Color(0xFFDC2626)
}

@Composable
fun BilaiTheme(amoled: Boolean, content: @Composable () -> Unit) {
    val background = if (amoled) Bilai.Amoled else Bilai.Background
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Bilai.Amber,
            onPrimary = Bilai.OnAmber,
            secondary = Bilai.Highlight,
            onSecondary = Bilai.OnAmber,
            background = background,
            onBackground = Bilai.TextPrimary,
            surface = Bilai.Surface,
            onSurface = Bilai.TextPrimary,
            surfaceVariant = Bilai.Pill,
            onSurfaceVariant = Bilai.TextSecondary,
        ),
        content = content,
    )
}
