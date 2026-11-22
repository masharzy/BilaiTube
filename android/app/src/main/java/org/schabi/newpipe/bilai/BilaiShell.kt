package org.schabi.newpipe.bilai

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import org.schabi.newpipe.BuildConfig

private enum class Tab(val id: String, val label: String, val glyph: Glyph) {
    HOME("home", "Home", Glyph.HOME), SHORTS("shorts", "Shorts", Glyph.ZAP),
    SUBS("subs", "Subscriptions", Glyph.RSS), YOU("you", "You", Glyph.USER);
}

@OptIn(ExperimentalComposeUiApi::class)
class BilaiShell(private val activity: ComponentActivity) {
    private val prefs = activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE)
    private var dark by mutableStateOf(prefs.getBoolean("dark", true))
    private var red by mutableStateOf(prefs.getBoolean("red", false))
    private var tab by mutableStateOf(Tab.entries.find { it.id == prefs.getString("tab", null) } ?: Tab.HOME)
    private var toast by mutableStateOf<String?>(null, neverEqualPolicy())

    init {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        applyDebugIntent(activity.intent)
    }

    val view = ComposeView(activity).apply { setContent { RenderShell() } }

    fun applyDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra("bt.dark")) dark = intent.getBooleanExtra("bt.dark", dark)
        when (intent.getStringExtra("bt.accent")) {
            "amber" -> red = false
            "red" -> red = true
        }
        Tab.entries.find { it.id == intent.getStringExtra("bt.tab") }?.let { tab = it }
    }

    private fun select(tab: Tab) {
        this.tab = tab
        prefs.edit().putString("tab", tab.id).apply()
    }

    @Composable
    private fun RenderShell() {
        BackHandler(enabled = tab != Tab.HOME) { select(Tab.HOME) }
        BilaiTheme(dark, red) {
            val colors = LocalBilaiColors.current
            val toastText = toast
            var displayedToast by remember { mutableStateOf("") }
            LaunchedEffect(toastText) {
                if (toastText != null) {
                    displayedToast = toastText
                    delay(2200)
                    toast = null
                }
            }
            SideEffect {
                val window = activity.window
                window.statusBarColor = colors.bg.toArgb()
                window.navigationBarColor = colors.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            Box(Modifier.fillMaxSize().background(colors.bg).systemBarsPadding().background(colors.outerBg)
                .then(if (BuildConfig.DEBUG) Modifier.semantics { testTagsAsResourceId = true } else Modifier),
                contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 430.dp).fillMaxWidth().heightIn(max = 880.dp).fillMaxHeight()
                    .background(colors.bg)) {
                    Column(Modifier.fillMaxSize()) {
                        Header(
                            dark = dark,
                            home = { select(Tab.HOME) },
                            theme = {
                                dark = !dark
                                prefs.edit().putBoolean("dark", dark).apply()
                                toast = if (dark) "Dark Mode" else "Light Mode"
                            },
                            accent = {
                                red = !red
                                prefs.edit().putBoolean("red", red).apply()
                                toast = if (red) "Classic YouTube Red" else "BilaiTube Warm Amber"
                            },
                            notify = { toast = "No new notifications" },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth().debugTag("content_${tab.id}"))
                        BottomNav(tab, ::select)
                    }
                    AnimatedVisibility(
                        toastText != null,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                        enter = fadeIn(), exit = fadeOut(),
                    ) {
                        BasicText(displayedToast, Modifier.clip(CircleShape)
                            .background(Color(0xFF181716))
                            .border(1.dp, Color.White.copy(alpha = .15f), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                            style = TextStyle(color = Color.White, fontFamily = BilaiFont,
                                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp))
                    }
                }
            }
        }
    }
}

private fun Modifier.debugTag(tag: String) = if (BuildConfig.DEBUG) testTag(tag) else this

@Composable
private fun Header(dark: Boolean, home: () -> Unit, theme: () -> Unit, accent: () -> Unit, notify: () -> Unit) {
    val p = LocalBilaiColors.current
    Box(Modifier.fillMaxWidth().height(48.dp).background(p.bg)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.clickable(role = Role.Button, onClick = home),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Logo()
                BasicText("BilaiTube", maxLines = 1, style = TextStyle(color = p.text,
                    fontFamily = BilaiFont, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-.025f * 18).sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HeaderButton(if (dark) Glyph.SUN else Glyph.MOON, "Toggle Light / Dark Mode", "btn_theme", p.text, theme)
                HeaderButton(Glyph.PALETTE, "Switch accent", "btn_accent", p.accent, accent)
                HeaderButton(Glyph.LINK, "Open YouTube URL", "btn_url", p.text)
                HeaderButton(Glyph.BELL, "Notifications", "btn_notifications", p.text, notify, true)
                HeaderButton(Glyph.SEARCH, "Search", "btn_search", p.text)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(p.border))
    }
}

@Composable
private fun HeaderButton(glyph: Glyph, label: String, tag: String, color: Color,
                         action: (() -> Unit)? = null, dot: Boolean = false) {
    Box(Modifier.size(36.dp).clip(CircleShape).debugTag(tag)
        .semantics { contentDescription = label }
        .then(if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier),
        contentAlignment = Alignment.Center) {
        Icon(glyph, color, Modifier.size(19.dp))
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
            .size(8.dp).background(LocalBilaiColors.current.accent, CircleShape))
    }
}

@Composable
private fun BottomNav(active: Tab, select: (Tab) -> Unit) {
    val p = LocalBilaiColors.current
    Box(Modifier.fillMaxWidth().height(56.dp).background(p.surface)) {
        Row(Modifier.fillMaxSize().selectableGroup()) {
            for (tab in Tab.entries) {
                val color = if (tab == active) p.accent else p.muted
                Column(Modifier.weight(1f).fillMaxHeight().debugTag("tab_${tab.id}")
                    .selectable(tab == active, role = Role.Tab, onClick = { select(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Icon(tab.glyph, color, Modifier.size(20.dp))
                    BasicText(tab.label, style = TextStyle(color = color, fontFamily = BilaiFont,
                        fontSize = 10.sp, lineHeight = 15.sp,
                        fontWeight = if (tab == active) FontWeight.Bold else FontWeight.Medium))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
    }
}

private enum class Glyph(data: String) {
    SUN("M16 12a4 4 0 1 1-8 0 4 4 0 1 1 8 0 M12 2v2 M12 20v2 M4.93 4.93l1.42 1.42 M17.65 17.65l1.42 1.42 M2 12h2 M20 12h2 M4.93 19.07l1.42-1.42 M17.65 6.35l1.42-1.42"),
    MOON("M20.985 12.486A9 9 0 1 1 11.514 3.015a.5.5 0 0 1 .44.814 6.5 6.5 0 0 0 8.217 8.217.5.5 0 0 1 .814.44Z"),
    PALETTE("M12 22a10 10 0 1 1 10-10c0 1.4-1.2 2.5-2.6 2.5H17a1.5 1.5 0 0 0-1.2 2.4c.4.5.7 1.1.7 1.8C16.5 20.5 14.7 22 12 22Z M7.5 12h.1 M10 7h.1 M15 6.5h.1 M18 10h.1"),
    LINK("M9 17H7a5 5 0 0 1 0-10h2 M15 7h2a5 5 0 0 1 0 10h-2 M8 12h8"),
    BELL("M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21a2 2 0 0 0 4 0"),
    SEARCH("M19 11a8 8 0 1 1-16 0 8 8 0 1 1 16 0 M21 21l-4.35-4.35"),
    HOME("M3 10l9-7 9 7v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z M9 21v-7h6v7"),
    ZAP("M13 2 3 14h9l-1 8 10-12h-9l1-8Z"),
    RSS("M4 11a9 9 0 0 1 9 9 M4 4a16 16 0 0 1 16 16 M5 19a1 1 0 1 1-2 0 1 1 0 1 1 2 0"),
    USER("M22 12a10 10 0 1 1-20 0 10 10 0 1 1 20 0 M15 10a3 3 0 1 1-6 0 3 3 0 1 1 6 0 M6.2 19.6V18a3 3 0 0 1 3-3h5.6a3 3 0 0 1 3 3v1.6");

    val path = PathParser().parsePathString(data).toPath()
}

@Composable
private fun Icon(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 24, size.height / 24, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(glyph.path, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val cat = PathParser().parsePathString("M35,49 L37,34 Q37.5,30 41,32 L51,41 H58 L68,32 Q72,30 72,35 L74,49 Q80,53 80,61 V65 Q80,77 68,77 H40 Q28,77 28,65 V61 Q28,53 35,49 Z").toPath()
private val play = PathParser().parsePathString("M49,51 Q47,50 47,53 V67 Q47,70 50,69 L64,61 Q66,60 64,58 Z").toPath()

@Composable
private fun Logo() {
    val p = LocalBilaiColors.current
    Canvas(Modifier.size(28.dp)) {
        scale(size.width / 108, size.height / 108, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawRoundRect(p.elevated, androidx.compose.ui.geometry.Offset(4f, 4f), Size(100f, 100f), CornerRadius(24f))
            drawPath(cat, p.accent)
            drawPath(play, p.onAccent)
        }
    }
}
