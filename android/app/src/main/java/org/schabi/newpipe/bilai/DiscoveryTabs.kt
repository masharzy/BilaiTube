package org.schabi.newpipe.bilai

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.bilai.engine.PlaybackEngine
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

@Composable
internal fun ShortsTab(home: HomeFeedViewModel, engine: PlaybackEngine, initialUrl: String?,
                       fixture: List<StreamInfoItem>?, like: () -> Unit, dislike: () -> Unit,
                       comments: (String) -> Unit, subscribe: (String) -> Unit, share: (String) -> Unit) {
    val p = LocalBilaiColors.current
    var fallback by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt, fixture) {
        if (fixture != null) return@LaunchedEffect
        home.start()
        failed = false
        fallback = withContext(Dispatchers.IO) {
            runCatching {
                val factory = ServiceList.YouTube.searchQHFactory
                val filter = (0..60).mapNotNull { runCatching { factory.getFilterItem(it) }.getOrNull() }
                    .firstOrNull { it.name == "shorts" }
                ExtractorHelper.searchFor(ServiceList.YouTube.serviceId, "shorts",
                    listOfNotNull(filter), emptyList()).blockingGet().relatedItems
                    .filterIsInstance<StreamInfoItem>().filter { it.isShortFormContent }
            }.getOrDefault(emptyList())
        }
        failed = fallback.isEmpty()
    }
    val clips = fixture ?: (home.shortItems + fallback).distinctBy { it.url }
    val initialPage = clips.indexOfFirst { it.url == initialUrl }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage) { clips.size }
    var placed by remember(initialUrl) { mutableStateOf(false) }
    LaunchedEffect(initialUrl, clips) {
        if (!placed && clips.isNotEmpty()) {
            pager.scrollToPage(initialPage)
            placed = true
        }
    }
    val active = clips.getOrNull(pager.currentPage)
    val state by engine.state.collectAsState()
    val metadata by engine.current.collectAsState()
    LaunchedEffect(active?.url) {
        active?.let { engine.play(it.url) }
        clips.getOrNull(pager.currentPage + 1)?.let { engine.prefetch(it.url) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (clips.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                FeedText(if (failed) "Couldn't load Shorts." else "Loading Shorts…", 14, Color.White)
                if (failed) WatchPill("Retry", p.onAccent, p.accent) { attempt++ }
            }
        } else {
            VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                key = { clips[it].url },
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(.78f, 320f))) { page ->
                val clip = clips[page]
                val ownsPlayback = page == pager.currentPage
                val info = metadata?.takeIf { ownsPlayback && it.url == clip.url }
                Box(Modifier.fillMaxSize().background(Color.Black).clickable {
                    if (ownsPlayback) engine.togglePlay()
                }) {
                    if (ownsPlayback) AndroidView(factory = { TextureView(it).also(engine::attachSurface) },
                        modifier = Modifier.fillMaxSize(), update = { texture ->
                            val w = state.videoWidth
                            val h = state.videoHeight
                            if (texture.width > 0 && texture.height > 0 && w > 0 && h > 0) {
                                val ratio = (w.toFloat() / h) / (texture.width.toFloat() / texture.height)
                                texture.setTransform(android.graphics.Matrix().apply {
                                    setScale(ratio.coerceAtLeast(1f), (1f / ratio).coerceAtLeast(1f),
                                        texture.width / 2f, texture.height / 2f)
                                })
                            }
                        })
                    if (info != null && state.firstFrame) Box(Modifier.size(1.dp)
                        .debugTag("short_frame_${page + 1}"))
                    AnimatedVisibility(info == null || !state.firstFrame, exit = fadeOut(tween(150))) {
                        NetImage(clip.thumbnailUrl, Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxWidth().height(130.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent))))
                    Box(Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 64.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NetImage(info?.uploaderAvatarUrl ?: clip.uploaderAvatarUrl,
                                Modifier.size(32.dp).clip(CircleShape), avatar = true)
                            val uploader = (info?.uploaderName ?: clip.uploaderName).orEmpty()
                                .takeUnless { it == "YouTube Shorts" }.orEmpty()
                            val channel = info?.uploaderUrl ?: clip.uploaderUrl
                            val handle = channel?.let { android.net.Uri.parse(it).lastPathSegment }
                                ?.takeIf { it.startsWith("@") }
                            if (!handle.isNullOrBlank() || uploader.isNotBlank()) {
                                Box(Modifier.weight(1f, fill = false)) {
                                    FeedText(handle ?: uploader, 12, Color.White, FontWeight.Bold)
                                }
                            }
                            if (!channel.isNullOrBlank()) WatchPill("Subscribe", p.onAccent, p.accent) { subscribe(channel) }
                        }
                        FeedText(info?.name ?: clip.name, 14, Color.White, FontWeight.Medium)
                    }
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShortsAction(Glyph.THUMB_UP, "Like", watchCount(info?.likeCount ?: -1), like)
                        ShortsAction(Glyph.THUMB_DOWN, "Dislike", watchCount(info?.dislikeCount ?: -1), dislike)
                        ShortsAction(Glyph.COMMENT, "Comments", "") { comments(clip.url) }
                        ShortsAction(Glyph.SHARE, "Share", "") { share(clip.url) }
                    }
                    if (ownsPlayback && state.error) Box(Modifier.align(Alignment.Center)) {
                        WatchPill("Retry video", p.onAccent, p.accent) { engine.play(clip.url) }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { FeedText("Shorts", 16, Color.White, FontWeight.Bold) }
    }
}

@Composable
private fun ShortsAction(glyph: Glyph, label: String, count: String, action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f))
            .clickable(onClick = action).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Icon(glyph, Color.White, Modifier.size(19.dp))
        }
        if (count.isNotEmpty()) FeedText(count, 11, Color.White)
    }
}

@Composable
internal fun SubscriptionsTab(open: (String, String) -> Unit, menu: (StreamInfoItem) -> Unit, fixture: Boolean = false) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    var channels by remember { mutableStateOf<List<SubscriptionEntity>>(emptyList()) }
    var channel by remember { mutableStateOf<Long?>(null) }
    var rows by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val shownChannels = if (fixture) remember {
        listOf("DevForge Labs", "Frames of Bengal").mapIndexed { index, title ->
            SubscriptionEntity().apply {
                uid = (index + 1).toLong()
                name = title
                url = "https://www.youtube.com/channel/UC000000000000000000000" + index
                avatarUrl = "bilai-review://avatar"
                serviceId = 0
            }
        }
    } else channels
    DisposableEffect(context) {
        if (fixture) onDispose { }
        else {
            val subscription = SubscriptionManager(context).subscriptions().subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe({ channels = it }, {})
            onDispose { subscription.dispose() }
        }
    }
    LaunchedEffect(shownChannels, channel, fixture) {
        if (fixture) {
            rows = WatchReviewFixture.stream("fixture").relatedItems
                .filterIsInstance<StreamInfoItem>().filterIndexed { index, _ ->
                    channel == null || index % 2L == channel!! - 1L
                }.take(6)
            loading = false
            return@LaunchedEffect
        }
        val current = shownChannels.filter { channel == null || it.uid == channel }.take(20)
        rows = emptyList()
        loading = current.isNotEmpty()
        rows = withContext(Dispatchers.IO) {
            current.flatMap { item ->
                runCatching {
                    val info = ExtractorHelper.getChannelInfo(item.serviceId, item.url, false).blockingGet()
                    val direct = info.relatedItems.filterIsInstance<StreamInfoItem>()
                    if (direct.isNotEmpty()) direct else {
                        val handler = info.tabs.firstOrNull { tab ->
                            tab.contentFilters.any { filter -> filter.name.equals("videos", ignoreCase = true) }
                        } ?: info.tabs.firstOrNull()
                        handler?.let {
                            ExtractorHelper.getChannelTab(item.serviceId, it, false).blockingGet()
                                .relatedItems.filterIsInstance<StreamInfoItem>()
                        }.orEmpty()
                    }
                }.getOrDefault(emptyList())
            }.distinctBy { it.url }.sortedByDescending { it.uploadDate?.offsetDateTime() }
        }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize()) {
        stickyHeader {
            LazyRow(Modifier.fillMaxWidth().background(p.bg),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { channel = null }) {
                        Box(Modifier.size(48.dp).background(if (channel == null) p.accent else p.elevated, CircleShape),
                            contentAlignment = Alignment.Center) { FeedText("All", 12, if (channel == null) p.onAccent else p.text) }
                        FeedText("All", 10, p.muted)
                    }
                }
                items(shownChannels, key = { it.uid }) { item ->
                    Column(Modifier.width(54.dp).clickable { channel = item.uid }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp)
                            .then(if (channel == item.uid) Modifier.border(2.dp, p.accent, CircleShape) else Modifier)
                            .padding(3.dp)) {
                            NetImage(item.avatarUrl, Modifier.fillMaxSize().clip(CircleShape), avatar = true)
                            Box(Modifier.align(Alignment.BottomEnd).size(12.dp)
                                .border(2.dp, p.bg, CircleShape).background(p.accent, CircleShape))
                        }
                        FeedText(item.name.substringBefore(' '), 10, p.muted)
                    }
                }
            }
        }
        if (rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                FeedText(if (loading) "Loading subscriptions…" else if (shownChannels.isEmpty())
                    "Subscribe to channels to see their latest videos." else "Couldn't load subscriptions.", 12, p.muted)
            }
        }
        items(rows, key = { it.url }) { VideoCard(it, 0f, open, menu) }
    }
}

@Composable
internal fun YouTab(dark: Boolean, red: Boolean, fixtureHistory: Boolean, signedIn: Boolean, accountEmail: String?,
                    toggleDark: () -> Unit, toggleAccent: () -> Unit,
                    open: (String, String) -> Unit, show: (String) -> Unit,
                   skip: Boolean, toggleSkip: () -> Unit, hideShorts: Boolean, toggleHideShorts: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    val dislikeKey = context.getString(R.string.show_dislike_key)
    var dislike by remember { mutableStateOf(prefs.getBoolean(dislikeKey, false)) }
    var recent by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    val p = LocalBilaiColors.current
    DisposableEffect(context) {
        val history = HistoryRecordManager(context).streamHistory.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe({ entries ->
                recent = entries.sortedByDescending { it.accessDate }.distinctBy { it.streamId }
                    .map { it.toStreamInfoItem() }
            }, {})
        onDispose { history.dispose() }
    }
    val historyShown = if (fixtureHistory)
        remember { WatchReviewFixture.stream("fixture").relatedItems.filterIsInstance<StreamInfoItem>().take(4) }
    else recent
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).background(p.accent, CircleShape), contentAlignment = Alignment.Center) {
                    FeedText(if (signedIn) accountEmail.orEmpty().take(2).uppercase() else "BT", 18, p.onAccent, FontWeight.ExtraBold)
                }
                Column(Modifier.weight(1f)) {
                    FeedText(if (signedIn) accountEmail ?: "YouTube account" else "Guest Mode", 16, p.text, FontWeight.Bold)
                    FeedText(if (signedIn) "Synced via local SAPISID cookie" else "100% Local SQLite • Zero Server", 12, p.muted)
                }
                WatchPill(if (signedIn) "Manage" else "Sign in", p.text, p.elevated) {
                    show(if (signedIn) "Settings" else "Sign in")
                }
                Box(Modifier.size(32.dp).clip(CircleShape).clickable { show("Settings") },
                    contentAlignment = Alignment.Center) {
                    Icon(Glyph.SETTINGS, p.muted, Modifier.size(18.dp))
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.background(p.elevated, CircleShape).clickable(onClick = toggleDark)
                        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (dark) Glyph.SUN else Glyph.MOON, p.text, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        FeedText(if (dark) "Switch to Light" else "Switch to Dark", 12, p.text, FontWeight.SemiBold)
                    }
                }
                item {
                    Row(Modifier.background(p.accentSoft, CircleShape).clickable(onClick = toggleAccent)
                        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.PALETTE, p.accent, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        FeedText(if (red) "Classic Red" else "Warm Amber", 12, p.accent, FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { show("History") }, horizontalArrangement = Arrangement.SpaceBetween) {
                FeedText("History", 14, p.text, FontWeight.Bold)
                FeedText("${historyShown.size} watched", 12, p.muted)
            }
            if (historyShown.isNotEmpty()) LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(historyShown.take(15), key = { it.url }) { item ->
                    Column(Modifier.width(144.dp).clickable { open(item.url, item.name) }) {
                        Box {
                            NetImage(item.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9)
                                .clip(RoundedCornerShape(12.dp)))
                            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                .background(Color.Black.copy(alpha = .85f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)) {
                                FeedText(formatTime(item.duration), 9, Color.White, FontWeight.SemiBold)
                            }
                        }
                        FeedText(item.name, 11, p.text, FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Column(Modifier.background(p.elevated, RoundedCornerShape(16.dp))) {
                ProfileRow("Offline Downloads", "", Glyph.DOWNLOAD) { show("Offline Downloads") }
                ProfileRow("Watch Later", "", Glyph.FLOAT) { show("Watch Later") }
                ProfileRow("Playlists", "", Glyph.HOME) { show("Playlists") }
                ProfileRow("SponsorBlock Auto-Skip", if (skip) "ON" else "OFF", click = toggleSkip)
                ProfileRow("Return YouTube Dislike", if (dislike) "ON" else "OFF") {
                    dislike = !dislike; prefs.edit().putBoolean(dislikeKey, dislike).apply()
                }
                ProfileRow("Hide Shorts from Home", if (hideShorts) "ON" else "OFF", click = toggleHideShorts)
            }
        }
    }
}

@Composable
private fun ProfileRow(title: String, value: String, glyph: Glyph? = null, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) {
            Icon(glyph, p.accent, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) { FeedText(title, 12, p.text) }
        FeedText(value, 12, if (value == "ON") Color(0xff10b981) else p.muted, FontWeight.Bold)
    }
}
