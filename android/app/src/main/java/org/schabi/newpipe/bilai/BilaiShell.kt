package org.schabi.newpipe.bilai

import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import org.schabi.newpipe.bilai.engine.ServicePlaybackEngine
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
import androidx.compose.foundation.shape.RoundedCornerShape
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.ExtractorHelper
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
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
    private val engine = ServicePlaybackEngine(activity)
    private val homeFeed by lazy { ViewModelProvider(activity)[HomeFeedViewModel::class.java] }
    private var video by mutableStateOf<Pair<String, String>?>(null)
    private var compact by mutableStateOf(false)
    private var overlay by mutableStateOf<String?>(null)
    private var scrollHome by mutableIntStateOf(0)
    private var menuVideo by mutableStateOf<StreamInfoItem?>(null)
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("bt.action")) {
                "minimize" -> compact = true
                "expand" -> compact = false
                "open_desc" -> if (video != null) overlay = "Description"
                "open_menu" -> if (video != null) { menuVideo = null; overlay = "Video options" }
                "open_search" -> overlay = "Search"
            }
        }
    }

    private val prefs = activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE)
    private var dark by mutableStateOf(prefs.getBoolean("dark", true))
    private var red by mutableStateOf(prefs.getBoolean("red", false))
    private var tab by mutableStateOf(Tab.entries.find { it.id == prefs.getString("tab", null) } ?: Tab.HOME)
    private var toast by mutableStateOf<String?>(null, neverEqualPolicy())

    init {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (BuildConfig.DEBUG) ContextCompat.registerReceiver(activity, debugReceiver,
            IntentFilter("com.masharzy.bilaitube.DEBUG_UI"), ContextCompat.RECEIVER_EXPORTED)
        handleIntent(activity.intent)
    }

    val view = ComposeView(activity).apply { setContent { RenderShell() } }

    fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(::openUrl)
        applyDebugIntent(intent)
        if (BuildConfig.DEBUG && intent.action == "com.masharzy.bilaitube.DEBUG_PLAY") {
            intent.getStringExtra("bt.uri")?.let { play(it, "Sample video") }
        }
    }

    fun dispose() {
        if (BuildConfig.DEBUG) activity.unregisterReceiver(debugReceiver)
        engine.detach()
    }

    private fun play(url: String, title: String) {
        compact = false
        overlay = null
        video = url to title
        engine.play(url)
    }

    private fun openUrl(url: String) {
        val uri = Uri.parse(url.trim())
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme in listOf("http", "https") && (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com"))) {
            play(url.trim(), "")
        } else toast = "Valid YouTube URL দাও"
    }

    private fun applyDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra("bt.dark")) dark = intent.getBooleanExtra("bt.dark", dark)
        when (intent.getStringExtra("bt.accent")) {
            "amber" -> red = false
            "red" -> red = true
        }
        Tab.entries.find { it.id == intent.getStringExtra("bt.tab") }?.let { tab = it }
    }

    private fun select(tab: Tab) {
        if (this.tab == Tab.HOME && tab == Tab.HOME) scrollHome++
        this.tab = tab
        prefs.edit().putString("tab", tab.id).apply()
    }

    @Composable
    private fun RenderShell() {
        val list = rememberLazyListState()
        LaunchedEffect(scrollHome) { if (scrollHome > 0) list.scrollToItem(0) }
        BackHandler(enabled = overlay != null || (video != null && !compact) || tab != Tab.HOME) {
            when {
                overlay != null -> overlay = null
                video != null && !compact -> compact = true
                else -> select(Tab.HOME)
            }
        }
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
                            home = { homeFeed.select(0); scrollHome++; select(Tab.HOME) },
                            openUrl = { overlay = "Open Any YouTube URL" },
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
                        Box(Modifier.weight(1f).fillMaxWidth().debugTag("content_${tab.id}")) {
                            if (tab == Tab.HOME) HomeFeed(homeFeed, list, ::play, { select(Tab.SHORTS) }) {
                                menuVideo = it
                                overlay = "Video options"
                            }
                        }
                        BottomNav(tab, ::select)
                    }
                    video?.let { (url, title) ->
                        Watch(url, title, engine, compact, { compact = it },
                            close = { engine.stop(); video = null; toast = "Mini-player dismissed" },
                            playNext = ::play, overlay = { name ->
                                if (name == "Share") share(url) else overlay = name
                            })
                    }
                    overlay?.let { name ->
                        if (name in listOf("Description", "Comments", "Video options")) {
                            WatchSheet(name, { overlay = null })
                        } else Overlay(name, { overlay = null })
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
    private fun share(url: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        activity.startActivity(Intent.createChooser(intent, null))
    }

    @Composable
    private fun WatchSheet(name: String, dismiss: () -> Unit) {
        val p = LocalBilaiColors.current
        val info by engine.current.collectAsState()
        val isMenu = name == "Video options"
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (isMenu) .6f else .25f))
                .clickable(onClick = dismiss))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (isMenu) Modifier else Modifier.height((maxHeight - maxWidth * 9 / 16).coerceAtLeast(120.dp)))
                .background(p.sheetBg, RoundedCornerShape(topStart = if (isMenu) 24.dp else 16.dp,
                    topEnd = if (isMenu) 24.dp else 16.dp))
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    FeedText(name, 14, p.text, FontWeight.Bold)
                    WatchPill("×", p.text, p.elevated, "Close", click = dismiss)
                }
                when (name) {
                    "Description" -> Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeedText(info?.name ?: video?.second.orEmpty(), 14, p.text, FontWeight.Bold)
                        BasicText(info?.description?.content.orEmpty(),
                            style = TextStyle(color = p.text, fontSize = 12.sp, lineHeight = 18.sp))
                    }
                    "Comments" -> Comments(video?.first.orEmpty())
                    "Video options" -> {
                        val current = menuVideo
                        val url = current?.url ?: video?.first
                        if (current != null) WatchPill("Save to Watch Later", p.text, p.elevated) {
                            WatchLater.add(activity, current)
                            dismiss()
                        }
                        if (url != null) WatchPill("Share", p.text, p.elevated) { dismiss(); share(url) }
                        WatchPill("Cancel", p.text, p.elevated, click = dismiss)
                    }
                }
            }
        }
    }

    @Composable
    private fun Comments(url: String) {
        val p = LocalBilaiColors.current
        var info by remember(url) { mutableStateOf<CommentsInfo?>(null) }
        var comments by remember(url) { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
        var next by remember(url) { mutableStateOf<Page?>(null) }
        var loading by remember(url) { mutableStateOf(true) }
        var failed by remember(url) { mutableStateOf(false) }
        val request = remember(url) { SerialDisposable() }
        DisposableEffect(url) {
            request.set(ExtractorHelper.getCommentsInfo(ServiceList.YouTube.serviceId, url, false)
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ page ->
                    info = page
                    comments = page.relatedItems
                    next = page.nextPage
                    loading = false
                }, { loading = false; failed = true }))
            onDispose { request.dispose() }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            comments.forEach { comment ->
                FeedText(comment.uploaderName.orEmpty(), 11, p.muted, FontWeight.SemiBold)
                BasicText(comment.commentText.content, style = TextStyle(color = p.text, fontSize = 12.sp))
            }
            if (loading) FeedText("Loading comments…", 12, p.muted)
            else if (failed) FeedText("Couldn't load comments.", 12, p.muted)
            else if (comments.isEmpty()) FeedText("No comments", 12, p.muted)
            val initial = info
            val continuation = next
            if (initial != null && Page.isValid(continuation) && !loading) {
                WatchPill("Load more", p.text, p.elevated) {
                    loading = true
                    request.set(ExtractorHelper.getMoreCommentItems(ServiceList.YouTube.serviceId, initial, continuation)
                        .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ page ->
                            comments = comments + page.items
                            next = page.nextPage
                            loading = false
                        }, { loading = false; failed = true }))
                }
            }
        }
    }

    @Composable
    private fun Overlay(name: String, dismiss: () -> Unit) {
        val p = LocalBilaiColors.current
        val info by engine.current.collectAsState()
        var input by remember(name) { mutableStateOf("") }
        Dialog(onDismissRequest = dismiss) {
            Column(Modifier.fillMaxWidth().background(p.sheetBg, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeedText(name, 14, p.text, FontWeight.Bold)
                when (name) {
                    "Open Any YouTube URL" -> {
                        FeedText("ভিডিও লিংক এখানে পেস্ট করো", 11, p.muted)
                        BasicTextField(input, { input = it }, singleLine = true,
                            textStyle = TextStyle(color = p.text, fontSize = 12.sp),
                            modifier = Modifier.fillMaxWidth().background(p.elevated, RoundedCornerShape(12.dp)).padding(12.dp),
                            decorationBox = { field ->
                                Box {
                                    if (input.isEmpty()) FeedText("https://www.youtube.com/watch?v=dQw4w9WgXcQ", 12, p.muted)
                                    field()
                                }
                            })
                        WatchPill("Play Video", p.onAccent, p.accent) { openUrl(input) }
                    }
                }
                WatchPill("Close", p.text, p.elevated, click = dismiss)
            }
        }
    }
}

private fun Modifier.debugTag(tag: String) = if (BuildConfig.DEBUG) testTag(tag) else this

@Composable
private fun Header(dark: Boolean, home: () -> Unit, openUrl: () -> Unit, theme: () -> Unit, accent: () -> Unit, notify: () -> Unit) {
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
                HeaderButton(Glyph.LINK, "Open YouTube URL", "btn_url", p.text, openUrl)
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
