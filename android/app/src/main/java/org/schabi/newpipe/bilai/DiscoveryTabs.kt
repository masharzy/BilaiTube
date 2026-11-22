package org.schabi.newpipe.bilai

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
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
                       like: () -> Unit, subscribe: (String) -> Unit, share: (String) -> Unit) {
    val p = LocalBilaiColors.current
    var fallback by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
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
    val clips = (home.shortItems + fallback).distinctBy { it.url }
    val initialPage = clips.indexOfFirst { it.url == initialUrl }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage) { clips.size }
    var placed by remember(initialUrl) { mutableStateOf(false) }
    LaunchedEffect(initialUrl, clips) {
        if (!placed && clips.isNotEmpty()) {
            pager.scrollToPage(initialPage)
            placed = true
        }
    }
    val active = clips.getOrNull(pager.settledPage)
    val state by engine.state.collectAsState()
    LaunchedEffect(active?.url) { active?.let { engine.play(it.url) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (clips.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                FeedText(if (failed) "Couldn't load Shorts." else "Loading Shorts…", 14, Color.White)
                if (failed) WatchPill("Retry", p.onAccent, p.accent) { attempt++ }
            }
        } else {
            AndroidView(factory = { context -> TextureView(context).also(engine::attachSurface) },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().fillMaxHeight(.72f))
            VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val clip = clips[page]
                Box(Modifier.fillMaxSize().clickable(onClick = engine::togglePlay)) {
                    if (page != pager.settledPage || state.videoWidth == 0) NetImage(clip.thumbnailUrl, Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 64.dp, bottom = 24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NetImage(clip.uploaderAvatarUrl, Modifier.size(32.dp).clip(CircleShape), avatar = true)
                            FeedText(clip.uploaderName.orEmpty(), 12, Color.White, FontWeight.Bold)
                            WatchPill("Subscribe", p.onAccent, p.accent) { clip.uploaderUrl?.let(subscribe) }
                        }
                        FeedText(clip.name, 14, Color.White, FontWeight.Medium)
                    }
                }
            }
            if (state.error) Box(Modifier.align(Alignment.Center)) {
                WatchPill("Retry video", p.onAccent, p.accent) { active?.let { engine.play(it.url) } }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WatchPill("Like", Color.White, Color.Black.copy(alpha = .45f), click = like)
                active?.let { WatchPill("Share", Color.White, Color.Black.copy(alpha = .45f)) { share(it.url) } }
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { FeedText("Shorts", 16, Color.White, FontWeight.Bold) }
    }
}

@Composable
internal fun SubscriptionsTab(open: (String, String) -> Unit, menu: (StreamInfoItem) -> Unit) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    var channels by remember { mutableStateOf<List<SubscriptionEntity>>(emptyList()) }
    var channel by remember { mutableStateOf<Long?>(null) }
    var rows by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = SubscriptionManager(context).subscriptions().subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe({ channels = it }, {})
        onDispose { subscription.dispose() }
    }
    LaunchedEffect(channels, channel) {
        val current = channels.filter { channel == null || it.uid == channel }.take(20)
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
                items(channels, key = { it.uid }) { item ->
                    Column(Modifier.width(54.dp).clickable { channel = item.uid }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).background(if (channel == item.uid) p.accent else p.elevated, CircleShape)
                            .padding(2.dp)) {
                            NetImage(item.avatarUrl, Modifier.fillMaxSize().clip(CircleShape), avatar = true)
                        }
                        FeedText(item.name.substringBefore(' '), 10, p.muted)
                    }
                }
            }
        }
        if (rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                FeedText(if (loading) "Loading subscriptions…" else if (channels.isEmpty())
                    "Subscribe to channels to see their latest videos." else "Couldn't load subscriptions.", 12, p.muted)
            }
        }
        items(rows, key = { it.url }) { VideoCard(it, 0f, open, menu) }
    }
}

@Composable
internal fun YouTab(dark: Boolean, signedIn: Boolean, open: (String, String) -> Unit, show: (String) -> Unit,
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
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).background(p.accent, CircleShape), contentAlignment = Alignment.Center) {
                    FeedText("BT", 18, p.onAccent, FontWeight.ExtraBold)
                }
                Column(Modifier.weight(1f)) {
                    FeedText(if (signedIn) "YouTube account" else "Guest Mode", 16, p.text, FontWeight.Bold)
                    FeedText("100% Local SQLite • Zero Server", 12, p.muted)
                }
                WatchPill(if (signedIn) "Manage" else "Sign in", p.text, p.elevated) { show(if (signedIn) "Settings" else "Sign in") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { show("Settings") }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                FeedText("Settings", 14, p.text, FontWeight.Bold)
                FeedText(if (dark) "☾" else "☀", 16, p.muted)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { show("History") }, horizontalArrangement = Arrangement.SpaceBetween) {
                FeedText("History", 14, p.text, FontWeight.Bold)
                FeedText("${recent.size} watched", 12, p.muted)
            }
            if (recent.isNotEmpty()) LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent.take(15), key = { it.url }) { item ->
                    Column(Modifier.width(144.dp).clickable { open(item.url, item.name) }) {
                        NetImage(item.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)))
                        FeedText(item.name, 11, p.text, FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Column(Modifier.background(p.elevated, RoundedCornerShape(16.dp))) {
                ProfileRow("Offline Downloads", "") { show("Offline Downloads") }
                ProfileRow("Watch Later", "") { show("Watch Later") }
                ProfileRow("Playlists", "") { show("Playlists") }
                ProfileRow("SponsorBlock Auto-Skip", if (skip) "ON" else "OFF", toggleSkip)
                ProfileRow("Return YouTube Dislike", if (dislike) "ON" else "OFF") {
                    dislike = !dislike; prefs.edit().putBoolean(dislikeKey, dislike).apply()
                }
                ProfileRow("Hide Shorts from Home", if (hideShorts) "ON" else "OFF", toggleHideShorts)
            }
        }
    }
}

@Composable
private fun ProfileRow(title: String, value: String, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        FeedText(title, 12, p.text)
        FeedText(value, 12, if (value == "ON") Color(0xff10b981) else p.muted, FontWeight.Bold)
    }
}
