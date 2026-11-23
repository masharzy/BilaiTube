package org.schabi.newpipe.bilai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.animation.togetherWith
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
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
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import kotlinx.coroutines.launch
import org.schabi.newpipe.bilai.engine.ServicePlaybackEngine
import org.schabi.newpipe.bilai.engine.YoutubeAccountActions
import org.schabi.newpipe.bilai.engine.YoutubeAccountActions.Reaction
import io.reactivex.rxjava3.disposables.CompositeDisposable
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import org.schabi.newpipe.App
import org.schabi.newpipe.bilai.engine.BilaiAccountStore
import org.schabi.newpipe.util.InfoCache
import org.schabi.newpipe.util.LoginSessions
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.youtube.LocalDomPoTokenProvider
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
    private val enginePreferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private val sponsorBlockPreference = activity.getString(R.string.sponsor_block_skipping_enabled_key)
    private var sponsorBlock by mutableStateOf(enginePreferences.getBoolean(sponsorBlockPreference, true))
    private var autoplay by mutableStateOf(true)
    private var settingsPage by mutableStateOf("Player settings")

    private fun toggleSponsorBlock() {
        sponsorBlock = !sponsorBlock
        enginePreferences.edit().putBoolean(sponsorBlockPreference, sponsorBlock).apply()
    }

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
    private var pip by mutableStateOf(false)
    private var signedIn by mutableStateOf(
        BilaiAccountStore.load(activity) != null,
    )
    private var accountEmail by mutableStateOf(activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE).getString("account_email", null))
    private var accountPhoto by mutableStateOf(activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE).getString("account_photo", null))
    private var accountHandle by mutableStateOf(activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE).getString("account_handle", null))

    /** Reads name, handle and photo of the signed-in Google account. */
    private fun loadAccountProfile() {
        if (!signedIn) return
        accountRequests.add(io.reactivex.rxjava3.core.Single.fromCallable {
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeHomeFeedExtractor.fetchAccount(
                org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization(),
                org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry()) ?: arrayOfNulls<String>(3)
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ info ->
            val name = info.getOrNull(0)
            if (name.isNullOrBlank()) return@subscribe
            accountEmail = name; accountHandle = info.getOrNull(1); accountPhoto = info.getOrNull(2)
            activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE).edit()
                .putString("account_email", name).putString("account_handle", accountHandle)
                .putString("account_photo", accountPhoto).apply()
        }, {}))
    }

    /** The user asked for audio in the background ("Play in background"); don't pause on stop. */
    private var backgroundRequested = false
    private var pipClosed = false

    /** Leaving the app with a video playing turns it into a floating mini player (picture-in-picture). */
    fun onUserLeaveHint() {
        if (video == null || pip || backgroundRequested || !engine.state.value.playing) return
        if (Build.VERSION.SDK_INT < 26 ||
            !activity.packageManager.hasSystemFeature("android.software.picture_in_picture")) return
        sheet = null
        runCatching {
            activity.enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9)).build())
        }
    }

    fun onResume() {
        backgroundRequested = false
        pipClosed = false
        // Back from "Install unknown apps": continue with the APK that's already downloaded.
        val release = update
        if (awaitingInstallPermission && release != null && BilaiUpdater.canInstall(activity)) {
            awaitingInstallPermission = false
            if (BilaiUpdater.isDownloaded(activity, release)) BilaiUpdater.install(activity, BilaiUpdater.cached(activity, release))
        }
    }

    /**
     * App went out of view. Closing the floating player stops playback completely; otherwise
     * playback pauses so nothing keeps running (and draining the battery) in the background,
     * unless background play was chosen explicitly.
     */
    private val backgroundPlay get() = PreferenceManager.getDefaultSharedPreferences(activity)
        .getBoolean(BACKGROUND_PLAY_KEY, false)

    fun onStop() {
        if (pipClosed && !pip) {
            pipClosed = false
            // Background play on: closing the floating window keeps the audio going.
            if (backgroundPlay && engine.state.value.playing) { compact = true; return }
            engine.stop()
            video = null
            compact = false
            return
        }
        if (pip || backgroundRequested || backgroundPlay) return
        if (engine.state.value.playing) engine.togglePlay()
    }

    fun setPictureInPicture(enabled: Boolean) {
        pipClosed = !enabled && pip &&
            !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        pip = enabled
        // Some Android versions stop the activity before reporting that PiP was dismissed.
        if (pipClosed && !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) onStop()
        if (BuildConfig.DEBUG) android.util.Log.i("BilaiPip", "Mode changed: $enabled")
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < 26 || video == null ||
            !activity.packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            toast = "Picture-in-picture unavailable"
            return
        }
        sheet = null
        if (!activity.enterPictureInPictureMode(PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9)).build())) {
            toast = "Picture-in-picture unavailable"
        }
    }

    private val accountRequests = CompositeDisposable()
    private val reactions = mutableStateMapOf<String, Reaction>()
    private val serverReactions = mutableStateMapOf<String, Reaction>()
    private val subscriptions = mutableStateMapOf<String, Boolean>()
    private val pendingAccountActions = mutableStateMapOf<String, Boolean>()

    private fun mutateAccount(key: String, operation: io.reactivex.rxjava3.core.Completable,
                              success: () -> Unit = {}, failure: () -> Unit = {}) {
        if (pendingAccountActions[key] == true) return
        pendingAccountActions[key] = true
        accountRequests.add(operation.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                pendingAccountActions.remove(key)
                success()
            }, { error ->
                pendingAccountActions.remove(key)
                failure()
                toast = "YouTube refused: " + (error.message ?: error.javaClass.simpleName)
            }))
    }

    private fun react(reaction: Reaction) {
        if (!signedIn) { accountAction(); return }
        val id = engine.current.value?.id ?: return
        if (pendingAccountActions[id] == true) return
        val previous = reactions[id] ?: Reaction.NONE
        val next = if (previous == reaction) Reaction.NONE else reaction
        reactions[id] = next
        mutateAccount(id, YoutubeAccountActions.react(id, next), failure = { reactions[id] = previous })
    }

    private fun subscribeTo(url: String) {
        if (!signedIn) { accountAction(); return }
        if (pendingAccountActions[url] == true) return
        val previous = subscriptions[url] == true
        subscriptions[url] = !previous
        mutateAccount(url, YoutubeAccountActions.subscribe(url, !previous),
            failure = { subscriptions[url] = previous })
    }

    private fun accountAction() {
        if (signedIn) toast = "This action is unavailable"
        else overlay = "Sign in to continue"
    }

    private fun signOut() {
        fixtureSignedIn = false
        BilaiAccountStore.clear(activity)
        prefs.edit().remove("account_email").remove("account_photo").remove("account_handle").apply()
        accountPhoto = null; accountHandle = null
        localReplies.clear()
        accountRequests.clear()
        pendingAccountActions.clear()
        reactions.clear()
        subscriptions.clear()
        LoginSessions.onLogout(activity, LoginSessions.youtube(activity))
        refreshAccount()
    }

    private fun refreshAccount() {
        signedIn = BilaiAccountStore.load(activity) != null
        accountEmail = activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE).getString("account_email", null)
        App.reconcileYoutubePlayerClient(activity)
        ServiceHelper.initService(
            activity, ServiceList.YouTube.serviceId,
        )
        LocalDomPoTokenProvider.invalidate()
        InfoCache.getInstance().clearCache()
        homeFeed.restart()
        loadAccountProfile()
        sheet = null
        pages.clear()
        select(Tab.YOU)
        toast = if (signedIn) "Google account connected" else "Signed out"
    }

    private var fullscreen by mutableStateOf(false)
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun requestFullscreen(enabled: Boolean) {
        if (enabled == fullscreen || video == null) return
        if (enabled) previousOrientation = activity.requestedOrientation
        fullscreen = enabled
        compact = false
        sheet = null
        activity.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else previousOrientation
        WindowCompat.getInsetsController(activity.window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    /** Sheets float above everything (including the player); pages sit under the mini player. */
    private var sheet by mutableStateOf<String?>(null)
    private val pages = androidx.compose.runtime.mutableStateListOf<String>()
    private val pageNames = setOf("Sign in", "Settings", "Search", "Channel", "Remote playlist", "History",
        "Watch Later", "Playlists", "Offline Downloads")
    private fun isPage(name: String) = name.substringBefore(PAGE_SEP) in pageNames || name.startsWith("Account:")
    private var overlay: String?
        get() = sheet ?: pages.lastOrNull()
        set(value) {
            when {
                value == null -> if (sheet != null) sheet = null else pages.removeLastOrNull()
                isPage(value) -> {
                    sheet = null
                    // Opening a page from the watch screen shrinks the video into the mini player.
                    if (video != null && !compact) { requestFullscreen(false); compact = true }
                    if (pages.lastOrNull() != value) { pages.remove(value); pages.add(value) }
                }
                else -> sheet = value
            }
        }
    private var scrollHome by mutableIntStateOf(0)
    private var savedVideo: Triple<String, String, Long>? = null
    private var resumePaused by mutableStateOf(false)
    private val localReplies = mutableStateMapOf<Pair<String, String>, List<String>>()
    private var menuVideo by mutableStateOf<StreamInfoItem?>(null)
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("bt.action")) {
                "fullscreen" -> requestFullscreen(true)
                "exit_fullscreen" -> requestFullscreen(false)
                "open_history" -> overlay = "History"
                "open_watch_later" -> overlay = "Watch Later"
                "open_playlists" -> overlay = "Playlists"
                "open_downloads" -> overlay = "Offline Downloads"
                "open_comments" -> if (video != null) overlay = "Comments"
                "open_settings", "open_captions", "open_speed" -> if (video != null) {
                    settingsPage = when (intent.getStringExtra("bt.action")) {
                        "open_captions" -> "Captions"
                        "open_speed" -> "Playback speed"
                        else -> "Player settings"
                    }
                    overlay = "Player settings"
                }
                "fixture_pause" -> if (engine.state.value.playing) engine.togglePlay()
                "fixture_end" -> {
                    engine.seekTo((engine.state.value.durationMs - 150).coerceAtLeast(0))
                    if (!engine.state.value.playing) engine.togglePlay()
                }
                "minimize" -> { requestFullscreen(false); compact = true }
                "expand" -> compact = false
                "open_desc" -> if (video != null) overlay = "Description"
                "open_menu" -> if (video != null) { menuVideo = null; overlay = "Video options" }
                "enter_pip" -> enterPictureInPicture()
                "open_app_settings" -> overlay = "Settings"
                "open_account" -> overlay = "Sign in"
                "open_search" -> overlay = "Search"
            }
        }
    }

    private val prefs = activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE)
    private var debugDark by mutableStateOf<Boolean?>(null)
    private var debugShorts by mutableStateOf<List<StreamInfoItem>?>(null)
    private var shortsComments by mutableStateOf<String?>(null)
    private var initialShortUrl by mutableStateOf<String?>(null)
    private var hideShorts by mutableStateOf(prefs.getBoolean("hide_shorts", false))
    private var red by mutableStateOf(prefs.getBoolean("red", false))
    private var userDark by mutableStateOf(prefs.getBoolean("dark", true))
    private var fixtureSignedIn by mutableStateOf(false)
    private var fixtureSubs by mutableStateOf(false)
    private var fixtureYou by mutableStateOf(false)
    private fun changeDark() {
        val next = !(debugDark ?: userDark)
        userDark = next
        debugDark = null
        prefs.edit().putBoolean("dark", next).apply()
        toast = if (next) "Dark Mode" else "Light Mode"
    }
    private fun changeAccent() {
        red = !red
        prefs.edit().putBoolean("red", red).apply()
        toast = if (red) "Classic YouTube Red" else "BilaiTube Warm Amber"
    }
    private var tab by mutableStateOf(Tab.entries.find { it.id == prefs.getString("tab", null) } ?: Tab.HOME)
    private var toast by mutableStateOf<String?>(null, neverEqualPolicy())

    init {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (BuildConfig.DEBUG) ContextCompat.registerReceiver(activity, debugReceiver,
            IntentFilter("com.masharzy.bilaitube.DEBUG_UI"), ContextCompat.RECEIVER_EXPORTED)
        autoplay = prefs.getBoolean("autoplay", true)
        // Return YouTube Dislike is on by default in BilaiTube (the inherited default was off).
        PreferenceManager.getDefaultSharedPreferences(activity).let { defaults ->
            if (!defaults.getBoolean("bilai_ryd_default_applied", false)) {
                defaults.edit().putBoolean(activity.getString(R.string.show_dislike_key), true)
                    .putBoolean("bilai_ryd_default_applied", true).apply()
                ServiceHelper.initServices(activity)
            }
        }
        handleIntent(activity.intent)
        loadAccountProfile()
    }

    val view = ComposeView(activity).apply { setContent { RenderShell() } }

    fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(::openUrl)
        applyDebugIntent(intent)
        if (BuildConfig.DEBUG && intent.action == "com.masharzy.bilaitube.DEBUG_PLAY") {
            intent.getStringExtra("bt.uri")?.let {
                if (intent.getBooleanExtra("bt.offline", false)) {
                    val fixture = java.io.File(activity.filesDir.parentFile, "sample.mp4")
                    openOffline(Uri.fromFile(fixture), "Offline sample")
                } else play(it, "Sample video")
            }
        }
    }

    fun dispose() {
        accountRequests.dispose()
        if (BuildConfig.DEBUG) activity.unregisterReceiver(debugReceiver)
        engine.detach()
    }

    private fun play(url: String, title: String) {
        savedVideo = null
        if (tab == Tab.SHORTS) { pages.clear(); select(Tab.HOME) }
        quality = null
        compact = false
        sheet = null
        video = url to title
        engine.play(url)
    }

    private fun openVideoMenu(item: StreamInfoItem) {
        menuVideo = item
        overlay = "Video options"
    }

    private fun openChannel(url: String, title: String) {
        overlay = "Channel$PAGE_SEP$url$PAGE_SEP$title"
    }

    private fun openPlaylist(url: String, title: String) {
        overlay = "Remote playlist$PAGE_SEP$url$PAGE_SEP$title"
    }

    private fun openOffline(uri: Uri, title: String) {
        savedVideo = null
        compact = false
        sheet = null
        video = uri.toString() to title
        engine.playOffline(uri)
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
        if (intent.getBooleanExtra("bt.account_signed_fixture", false)) {
            fixtureSignedIn = true
            tab = Tab.YOU
        }
        if (intent.getBooleanExtra("bt.home_fixture", false)) homeFeed.useReviewFixture()
        if (intent.getBooleanExtra("bt.subs_fixture", false)) fixtureSubs = true
        if (intent.getBooleanExtra("bt.you_fixture", false)) fixtureYou = true
        if (intent.getBooleanExtra("bt.shorts_fixture", false)) {
            val uri = Uri.fromFile(java.io.File(activity.filesDir.parentFile, "sample.mp4")).toString()
            debugShorts = WatchReviewFixture.shorts(uri)
        }
        if (intent.hasExtra("bt.dark")) debugDark = intent.getBooleanExtra("bt.dark", false)
        when (intent.getStringExtra("bt.accent")) {
            "amber" -> red = false
            "red" -> red = true
        }
        Tab.entries.find { it.id == intent.getStringExtra("bt.tab") }?.let { tab = it }
    }

    private fun select(tab: Tab) {
        pages.clear(); sheet = null
        if (this.tab == Tab.SHORTS && tab != Tab.SHORTS) {
            engine.stop()
            initialShortUrl = null
            // Bring back the video that was floating before Shorts, paused, as a mini player.
            savedVideo?.let { (url, title, position) ->
                savedVideo = null
                video = url to title
                compact = true
                resumePaused = true
                if (url.startsWith("http")) engine.play(url, position) else engine.playOffline(Uri.parse(url))
            }
        }
        if (tab == Tab.SHORTS && this.tab != Tab.SHORTS && video != null) {
            savedVideo = video?.let { (url, title) -> Triple(url, title, engine.state.value.positionMs) }
            requestFullscreen(false)
            engine.stop()
            video = null
            compact = false
        }
        if (this.tab == Tab.HOME && tab == Tab.HOME) scrollHome++
        if (this.tab != Tab.HOME && tab == Tab.HOME) homeFeed.refreshIfStale()
        this.tab = tab
        prefs.edit().putString("tab", tab.id).apply()
    }

    @Composable
    private fun RenderShell() {
        val dark = debugDark ?: userDark
        val videoOnly = fullscreen || pip
        val list = rememberLazyListState()
        LaunchedEffect(hideShorts) { homeFeed.hideShorts = hideShorts }
        LaunchedEffect(scrollHome) { if (scrollHome > 0) list.scrollToItem(0) }
        // When Home stops scrolling, pre-extract the videos on screen so a tap plays right away.
        LaunchedEffect(list) {
            snapshotFlow {
                if (list.isScrollInProgress) emptyList() else list.layoutInfo.visibleItemsInfo
                    .mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("v-") }?.removePrefix("v-") }.take(2)
            }.collectLatest { urls ->
                if (urls.isEmpty()) return@collectLatest
                kotlinx.coroutines.delay(400)
                urls.forEach(engine::prefetch)
            }
        }
        BackHandler(enabled = overlay != null || (video != null && !compact) || tab != Tab.HOME) {
            when {
                sheet != null -> sheet = null
                fullscreen -> requestFullscreen(false)
                video != null && !compact -> compact = true
                pages.isNotEmpty() -> pages.removeLastOrNull()
                else -> select(Tab.HOME)
            }
        }
        // A video restored after Shorts comes back paused at the spot it was left.
        val restoring by engine.state.collectAsState()
        LaunchedEffect(restoring.playing, resumePaused) {
            if (resumePaused && restoring.playing) { engine.togglePlay(); resumePaused = false }
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
                window.statusBarColor = if (tab == Tab.SHORTS && video == null) android.graphics.Color.BLACK else colors.bg.toArgb()
                window.navigationBarColor = if (tab == Tab.SHORTS) android.graphics.Color.BLACK else colors.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark && !(tab == Tab.SHORTS && video == null)
                    isAppearanceLightNavigationBars = !dark && tab != Tab.SHORTS
                }
            }
            Box(Modifier.fillMaxSize().background(colors.bg).then(if (videoOnly) Modifier else Modifier.systemBarsPadding()).background(colors.outerBg)
                .then(if (BuildConfig.DEBUG) Modifier.semantics { testTagsAsResourceId = true } else Modifier),
                contentAlignment = Alignment.Center) {
                Box(Modifier.then(if (videoOnly) Modifier else Modifier.widthIn(max = 430.dp).heightIn(max = 880.dp)).fillMaxSize()
                    .background(colors.bg)) {
                    if (!videoOnly) Column(Modifier.fillMaxSize()) {
                        if (tab != Tab.SHORTS) Header(
                            home = { homeFeed.select(0); scrollHome++; select(Tab.HOME) },
                            openUrl = { overlay = "Open Any YouTube URL" },
                            search = { overlay = "Search" },
                            notify = { toast = "No new notifications" },
                            dark = dark, toggleDark = ::changeDark, toggleAccent = ::changeAccent,
                        )
                        androidx.compose.animation.AnimatedContent(tab, Modifier.weight(1f).fillMaxWidth().debugTag("content_${tab.id}"),
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                (androidx.compose.animation.slideInHorizontally(tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) {
                                    if (forward) it / 5 else -it / 5 } + fadeIn(tween(220))) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(tween(260)) { if (forward) -it / 8 else it / 8 } +
                                        fadeOut(tween(160)))
                            }, label = "tabs") { tab ->
                            when (tab) {
                                Tab.HOME -> HomeFeed(homeFeed, list, ::play, { url ->
                                    initialShortUrl = url
                                    select(Tab.SHORTS)
                                }, ::openVideoMenu)
                                Tab.SHORTS -> ShortsTab(homeFeed, engine, initialShortUrl, debugShorts,
                                    { react(Reaction.LIKE) }, { react(Reaction.DISLIKE) },
                                    { shortsComments = it; overlay = "Comments" }, ::subscribeTo, ::share,
                                    search = { overlay = "Search" },
                                    reactionOf = { id -> reactions[id]?.name ?: "NONE" },
                                    download = { overlay = "Download picker" })
                                Tab.SUBS -> SubscriptionsTab(::play, ::openVideoMenu, fixture = BuildConfig.DEBUG && fixtureSubs,
                                    signedIn = signedIn, signIn = { overlay = "Sign in" },
                                    explore = { overlay = "Search" }, channelPage = ::openChannel,
                                    openShort = { url -> initialShortUrl = url; select(Tab.SHORTS) })
                                Tab.YOU -> YouTab(dark, red, BuildConfig.DEBUG && fixtureYou,
                                    signedIn || (BuildConfig.DEBUG && fixtureSignedIn),
                                    if (BuildConfig.DEBUG && fixtureSignedIn) "devforge@example.com" else accountEmail,
                                    ::changeDark, ::changeAccent, ::signOut, ::play, { overlay = it },
                                    sponsorBlock, ::toggleSponsorBlock, hideShorts, accountPhoto, accountHandle) {
                                    hideShorts = !hideShorts
                                    prefs.edit().putBoolean("hide_shorts", hideShorts).apply()
                                }
                            }
                        }
                        if (tab == Tab.SHORTS) BilaiTheme(true, red) { BottomNav(tab, ::select) }
                        else BottomNav(tab, ::select)
                    }
                    pages.lastOrNull()?.let { entry ->
                        val parts = entry.split(PAGE_SEP)
                        val name = parts[0]
                        androidx.compose.runtime.key(entry) {
                        if (name == "Sign in") {
                            AccountPage({ overlay = null }, ::refreshAccount)
                        } else if (name == "Settings") {
                            BrowseScreen("Settings", { overlay = null }) {
                                Column(Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FeedText(if (dark) "Appearance · Device dark" else "Appearance · Device light", 14, colors.text)
                                    WatchPill(if (autoplay) "Autoplay on" else "Autoplay off", colors.text, colors.elevated, click = ::toggleAutoplay)
                                    WatchPill(if (hideShorts) "Show Shorts" else "Hide Shorts", colors.text, colors.elevated) {
                                        hideShorts = !hideShorts
                                        prefs.edit().putBoolean("hide_shorts", hideShorts).apply()
                                    }
                                    WatchPill(if (signedIn) "Sign out" else "Sign in", colors.onAccent, colors.accent) {
                                        if (signedIn) signOut() else overlay = "Sign in"
                                    }
                                }
                            }
                        } else if (name == "Search") {
                            SearchPage({ overlay = null }, ::play, ::openChannel, ::openPlaylist) { url ->
                                initialShortUrl = url; select(Tab.SHORTS)
                            }
                        } else if (name == "Channel" && parts.size >= 3) {
                            val url = parts[1]
                            ChannelPage(url, parts[2], { overlay = null }, ::play, ::openPlaylist,
                                subscribed = subscriptions[url] == true, onSubscribe = { subscribeTo(url) })
                        } else if (name == "Remote playlist" && parts.size >= 3) {
                            RemotePlaylistPage(parts[1], parts[2], { overlay = null }, ::play)
                        } else if (name.startsWith("Account:")) {
                            val account = name.split(":", limit = 3)
                            AccountPlaylistPage(account[1], account.getOrElse(2) { "Playlist" }, { overlay = null }, ::play)
                        } else if (name == "History" && signedIn) {
                            AccountPlaylistPage("FEhistory", "History", { overlay = null }, ::play)
                        } else if (name == "Watch Later" && signedIn) {
                            AccountPlaylistPage("WL", "Watch Later", { overlay = null }, ::play)
                        } else if (name == "Playlists" && signedIn) {
                            AccountPlaylistsPage({ overlay = null }, { id, title -> overlay = "Account:$id:$title" }, ::play)
                        } else if (name in listOf("History", "Watch Later", "Playlists")) {
                            LibraryPage(name, { overlay = null }, ::play)
                        } else if (name == "Offline Downloads") {
                            OfflineDownloads({ overlay = null }, ::openOffline)
                        }
                        }
                    }
                    video?.let { (url, title) ->
                        val metadata by engine.current.collectAsState()
                        // Show what the account already did: liked / disliked / subscribed.
                        LaunchedEffect(metadata?.id, signedIn) {
                            val id = metadata?.id ?: return@LaunchedEffect
                            val channel = metadata?.uploaderUrl
                            if (!signedIn || pendingAccountActions[id] == true) return@LaunchedEffect
                            val known = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { YoutubeAccountActions.state(id) }.getOrNull()
                            } ?: return@LaunchedEffect
                            known.reaction?.let { serverReactions[id] = it }
                            if (pendingAccountActions[id] != true) known.reaction?.let { reactions[id] = it }
                            if (channel != null && pendingAccountActions[channel] != true) known.subscribed?.let { subscriptions[channel] = it }
                        }
                        val reaction = reactions[metadata?.id] ?: Reaction.NONE
                        val original = serverReactions[metadata?.id] ?: Reaction.NONE
                        fun delta(r: Reaction) = (if (reaction == r) 1L else 0L) - (if (original == r) 1L else 0L)
                        Watch(url, title, engine, compact, { compact = it }, videoOnly, ::requestFullscreen, playbackSpeed, pip,
                            captions != null, ::toggleCaptions, autoplay, ::toggleAutoplay,
                            liked = reaction == Reaction.LIKE, disliked = reaction == Reaction.DISLIKE,
                            likeDelta = delta(Reaction.LIKE), dislikeDelta = delta(Reaction.DISLIKE),
                            subscribed = subscriptions[metadata?.uploaderUrl] == true,
                            close = { requestFullscreen(false); engine.stop(); video = null; toast = "Mini-player dismissed" },
                            playNext = ::play,
                            autoplayNext = { nextUrl, nextTitle ->
                                val wasCompact = compact
                                play(nextUrl, nextTitle)
                                compact = wasCompact
                            }, overlay = { name ->
                                when (name) {
                                    "Channel" -> engine.current.value?.let { info ->
                                        info.uploaderUrl?.takeIf { it.isNotBlank() }?.let {
                                            openChannel(it, info.uploaderName.orEmpty())
                                        }
                                    }
                                    "Like" -> react(Reaction.LIKE)
                                    "Dislike" -> react(Reaction.DISLIKE)
                                    "Subscribe" -> metadata?.uploaderUrl?.let(::subscribeTo)
                                    "Sign in to continue" -> accountAction()
                                    "Share" -> share(url)
                                    "Download" -> overlay = "Download picker"
                                    else -> overlay = name
                                }
                            })
                    }
                    sheet?.let { name ->
                        if (name == "Check updates") {
                            LaunchedEffect(Unit) { sheet = null; manualUpdateCheck++ }
                        } else if (name == "Download picker") {
                            val current by engine.current.collectAsState()
                            val options = remember(current) {
                                runCatching { engine.downloadOptions() }.getOrDefault(emptyList())
                            }
                            DownloadPickerSheet(current, options, { sheet = null }) { option ->
                                toast = if (engine.download(option)) "Downloading ${option.label} · saved to Movies/BilaiTube"
                                    else "Download failed: " + (engine.lastDownloadError ?: "unavailable")
                            }
                        } else if (name == "Player settings") {
                            PlayerSettings { sheet = null; settingsPage = "Player settings" }
                        } else if (name in listOf("Description", "Comments", "Video options", "Sign in to continue")) {
                            WatchSheet(name, { sheet = null })
                        } else Overlay(name, { sheet = null })
                    }
                    UpdateChecker()
                    AnimatedVisibility(
                        toastText != null && !pip,
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
    private var update by mutableStateOf<BilaiUpdater.Release?>(null)
    private var updateProgress by mutableStateOf<Float?>(null)
    private var manualUpdateCheck by mutableIntStateOf(0)
    private var autoChecked = false
    private var awaitingInstallPermission by mutableStateOf(false)

    @Composable
    private fun UpdateChecker() {
        LaunchedEffect(manualUpdateCheck) {
            val manual = manualUpdateCheck > 0
            // Every launch checks, so nobody misses an update; "Later" only hides it until next time.
            if (!manual && (BuildConfig.DEBUG || autoChecked)) return@LaunchedEffect
            autoChecked = true
            val found = BilaiUpdater.check()
            if (found != null) update = found
            else if (manual) toast = "You're on the latest version (${BuildConfig.VERSION_NAME})"
        }
        val release = update ?: return
        val p = LocalBilaiColors.current
        val scope = rememberCoroutineScope()
        AnimatedSheet({ if (updateProgress == null) update = null }) { sheet ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                        MIcon(Icons.Rounded.SystemUpdate, p.accent, 24.dp)
                    }
                    Column {
                        BText("Update available", 17f, p.text, FontWeight.Bold)
                        BText("BilaiTube ${release.version} · you have ${BuildConfig.VERSION_NAME}" +
                            (if (release.size > 0) " · " + formatBytes(release.size) else ""), 12f, p.muted)
                    }
                }
                if (release.notes.isNotBlank()) Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp)).background(p.elevated).verticalScroll(rememberScrollState())
                    .padding(12.dp)) {
                    BText(release.notes, 13f, p.text, maxLines = 40, lineHeight = 19f)
                }
                val progress = updateProgress
                if (progress != null) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(p.elevated)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(p.accent))
                    }
                    BText("Downloading… ${(progress * 100).toInt()}%", 12f, p.muted)
                } else Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).clip(CircleShape).background(p.elevated).clickable {
                        sheet.close()
                    }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        BText("Later", 14f, p.text, FontWeight.SemiBold)
                    }
                    Box(Modifier.weight(1f).clip(CircleShape).background(p.accent).clickable {
                        if (!BilaiUpdater.isDownloaded(activity, release)) updateProgress = 0f
                        scope.launch {
                            val file = BilaiUpdater.download(activity, release) { updateProgress = it }
                            updateProgress = null
                            if (file == null) toast = "Update download failed"
                            else if (!BilaiUpdater.install(activity, file)) awaitingInstallPermission = true
                        }
                    }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        BText(if (BilaiUpdater.isDownloaded(activity, release)) "Install" else "Update now",
                            14f, p.onAccent, FontWeight.Bold)
                    }
                }
                if (awaitingInstallPermission) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(p.elevated).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BText("Android asks once to allow updates from BilaiTube. Turn on \"Allow from this source\" " +
                        "and come back — the install starts by itself, without downloading again.", 12f, p.text,
                        maxLines = 5, lineHeight = 17f)
                    BText("Don't want to allow it? Download from GitHub in your browser instead", 12f, p.accent,
                        FontWeight.SemiBold, maxLines = 2,
                        modifier = Modifier.clickable { BilaiUpdater.openReleasePage(activity) })
                }
                Spacer(Modifier.navigationBarsPadding())
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
            val panelHeight = if (isMenu) null else (maxHeight - maxWidth * 9 / 16).coerceAtLeast(240.dp)
            AnimatedSheet(dismiss, scrim = if (isMenu) .6f else .3f, topRadius = if (isMenu) 24.dp else 16.dp,
                fixedHeight = panelHeight) { sheet ->
                when (name) {
                    "Description" -> {
                        SheetTitle("Description", close = sheet.close)
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            BText(info?.name ?: video?.second.orEmpty(), 17f, p.text, FontWeight.Bold, maxLines = 4)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.elevated)
                                .padding(vertical = 12.dp)) {
                                StatCell(watchCount(info?.likeCount ?: -1).ifBlank { "—" }, "Likes", Modifier.weight(1f))
                                StatCell(watchCount(info?.viewCount ?: -1).ifBlank { "—" }, "Views", Modifier.weight(1f))
                                StatCell(info?.uploadDate?.offsetDateTime()?.toLocalDate()?.let {
                                    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH).format(it)
                                } ?: "—", "Published", Modifier.weight(1f))
                            }
                            val raw = info?.description?.content.orEmpty()
                            BasicText(remember(raw, p.accent) {
                                AnnotatedString.fromHtml(
                                    if (raw.contains('<')) raw else raw.replace("\n", "<br>"),
                                    linkStyles = androidx.compose.ui.text.TextLinkStyles(
                                        androidx.compose.ui.text.SpanStyle(color = p.accent)))
                            }, style = TextStyle(color = p.text, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = BilaiFont))
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    "Comments" -> {
                        SheetTitle("Comments", close = sheet.close)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Comments((if (tab == Tab.SHORTS) shortsComments else video?.first).orEmpty())
                        }
                    }
                    "Sign in to continue" -> {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(56.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                                MIcon(Icons.Rounded.AccountCircle, p.accent, 32.dp)
                            }
                            BText("Sign in to continue", 17f, p.text, FontWeight.Bold)
                            BText("Like, subscribe and comment need your Google account.", 13f, p.muted, maxLines = 2)
                            Box(Modifier.fillMaxWidth().padding(top = 6.dp).clip(CircleShape).background(p.accent)
                                .clickable { overlay = "Sign in" }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                BText("Sign in", 14f, p.onAccent, FontWeight.Bold)
                            }
                            Box(Modifier.fillMaxWidth().clip(CircleShape).clickable(onClick = sheet.close)
                                .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                BText("Not now", 14f, p.text, FontWeight.SemiBold)
                            }
                            Spacer(Modifier.navigationBarsPadding())
                        }
                    }
                    "Video options" -> {
                        val current = menuVideo
                        val url = current?.url ?: video?.first
                        if (current != null) {
                            SheetRow(Icons.Rounded.WatchLater, "Save to Watch Later") {
                                WatchLater.add(activity, current); toast = "Saved to Watch Later"; sheet.close()
                            }
                            SheetRow(Icons.Rounded.PlayArrow, "Play") { sheet.close(); play(current.url, current.name) }
                            if (!current.uploaderUrl.isNullOrBlank()) SheetRow(Icons.Rounded.AccountCircle, "Go to channel") {
                                sheet.close(); openChannel(current.uploaderUrl, current.uploaderName.orEmpty())
                            }
                        }
                        if (current == null && video != null) {
                            SheetRow(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture") { enterPictureInPicture() }
                            SheetRow(Icons.Rounded.Headphones, "Play in background") {
                                sheet.close(); backgroundRequested = true; activity.moveTaskToBack(true)
                            }
                            SheetRow(Icons.Rounded.Download, "Download") { overlay = "Download picker" }
                        }
                        if (url != null) SheetRow(Icons.Rounded.Share, "Share") { sheet.close(); share(url) }
                        Spacer(Modifier.height(8.dp).navigationBarsPadding())
                    }
                }
            }
        }
    }

    @Composable
    private fun StatCell(value: String, label: String, modifier: Modifier) {
        val p = LocalBilaiColors.current
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            BText(value, 15f, p.text, FontWeight.Bold)
            BText(label, 11f, p.muted)
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
        val fixture = BuildConfig.DEBUG && engine.current.value?.id == "fixture0000"
        val request = remember(url) { SerialDisposable() }
        var reload by remember(url) { mutableIntStateOf(0) }
        LaunchedEffect(url, reload) {
            if (fixture) {
                comments = WatchReviewFixture.comments
                loading = false
                return@LaunchedEffect
            }
            loading = true
            failed = false
            request.set(ExtractorHelper.getCommentsInfo(ServiceList.YouTube.serviceId, url, reload > 0)
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ page ->
                    info = page
                    comments = page.relatedItems
                    next = page.nextPage
                    loading = false
                }, { loading = false; failed = true }))
        }
        DisposableEffect(request) { onDispose { request.dispose() } }
        var replyTo by remember(url) { mutableStateOf<String?>(null) }
        var composeComment by remember(url) { mutableStateOf(false) }
        var draft by remember(url) { mutableStateOf("") }
        var newest by remember(url) { mutableStateOf(false) }
        val sorted = remember(comments, newest) {
            val pinned = compareByDescending<CommentsInfoItem> { it.isPinned }
            comments.distinctBy { it.commentId }.sortedWith(
                if (newest) pinned.thenByDescending { it.uploadDate?.offsetDateTime() }
                else pinned.thenByDescending { it.likeCount },
            )
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
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchPill("Top", if (newest) p.text else p.onAccent, if (newest) p.elevated else p.accent) { newest = false }
                    WatchPill("Newest", if (newest) p.onAccent else p.text, if (newest) p.accent else p.elevated) { newest = true }
                }
            }
            if (COMMENT_POSTING) item {
                if (!composeComment) WatchPill("Add a comment…", p.text, p.elevated) {
                    if (signedIn) composeComment = true else accountAction()
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth().padding(8.dp),
                        textStyle = TextStyle(color = p.text, fontSize = 14.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchPill("Cancel", p.text, p.elevated) { composeComment = false }
                        WatchPill("Post", p.onAccent, p.accent, enabled = draft.isNotBlank()) {
                            val id = engine.current.value?.id ?: return@WatchPill
                            mutateAccount("comment:$id", YoutubeAccountActions.comment(id, draft.trim()),
                                success = { draft = ""; composeComment = false; reload++; toast = "Comment posted" })
                        }
                    }
                }
            }
            items(sorted, key = { it.commentId }) { comment ->
                val root = url to comment.commentId
                CommentItem(comment, initial, replyTo = replyTo,
                    onReply = { target -> if (signedIn || fixture) replyTo = target else overlay = "Sign in to continue" },
                    local = localReplies[root].orEmpty(),
                    submit = { text ->
                        localReplies[root] = localReplies[root].orEmpty() + text
                        replyTo = null
                    })
            }
            if (loading) {
                items(2) { Box(Modifier.fillMaxWidth().height(58.dp).background(p.elevated, RoundedCornerShape(12.dp))) }
            } else if (failed) item { FeedText("Couldn't load comments.", 12, p.muted) }
            else if (comments.isEmpty()) item { FeedText("No comments", 12, p.muted) }
        }
    }

    @Composable
    private fun CommentItem(comment: CommentsInfoItem, info: CommentsInfo?, nested: Boolean = false,
                            replyTo: String?, onReply: (String?) -> Unit, local: List<String> = emptyList(),
                            submit: (String) -> Unit) {
        val p = LocalBilaiColors.current
        var expanded by remember(comment.commentId) { mutableStateOf(false) }
        LaunchedEffect(local.size) { if (local.isNotEmpty()) expanded = true }
        var fullText by remember(comment.commentId) { mutableStateOf(false) }
        var replies by remember(comment.commentId) { mutableStateOf<List<CommentsInfoItem>>(emptyList()) }
        var next by remember(comment.commentId) { mutableStateOf(comment.replies) }
        var loading by remember(comment.commentId) { mutableStateOf(false) }
        val request = remember(comment.commentId) { SerialDisposable() }
        DisposableEffect(request) { onDispose { request.dispose() } }
        fun loadReplies() {
            if (BuildConfig.DEBUG && engine.current.value?.id == "fixture0000") {
                replies = WatchReviewFixture.replies(comment.commentId)
                return
            }
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
                BText(listOfNotNull(comment.uploaderName, watchAge(comment.uploadDate)).joinToString(" • "),
                    12f, p.muted)
                val body = comment.commentText.content
                val html = remember(body, p.accent) {
                    AnnotatedString.fromHtml(body.replace("\n", "<br>"),
                        linkStyles = androidx.compose.ui.text.TextLinkStyles(
                            androidx.compose.ui.text.SpanStyle(color = p.accent)))
                }
                BasicText(html, maxLines = if (fullText) Int.MAX_VALUE else 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = TextStyle(color = p.text, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = BilaiFont))
                if (body.length > 120 || body.count { it == '\n' } > 2) {
                    BText(if (fullText) "Show less" else "Read more", 12f, p.muted, FontWeight.SemiBold,
                        modifier = Modifier.clickable { fullText = !fullText })
                }
                // Comment likes and replies can't be sent to YouTube from here, so only the counts are shown.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MIcon(Icons.Rounded.ThumbUpOffAlt, p.muted, 16.dp)
                    if (comment.likeCount > 0) BText(watchCount(comment.likeCount.toLong()), 12f, p.muted)
                    if (comment.isHeartedByUploader) {
                        Spacer(Modifier.width(8.dp))
                        MIcon(Icons.Rounded.Favorite, Color(0xFFEF4444), 14.dp, "Hearted by creator")
                    }
                }
                LocalReplyComposer(replyTo != null && replyTo == comment.commentId, comment.uploaderName.orEmpty(), nested,
                    cancel = { onReply(null) }, submit = submit)
                val count = comment.replyCount.coerceAtLeast(0) + local.size
                if (!nested && count > 0) {
                    Row(Modifier.clip(CircleShape).clickable {
                        expanded = !expanded
                        if (expanded && replies.isEmpty()) loadReplies()
                    }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MIcon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, p.accent, 20.dp)
                        BText("$count ${if (count == 1) "reply" else "replies"}", 13f, p.accent, FontWeight.SemiBold)
                    }
                    AnimatedVisibility(expanded, enter = expandVertically(tween(320)), exit = shrinkVertically(tween(320))) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            replies.forEach { reply ->
                                key(reply.commentId) {
                                    CommentItem(reply, info, nested = true, replyTo = replyTo, onReply = onReply, submit = submit)
                                }
                            }
                            local.forEach { text ->
                                Column(Modifier.padding(start = 24.dp)) {
                                    FeedText("Local", 11, p.muted)
                                    FeedText(text, 13, p.text)
                                }
                            }
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

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun LocalReplyComposer(visible: Boolean, author: String, nested: Boolean,
                                   cancel: () -> Unit, submit: (String) -> Unit) {
        val p = LocalBilaiColors.current
        val handle = "@" + author.trimStart('@')
        var draft by remember(visible, author) { mutableStateOf(if (nested) "$handle " else "") }
        val viewport = remember { BringIntoViewRequester() }
        LaunchedEffect(visible) {
            if (visible) {
                delay(300)
                viewport.bringIntoView()
            }
        }
        AnimatedVisibility(visible, enter = expandVertically(tween(300)), exit = shrinkVertically(tween(300))) {
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(viewport),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                        FeedText("BT", 9, p.accent, FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = p.text, fontSize = 13.sp),
                            decorationBox = { field -> Box {
                                if (draft.isEmpty()) FeedText("Reply to $handle…", 13, p.muted)
                                field()
                            } })
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.muted))
                    }
                }
                val enabled = draft.isNotBlank() && (!nested || draft.trim() != handle)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WatchPill("Cancel", p.text, p.elevated, click = cancel)
                    WatchPill("Reply", if (enabled) p.onAccent else p.muted,
                        if (enabled) p.accent else p.elevated, enabled = enabled) { submit(draft.trim()) }
                }
            }
        }
    }

    @Composable
    private fun PlayerSettings(dismiss: () -> Unit) {
        val p = LocalBilaiColors.current
        if (fullscreen) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .55f)).clickable(onClick = dismiss))
                Column(Modifier.align(Alignment.CenterEnd).width(minOf(320.dp, maxWidth * .6f)).fillMaxHeight()
                    .background(p.sheetBg).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    SettingsContent(dismiss)
                }
            }
        } else AnimatedSheet(dismiss) { sheet ->
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsContent(sheet.close)
                Spacer(Modifier.height(8.dp).navigationBarsPadding())
            }
        }
    }

    @Composable
    private fun SettingsContent(close: () -> Unit) {
        val p = LocalBilaiColors.current
        val state by engine.state.collectAsState()
        val page = settingsPage
        val back = { settingsPage = "Player settings" }
        androidx.compose.animation.AnimatedContent(page, label = "settings page", transitionSpec = {
            val forward = targetState != "Player settings"
            (androidx.compose.animation.slideInHorizontally(tween(240)) { if (forward) it / 3 else -it / 3 } +
                fadeIn(tween(240))) togetherWith fadeOut(tween(120))
        }) { current ->
            Column {
                when (current) {
                    "Playback speed" -> {
                        SheetTitle("Playback speed", back = back)
                        BText(if (playbackSpeed == 1f) "Normal" else formatSpeed(playbackSpeed).replace("0x", "x"),
                            28f, p.text, FontWeight.ExtraBold,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundIconButton(Icons.Rounded.Remove, "Slower", p.text, p.elevated, 40.dp, 20.dp) {
                                changeSpeed(playbackSpeed - .05f)
                            }
                            androidx.compose.material3.Slider(
                                value = playbackSpeed, onValueChange = ::changeSpeed,
                                valueRange = .25f..4f, steps = 74, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated,
                                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
                            RoundIconButton(Icons.Rounded.Add, "Faster", p.text, p.elevated, 40.dp, 20.dp) {
                                changeSpeed(playbackSpeed + .05f)
                            }
                        }
                        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).size) { index ->
                                val preset = listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)[index]
                                val on = kotlin.math.abs(preset - playbackSpeed) < .001f
                                Box(Modifier.clip(CircleShape).background(if (on) p.accent else p.elevated)
                                    .clickable { changeSpeed(preset) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    BText(if (preset == 1f) "Normal" else "${preset}x".replace(".0x", "x"), 13f,
                                        if (on) p.onAccent else p.text, FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    "Captions" -> {
                        SheetTitle("Captions", back = back)
                        CheckRow("Off", captions == null) { chooseCaptions(null); back() }
                        if (state.subtitles.isEmpty()) BText("No captions for this video", 13f, p.muted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        for (track in state.subtitles) CheckRow(java.util.Locale.forLanguageTag(track).displayName
                            .ifBlank { track }, captions == track) { chooseCaptions(track); back() }
                    }
                    "Quality" -> {
                        SheetTitle("Quality", back = back)
                        CheckRow("Auto (recommended)", quality == null) { quality = null; engine.setQuality(null); back() }
                        for (height in state.qualities) CheckRow("${height}p", quality == height) {
                            quality = height; engine.setQuality(height); back()
                        }
                    }
                    else -> {
                        SheetTitle("Playback settings", close = close)
                        SheetRow(Icons.Rounded.PlayCircle, "Autoplay", trailing = { PlayerSwitch(autoplay, "Autoplay", ::toggleAutoplay) },
                            click = ::toggleAutoplay)
                        SheetRow(Icons.Rounded.ClosedCaption, "Captions", captions?.let {
                            java.util.Locale.forLanguageTag(it).displayName.ifBlank { it } } ?: "Off",
                            trailing = { Chevron() }) { settingsPage = "Captions" }
                        SheetRow(Icons.Rounded.Tune, "Quality", quality?.let { "${it}p" } ?: "Auto",
                            trailing = { Chevron() }) { settingsPage = "Quality" }
                        SheetRow(Icons.Rounded.Speed, "Playback speed",
                            if (playbackSpeed == 1f) "Normal" else formatSpeed(playbackSpeed),
                            trailing = { Chevron() }) { settingsPage = "Playback speed" }
                        SheetRow(Icons.Rounded.FastForward, "SponsorBlock",
                            trailing = { PlayerSwitch(sponsorBlock, "SponsorBlock", ::toggleSponsorBlock) },
                            click = ::toggleSponsorBlock)
                        SheetRow(Icons.Rounded.Repeat, "Loop video", trailing = {
                            PlayerSwitch(loopVideo, "Loop video") { loopVideo = !loopVideo; engine.setLoop(loopVideo) }
                        }) { loopVideo = !loopVideo; engine.setLoop(loopVideo) }
                        SheetRow(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture") { close(); enterPictureInPicture() }
                        SheetRow(Icons.Rounded.Headphones, "Play in background") { close(); backgroundRequested = true; activity.moveTaskToBack(true) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chevron() {
        Spacer(Modifier.width(6.dp))
        MIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, LocalBilaiColors.current.muted, 20.dp)
    }

    @Composable
    private fun CheckRow(title: String, checked: Boolean, click: () -> Unit) {
        val p = LocalBilaiColors.current
        Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp)) { if (checked) MIcon(Icons.Rounded.Check, p.accent, 22.dp) }
            BText(title, 15f, if (checked) p.accent else p.text, if (checked) FontWeight.SemiBold else FontWeight.Normal)
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
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        var input by remember(name) { mutableStateOf("") }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val valid = remember(input) { youtubeId(input) != null || input.contains("youtube.com/") || input.contains("youtu.be/") }
        LaunchedEffect(name) {
            // Offer a copied YouTube link right away.
            val copied = runCatching { clipboard.getText()?.text.orEmpty().trim() }.getOrDefault("")
            if (input.isEmpty() && (copied.contains("youtube.com/") || copied.contains("youtu.be/"))) input = copied
            runCatching { focus.requestFocus() }
        }
        AnimatedSheet(dismiss, topRadius = 24.dp) { sheet ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                        MIcon(Icons.Rounded.Link, p.accent, 24.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        BText("Open a YouTube link", 17f, p.text, FontWeight.Bold)
                        BText("Videos, Shorts, playlists and channels", 12f, p.muted)
                    }
                }
                Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(p.elevated)
                    .border(1.5.dp, if (valid) p.accent else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(input, { input = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                        textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontFamily = BilaiFont),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                            if (valid) { openUrl(input); sheet.close() } }),
                        decorationBox = { field ->
                            Box {
                                if (input.isEmpty()) BText("Paste a link (youtube.com or youtu.be)", 14f, p.muted)
                                field()
                            }
                        })
                    if (input.isNotEmpty()) RoundIconButton(Icons.Rounded.Close, "Clear", p.muted, Color.Transparent, 36.dp, 18.dp) {
                        input = ""
                    } else Box(Modifier.clip(CircleShape).clickable {
                        input = runCatching { clipboard.getText()?.text.orEmpty().trim() }.getOrDefault("")
                    }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        BText("Paste", 13f, p.accent, FontWeight.SemiBold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).clip(CircleShape).background(p.elevated).clickable(onClick = sheet.close)
                        .padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                        BText("Cancel", 14f, p.text, FontWeight.SemiBold)
                    }
                    Row(Modifier.weight(1f).clip(CircleShape).background(if (valid) p.accent else p.elevated)
                        .clickable(enabled = valid) { openUrl(input); sheet.close() }.padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        MIcon(Icons.Rounded.PlayArrow, if (valid) p.onAccent else p.muted, 20.dp)
                        Spacer(Modifier.width(4.dp))
                        BText("Play", 14f, if (valid) p.onAccent else p.muted, FontWeight.Bold)
                    }
                }
                Spacer(Modifier.navigationBarsPadding().imePadding())
            }
        }
    }
}

/** YouTube needs per-video comment params we can't obtain, so posting stays off. */
private const val COMMENT_POSTING = false
private const val PAGE_SEP = "\u001F"
internal const val BACKGROUND_PLAY_KEY = "bilai_background_play"

internal fun Modifier.debugTag(tag: String) = if (BuildConfig.DEBUG) testTag(tag) else this

@Composable
private fun Header(home: () -> Unit, openUrl: () -> Unit, search: () -> Unit, notify: () -> Unit,
                   dark: Boolean, toggleDark: () -> Unit, toggleAccent: () -> Unit) {
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
                HeaderButton(Glyph.SEARCH, "Search", "btn_search", p.text, search)
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

internal enum class Glyph(data: String) {
    COMMENT("M4 4h16v12H9l-5 4Z M8 9h8 M8 12h5"),
    THUMB_UP("M7 10v11 M14 5l-1 5h6a2 2 0 0 1 2 2l-2 7a2 2 0 0 1-2 2H7l-4-1v-9l4-1 5-7a2 2 0 0 1 2 2Z"),
    THUMB_DOWN("M7 14V3 M14 19l-1-5h6a2 2 0 0 0 2-2l-2-7a2 2 0 0 0-2-2H7L3 4v9l4 1 5 7a2 2 0 0 0 2-2Z"),
    SHARE("M8 11 16 6 M8 13l8 5 M9 12a3 3 0 1 1-6 0 3 3 0 1 1 6 0 M22 5a3 3 0 1 1-6 0 3 3 0 1 1 6 0 M22 19a3 3 0 1 1-6 0 3 3 0 1 1 6 0"),
    DOWNLOAD("M12 3v12m-4-4 4 4 4-4 M4 16v4h16v-4"),
    FLOAT("M4 14h6v6 M20 10h-6V4 M4 20l6-6 M20 4l-6 6"),
    CHEVRONS("m8 9 4-4 4 4m-8 6 4 4 4-4"),
    SUN("M16 12a4 4 0 1 1-8 0 4 4 0 1 1 8 0 M12 2v2 M12 20v2 M4.93 4.93l1.42 1.42 M17.65 17.65l1.42 1.42 M2 12h2 M20 12h2 M4.93 19.07l1.42-1.42 M17.65 6.35l1.42-1.42"),
    MOON("M20.985 12.486A9 9 0 1 1 11.514 3.015a.5.5 0 0 1 .44.814 6.5 6.5 0 0 0 8.217 8.217.5.5 0 0 1 .814.44Z"),
    PALETTE("M12 22a10 10 0 1 1 10-10c0 1.4-1.2 2.5-2.6 2.5H17a1.5 1.5 0 0 0-1.2 2.4c.4.5.7 1.1.7 1.8C16.5 20.5 14.7 22 12 22Z M7.5 12h.1 M10 7h.1 M15 6.5h.1 M18 10h.1"),
    SETTINGS("M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8 M19.4 15a1.6 1.6 0 0 0 .32 1.76l.06.06-1.9 1.9-.06-.06a1.6 1.6 0 0 0-1.76-.32 1.6 1.6 0 0 0-1.02 1.47V20h-2.7v-.09A1.6 1.6 0 0 0 11.32 18.5a1.6 1.6 0 0 0-1.76.32l-.06.06-1.9-1.9.06-.06A1.6 1.6 0 0 0 8 15.16a1.6 1.6 0 0 0-1.47-1.02H6v-2.7h.09A1.6 1.6 0 0 0 7.5 10.42a1.6 1.6 0 0 0-.32-1.76l-.06-.06 1.9-1.9.06.06a1.6 1.6 0 0 0 1.76.32A1.6 1.6 0 0 0 11.86 5.6V5h2.7v.09A1.6 1.6 0 0 0 15.58 6.5a1.6 1.6 0 0 0 1.76-.32l.06-.06 1.9 1.9-.06.06a1.6 1.6 0 0 0-.32 1.76A1.6 1.6 0 0 0 20.4 10.86H21v2.7h-.09A1.6 1.6 0 0 0 19.4 15Z"),
    CLOCK("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20 M12 6v6l4 2"),
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
internal fun Icon(glyph: Glyph, color: Color, modifier: Modifier) {
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
    Canvas(Modifier.size(32.dp)) {
        scale(size.width / 108, size.height / 108, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(cat, p.accent)
            drawPath(play, p.onAccent)
        }
    }
}
