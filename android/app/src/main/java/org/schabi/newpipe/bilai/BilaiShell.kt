package org.schabi.newpipe.bilai

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import kotlin.math.roundToInt
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
    private var playbackSpeed by mutableFloatStateOf(1f)
    private var captions by mutableStateOf<String?>(null)
    private var preferredCaption: String? = null
    private var quality by mutableStateOf<Int?>(null)
    private var loopVideo by mutableStateOf(false)
    private var autoplay by mutableStateOf(true)
    private var settingsPage by mutableStateOf("Player settings")

    private fun toggleAutoplay() {
        autoplay = !autoplay
        prefs.edit().putBoolean("autoplay", autoplay).apply()
    }

    private fun chooseCaptions(language: String?) {
        captions = language
        if (language != null) preferredCaption = language
        engine.setCaptions(language)
        toast = if (language == null) "Captions off" else "Captions on · $language"
    }

    private fun toggleCaptions() {
        if (captions != null) chooseCaptions(null)
        else (preferredCaption?.takeIf { it in engine.state.value.subtitles }
            ?: engine.state.value.subtitles.firstOrNull())?.let(::chooseCaptions)
    }
    private var fullscreen by mutableStateOf(false)
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun requestFullscreen(enabled: Boolean) {
        if (enabled == fullscreen || video == null) return
        if (enabled) previousOrientation = activity.requestedOrientation
        fullscreen = enabled
        compact = false
        overlay = null
        activity.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else previousOrientation
        WindowCompat.getInsetsController(activity.window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    private var overlay by mutableStateOf<String?>(null)
    private var scrollHome by mutableIntStateOf(0)
    private var menuVideo by mutableStateOf<StreamInfoItem?>(null)
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("bt.action")) {
                "fullscreen" -> requestFullscreen(true)
                "exit_fullscreen" -> requestFullscreen(false)
                "open_comments" -> if (video != null) overlay = "Comments"
                "open_settings" -> if (video != null) overlay = "Player settings"
                "minimize" -> { requestFullscreen(false); compact = true }
                "expand" -> compact = false
                "open_desc" -> if (video != null) overlay = "Description"
                "open_menu" -> if (video != null) { menuVideo = null; overlay = "Video options" }
                "open_search" -> overlay = "Search"
            }
        }
    }

    private val prefs = activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE)
    private var debugDark by mutableStateOf<Boolean?>(null)
    private var initialShortUrl by mutableStateOf<String?>(null)
    private var hideShorts by mutableStateOf(prefs.getBoolean("hide_shorts", false))
    private var red by mutableStateOf(false)
    private var tab by mutableStateOf(Tab.entries.find { it.id == prefs.getString("tab", null) } ?: Tab.HOME)
    private var toast by mutableStateOf<String?>(null, neverEqualPolicy())

    init {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (BuildConfig.DEBUG) ContextCompat.registerReceiver(activity, debugReceiver,
            IntentFilter("com.masharzy.bilaitube.DEBUG_UI"), ContextCompat.RECEIVER_EXPORTED)
        autoplay = prefs.getBoolean("autoplay", true)
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
        quality = null
        compact = false
        overlay = null
        video = url to title
        engine.play(url)
    }

    private fun openVideoMenu(item: StreamInfoItem) {
        menuVideo = item
        overlay = "Video options"
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
        if (intent.hasExtra("bt.dark")) debugDark = intent.getBooleanExtra("bt.dark", false)
        when (intent.getStringExtra("bt.accent")) {
            "amber" -> red = false
            "red" -> red = true
        }
        Tab.entries.find { it.id == intent.getStringExtra("bt.tab") }?.let { tab = it }
    }

    private fun select(tab: Tab) {
        if (tab == Tab.SHORTS && this.tab != Tab.SHORTS && video != null) {
            engine.stop()
            video = null
            compact = false
        }
        if (this.tab == Tab.HOME && tab == Tab.HOME) scrollHome++
        this.tab = tab
        prefs.edit().putString("tab", tab.id).apply()
    }

    @Composable
    private fun RenderShell() {
        val dark = debugDark ?: isSystemInDarkTheme()
        val list = rememberLazyListState()
        LaunchedEffect(hideShorts) { homeFeed.hideShorts = hideShorts }
        LaunchedEffect(scrollHome) { if (scrollHome > 0) list.scrollToItem(0) }
        BackHandler(enabled = overlay != null || (video != null && !compact) || tab != Tab.HOME) {
            when {
                overlay != null -> overlay = null
                fullscreen -> requestFullscreen(false)
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
            Box(Modifier.fillMaxSize().background(colors.bg).then(if (fullscreen) Modifier else Modifier.systemBarsPadding()).background(colors.outerBg)
                .then(if (BuildConfig.DEBUG) Modifier.semantics { testTagsAsResourceId = true } else Modifier),
                contentAlignment = Alignment.Center) {
                Box(Modifier.then(if (fullscreen) Modifier else Modifier.widthIn(max = 430.dp).heightIn(max = 880.dp)).fillMaxSize()
                    .background(colors.bg)) {
                    if (!fullscreen) Column(Modifier.fillMaxSize()) {
                        Header(
                            home = { homeFeed.select(0); scrollHome++; select(Tab.HOME) },
                            openUrl = { overlay = "Open Any YouTube URL" },
                            notify = { toast = "No new notifications" },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth().debugTag("content_${tab.id}")) {
                            when (tab) {
                                Tab.HOME -> HomeFeed(homeFeed, list, ::play, { url ->
                                    initialShortUrl = url
                                    select(Tab.SHORTS)
                                }, ::openVideoMenu)
                                Tab.SHORTS -> ShortsTab(homeFeed, engine, initialShortUrl, { overlay = "Sign in to continue" }, ::share)
                                Tab.SUBS -> SubscriptionsTab(::play, ::openVideoMenu)
                                Tab.YOU -> YouTab(dark, ::play, { overlay = it }, hideShorts) {
                                    hideShorts = !hideShorts
                                    prefs.edit().putBoolean("hide_shorts", hideShorts).apply()
                                }
                            }
                        }
                        BottomNav(tab, ::select)
                    }
                    video?.let { (url, title) ->
                        Watch(url, title, engine, compact, { compact = it }, fullscreen, ::requestFullscreen, playbackSpeed,
                            captions != null, ::toggleCaptions, autoplay, ::toggleAutoplay,
                            close = { requestFullscreen(false); engine.stop(); video = null; toast = "Mini-player dismissed" },
                            playNext = ::play, overlay = { name ->
                                when (name) {
                                    "Share" -> share(url)
                                    "Download" -> toast = if (engine.download()) "Download started" else "Download unavailable"
                                    else -> overlay = name
                                }
                            })
                    }
                    overlay?.let { name ->
                        if (name == "Player settings") {
                            PlayerSettings { overlay = null; settingsPage = "Player settings" }
                        } else if (name in listOf("Description", "Comments", "Video options", "Sign in to continue")) {
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
        val isMenu = name == "Video options" || name == "Sign in to continue"
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (isMenu) .6f else .25f))
                .clickable(onClick = dismiss))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (isMenu) Modifier else Modifier.height((maxHeight - maxWidth * 9 / 16).coerceAtLeast(120.dp)))
                .background(p.sheetBg, RoundedCornerShape(topStart = if (isMenu) 24.dp else 16.dp,
                    topEnd = if (isMenu) 24.dp else 16.dp))
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val density = LocalDensity.current.density
                var dragDistance = 0f
                Row(Modifier.fillMaxWidth().pointerInput(name, density) {
                    detectVerticalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onDragEnd = { if (dragDistance >= 110 * density) dismiss() },
                    ) { change, delta -> dragDistance += delta; change.consume() }
                }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                    "Sign in to continue" -> {
                        FeedText("Like, subscribe and comment need your Google account", 12, p.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchPill("Sign in", p.onAccent, p.accent) { overlay = "Sign in" }
                            WatchPill("Not now", p.text, p.elevated, click = dismiss)
                        }
                    }
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
        val commentList = rememberLazyListState()
        val initial = info
        val continuation = next
        LaunchedEffect(initial, continuation, commentList, comments.size) {
            if (initial == null || !Page.isValid(continuation) || failed) return@LaunchedEffect
            snapshotFlow { commentList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
                if (index < comments.size - 4 || loading) return@collect
                loading = true
                request.set(ExtractorHelper.getMoreCommentItems(ServiceList.YouTube.serviceId, initial, continuation)
                    .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ page ->
                        comments = (comments + page.items).distinctBy { it.commentId }
                        next = page.nextPage
                        loading = false
                    }, { loading = false; failed = true }))
            }
        }
        LazyColumn(state = commentList, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { WatchPill("Add a comment…", p.text, p.elevated) { overlay = "Sign in to continue" } }
            items(comments.distinctBy { it.commentId }, key = { it.commentId }) { comment ->
                CommentItem(comment, initial)
            }
            if (loading) {
                items(2) { Box(Modifier.fillMaxWidth().height(58.dp).background(p.elevated, RoundedCornerShape(12.dp))) }
            } else if (failed) item { FeedText("Couldn't load comments.", 12, p.muted) }
            else if (comments.isEmpty()) item { FeedText("No comments", 12, p.muted) }
        }
    }

    @Composable
    private fun CommentItem(comment: CommentsInfoItem, info: CommentsInfo?, nested: Boolean = false) {
        val p = LocalBilaiColors.current
        var expanded by remember(comment.commentId) { mutableStateOf(false) }
        var fullText by remember(comment.commentId) { mutableStateOf(false) }
        var replies by remember(comment.commentId) { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
        var next by remember(comment.commentId) { mutableStateOf(comment.replies) }
        var loading by remember(comment.commentId) { mutableStateOf(false) }
        val request = remember(comment.commentId) { SerialDisposable() }
        DisposableEffect(request) { onDispose { request.dispose() } }
        fun loadReplies() {
            val page = next ?: return
            if (info == null || loading) return
            loading = true
            request.set(ExtractorHelper.getMoreCommentItems(ServiceList.YouTube.serviceId, info, page)
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ result ->
                    replies = (replies + result.items).distinctBy { it.commentId }
                    next = result.nextPage
                    loading = false
                }, { loading = false }))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NetImage(comment.uploaderAvatarUrl, Modifier.size(if (nested) 24.dp else 36.dp).clip(CircleShape), avatar = true)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (comment.isPinned) FeedText("Pinned by creator", 11, p.muted)
                FeedText(listOfNotNull(comment.uploaderName, comment.textualUploadDate).joinToString(" • "),
                    11, p.muted, FontWeight.SemiBold)
                val body = comment.commentText.content
                BasicText(body, maxLines = if (fullText) Int.MAX_VALUE else 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = TextStyle(color = p.text, fontSize = 13.sp))
                if (body.length > 120 || body.count { it == '\n' } > 2) {
                    WatchPill(if (fullText) "Show less" else "Read more", p.text, p.elevated) { fullText = !fullText }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WatchPill("Like · ${comment.likeCount.coerceAtLeast(0)}", p.text, p.elevated) {
                        overlay = "Sign in to continue"
                    }
                    WatchPill("Dislike", p.text, p.elevated) { overlay = "Sign in to continue" }
                    WatchPill("Reply", p.text, p.elevated) { overlay = "Sign in to continue" }
                    if (comment.isHeartedByUploader) FeedText("♥", 12, Color.Red)
                }
                if (!nested && comment.replyCount > 0) {
                    WatchPill("${comment.replyCount} replies", p.accent, p.accentSoft) {
                        expanded = !expanded
                        if (expanded && replies.isEmpty()) loadReplies()
                    }
                    AnimatedVisibility(expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            replies.forEach { reply -> CommentItem(reply, info, nested = true) }
                            if (loading) FeedText("Loading replies…", 12, p.muted)
                            else if (Page.isValid(next) && replies.isNotEmpty()) {
                                WatchPill("More replies", p.accent, p.accentSoft, click = ::loadReplies)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlayerSettings(dismiss: () -> Unit) {
        val p = LocalBilaiColors.current
        val state by engine.state.collectAsState()
        val page = settingsPage
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (fullscreen) .55f else .6f))
                .clickable(onClick = dismiss))
            Column(Modifier.align(if (fullscreen) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(if (fullscreen) Modifier.width(minOf(288.dp, maxWidth * .75f)).fillMaxHeight()
                    else Modifier.fillMaxWidth().heightIn(max = maxHeight * .8f))
                .background(p.sheetBg, RoundedCornerShape(16.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (page != "Player settings") WatchPill("‹", p.text, p.elevated, "Back") { settingsPage = "Player settings" }
                    FeedText(page, 14, p.text, FontWeight.Bold)
                    WatchPill("×", p.text, p.elevated, "Close", click = dismiss)
                }
                when (page) {
                    "Playback speed" -> {
                        FeedText(formatSpeed(playbackSpeed), 18, p.accent, FontWeight.Bold)
                        androidx.compose.material3.Slider(
                            value = playbackSpeed, onValueChange = ::changeSpeed,
                            valueRange = .25f..4f, steps = 74,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchPill("−", p.text, p.elevated) { changeSpeed(playbackSpeed - .05f) }
                            WatchPill("+", p.text, p.elevated) { changeSpeed(playbackSpeed + .05f) }
                        }
                        for (preset in listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)) {
                            MenuRow(if (preset == 1f) "Normal" else formatSpeed(preset),
                                if (preset == playbackSpeed) "✓" else "") { changeSpeed(preset) }
                        }
                    }
                    "Captions" -> {
                        MenuRow("Off", if (captions == null) "✓" else "") {
                            chooseCaptions(null); settingsPage = "Player settings"
                        }
                        for (track in state.subtitles) MenuRow(track, if (captions == track) "✓" else "") {
                            chooseCaptions(track); settingsPage = "Player settings"
                        }
                    }
                    "Quality" -> {
                        MenuRow("Auto", if (quality == null) "✓" else "") {
                            quality = null; engine.setQuality(null); settingsPage = "Player settings"
                        }
                        for (height in state.qualities) MenuRow("${height}p", if (quality == height) "✓" else "") {
                            quality = height; engine.setQuality(height); settingsPage = "Player settings"
                        }
                    }
                    else -> {
                        MenuRow("Autoplay", if (autoplay) "On" else "Off", ::toggleAutoplay)
                        MenuRow("Captions", captions ?: "Off") { settingsPage = "Captions" }
                        MenuRow("Quality", quality?.let { "${it}p" } ?: "Auto") { settingsPage = "Quality" }
                        MenuRow("Playback speed", formatSpeed(playbackSpeed)) { settingsPage = "Playback speed" }
                        MenuRow("Loop video", if (loopVideo) "On" else "Off") {
                            loopVideo = !loopVideo; engine.setLoop(loopVideo)
                        }
                    }
                }
            }
        }
    }

    private fun changeSpeed(value: Float) {
        playbackSpeed = (value.coerceIn(.25f, 4f) * 20).roundToInt() / 20f
        engine.setSpeed(playbackSpeed)
    }

    @Composable
    private fun MenuRow(title: String, value: String, click: () -> Unit) {
        val p = LocalBilaiColors.current
        Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            FeedText(title, 14, p.text)
            FeedText(value, 13, if (value in listOf("On", "✓")) p.accent else p.muted)
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
private fun Header(home: () -> Unit, openUrl: () -> Unit, notify: () -> Unit) {
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
