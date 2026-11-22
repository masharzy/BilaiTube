package org.schabi.newpipe.bilai

import android.view.TextureView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManagerService
import org.schabi.newpipe.bilai.engine.PlaybackEngine
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

private fun shortsSearchFilter(): List<org.schabi.newpipe.extractor.search.filter.FilterItem> {
    val factory = ServiceList.YouTube.searchQHFactory
    return listOfNotNull((0..60).mapNotNull { runCatching { factory.getFilterItem(it) }.getOrNull() }
        .firstOrNull { it.name == "shorts" })
}

private val SHORTS_QUERIES = listOf("#shorts", "shorts funny", "shorts trending", "shorts music", "shorts gaming")

@Composable
internal fun ShortsTab(home: HomeFeedViewModel, engine: PlaybackEngine, initialUrl: String?,
                       fixture: List<StreamInfoItem>?, like: () -> Unit, dislike: () -> Unit,
                       comments: (String) -> Unit, subscribe: (String) -> Unit, share: (String) -> Unit,
                       search: () -> Unit = {}) {
    val p = LocalBilaiColors.current
    var extra by remember { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var queryIndex by remember { mutableIntStateOf(0) }
    var nextPage by remember { mutableStateOf<org.schabi.newpipe.extractor.Page?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    suspend fun loadMore() {
        if (loadingMore || fixture != null) return
        loadingMore = true
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val service = ServiceList.YouTube.serviceId
                    val query = SHORTS_QUERIES[queryIndex % SHORTS_QUERIES.size]
                    val page = nextPage
                    if (page != null && org.schabi.newpipe.extractor.Page.isValid(page)) {
                        val more = ExtractorHelper.getMoreSearchItems(service, query, shortsSearchFilter(),
                            emptyList(), page).blockingGet()
                        more.items to more.nextPage
                    } else {
                        val first = ExtractorHelper.searchFor(service, query, shortsSearchFilter(), emptyList())
                            .blockingGet()
                        first.relatedItems to first.nextPage
                    }
                }.getOrNull()
            }
            if (result != null) {
                val items = result.first.filterIsInstance<StreamInfoItem>()
                extra = (extra + items).distinctBy { youtubeId(it.url) ?: it.url }
                nextPage = result.second
                if (!org.schabi.newpipe.extractor.Page.isValid(result.second)) { queryIndex++; nextPage = null }
            } else {
                queryIndex++
                nextPage = null
            }
        } finally {
            loadingMore = false
        }
    }
    LaunchedEffect(attempt, fixture) {
        if (fixture != null) return@LaunchedEffect
        home.start()
        failed = false
        loadMore()
        failed = extra.isEmpty() && home.shortItems.isEmpty()
    }
    val clips = remember(fixture, home.shortItems, extra) {
        fixture ?: (home.shortItems + extra).distinctBy { youtubeId(it.url) ?: it.url }
    }
    val initialPage = clips.indexOfFirst { youtubeId(it.url) == youtubeId(initialUrl) }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage) { clips.size }
    var placed by remember(initialUrl) { mutableStateOf(false) }
    LaunchedEffect(initialUrl, clips.size) {
        if (!placed && clips.isNotEmpty()) {
            pager.scrollToPage(initialPage)
            placed = true
        }
    }
    val active = clips.getOrNull(pager.currentPage)
    val state by engine.state.collectAsState()
    val metadata by engine.current.collectAsState()
    // Start the next clip as soon as the pager passes the halfway point, not after it settles.
    LaunchedEffect(active?.url) {
        active?.let { engine.play(it.url) }
        clips.getOrNull(pager.currentPage + 1)?.let { engine.prefetch(it.url) }
    }
    LaunchedEffect(pager.currentPage, clips.size) {
        if (clips.isNotEmpty() && pager.currentPage >= clips.size - 4) loadMore()
    }
    LaunchedEffect(state.ended) { if (state.ended) engine.replay() }
    var pauseFlash by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (clips.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!failed) androidx.compose.material3.CircularProgressIndicator(color = p.accent,
                    modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                FeedText(if (failed) "Couldn't load Shorts." else "Loading Shorts…", 14, Color.White)
                if (failed) WatchPill("Retry", p.onAccent, p.accent) { attempt++ }
            }
        } else {
            VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                key = { youtubeId(clips[it].url) ?: clips[it].url },
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(.85f, 400f))) { page ->
                val clip = clips[page]
                val ownsPlayback = page == pager.currentPage
                val info = metadata?.takeIf { ownsPlayback && youtubeId(it.url) == youtubeId(clip.url) }
                Box(Modifier.fillMaxSize().background(Color.Black)
                    .pointerInput(ownsPlayback) {
                        detectTapGestures(onTap = {
                            if (ownsPlayback) { engine.togglePlay(); pauseFlash++ }
                        }, onDoubleTap = { if (ownsPlayback) like() })
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
                    AnimatedVisibility(!ownsPlayback || info == null || !state.firstFrame, enter = fadeIn(tween(1)),
                        exit = fadeOut(tween(150))) {
                        NetImage(clip.thumbnailUrl, Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
                    Box(Modifier.fillMaxWidth().height(240.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                    if (ownsPlayback && state.error) Column(Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeedText("Couldn't play this Short", 13, Color.White)
                        WatchPill("Retry", p.onAccent, p.accent) { engine.play(clip.url) }
                    } else if (ownsPlayback && info == null) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color.White.copy(alpha = .8f),
                            modifier = Modifier.align(Alignment.Center).size(36.dp), strokeWidth = 3.dp)
                    }
                    if (ownsPlayback) PauseFlash(pauseFlash, state.playing, Modifier.align(Alignment.Center))
                    val uploader = (info?.uploaderName ?: clip.uploaderName).orEmpty()
                        .takeUnless { it.isBlank() || it == "YouTube Shorts" }
                    val channel = (info?.uploaderUrl ?: clip.uploaderUrl)?.takeIf { it.isNotBlank() }
                    val handle = channel?.let { android.net.Uri.parse(it).lastPathSegment }?.takeIf { it.startsWith("@") }
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 84.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val avatar = info?.uploaderAvatarUrl ?: clip.uploaderAvatarUrl
                            if (!avatar.isNullOrBlank()) NetImage(avatar, Modifier.size(34.dp).clip(CircleShape), avatar = true)
                            else Box(Modifier.size(34.dp).background(if (uploader == null) Color.White.copy(alpha = .2f) else p.accent, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (uploader != null) FeedText(uploader.trimStart('@').take(1).uppercase(), 14, p.onAccent, FontWeight.Bold)
                            }
                            if (handle != null || uploader != null) Box(Modifier.weight(1f, fill = false)) {
                                BText(handle ?: uploader.orEmpty(), 14f, Color.White, FontWeight.SemiBold)
                            } else Box(Modifier.size(110.dp, 12.dp).background(Color.White.copy(alpha = .25f), CircleShape))
                            if (channel != null) Box(Modifier.clip(CircleShape).background(Color.White)
                                .clickable { subscribe(channel) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                BText("Subscribe", 13f, Color.Black, FontWeight.SemiBold)
                            }
                        }
                        BText(info?.name ?: clip.name, 14f, Color.White, maxLines = 2, lineHeight = 19f)
                    }
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        ShortsAction(Icons.Rounded.ThumbUp, "Like",
                            watchCount(info?.likeCount ?: -1).ifBlank { "Like" }, like)
                        ShortsAction(Icons.Rounded.ThumbDown, "Dislike",
                            watchCount(info?.dislikeCount ?: -1).ifBlank { "Dislike" }, dislike)
                        ShortsAction(Icons.Rounded.ChatBubble, "Comments", "Comments") { comments(clip.url) }
                        ShortsAction(Icons.Rounded.Share, "Share", "Share") { share(clip.url) }
                        val avatar = info?.uploaderAvatarUrl
                        if (!avatar.isNullOrBlank()) NetImage(avatar, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp)), avatar = true)
                    }
                    if (ownsPlayback && state.durationMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .height(2.dp).background(Color.White.copy(alpha = .25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(
                            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)).background(p.accent))
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BText("Shorts", 20f, Color.White, FontWeight.Bold, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.Search, "Search", Color.White, Color.Transparent, 44.dp, 26.dp, click = search)
        }
    }
}

@Composable
private fun PauseFlash(trigger: Int, playing: Boolean, modifier: Modifier) {
    if (trigger == 0) return
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(trigger) {
        alpha.snapTo(1f); scale.snapTo(.8f)
        kotlinx.coroutines.coroutineScope {
            launch { scale.animateTo(1.15f, tween(450)) }
            launch { delay(150); alpha.animateTo(0f, tween(300)) }
        }
    }
    Box(modifier.size(76.dp).graphicsLayer { this.alpha = alpha.value; scaleX = scale.value; scaleY = scale.value }
        .background(Color.Black.copy(alpha = .45f), CircleShape), contentAlignment = Alignment.Center) {
        MIcon(if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, Color.White, 44.dp)
    }
}

@Composable
private fun ShortsAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, caption: String,
                         action: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = action)
        .semantics { contentDescription = label }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = .35f), CircleShape), contentAlignment = Alignment.Center) {
            MIcon(icon, Color.White, 26.dp)
        }
        BText(caption, 12f, Color.White, FontWeight.SemiBold)
    }
}

private enum class SubsFilter(val label: String) { ALL("All"), TODAY("Today"), VIDEOS("Videos"), SHORTS("Shorts"), LIVE("Live") }

private suspend fun loadChannelVideos(item: SubscriptionEntity): List<StreamInfoItem> = withContext(Dispatchers.IO) {
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
        }.map { video ->
            // Channel feeds often omit the uploader; fill it so cards show the channel name and avatar.
            if (video.uploaderName.isNullOrBlank()) video.uploaderName = item.name
            if (video.uploaderUrl.isNullOrBlank()) video.uploaderUrl = item.url
            if (video.uploaderAvatarUrl.isNullOrBlank()) video.uploaderAvatarUrl = item.avatarUrl
            video
        }
    }.getOrDefault(emptyList())
}

@Composable
internal fun SubscriptionsTab(open: (String, String) -> Unit, menu: (StreamInfoItem) -> Unit, fixture: Boolean = false,
                              explore: () -> Unit = {}, channelPage: (String, String) -> Unit = { _, _ -> },
                              openShort: (String) -> Unit = {}) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    var channels by remember { mutableStateOf<List<SubscriptionEntity>?>(null) }
    var channel by remember { mutableStateOf<Long?>(null) }
    var filter by remember { mutableStateOf(SubsFilter.ALL) }
    val perChannel = remember { mutableStateMapOf<Long, List<StreamInfoItem>>() }
    var pending by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val shownChannels: List<SubscriptionEntity>? = if (fixture) remember {
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
                .observeOn(AndroidSchedulers.mainThread()).subscribe({ channels = it }, { channels = emptyList() })
            onDispose { subscription.dispose() }
        }
    }
    LaunchedEffect(shownChannels, fixture, refresh) {
        val list = shownChannels ?: return@LaunchedEffect
        if (fixture) {
            val rows = WatchReviewFixture.stream("fixture").relatedItems.filterIsInstance<StreamInfoItem>()
            list.forEach { ch -> perChannel[ch.uid] = rows.filterIndexed { i, _ -> i % 2L == ch.uid - 1L }.take(6) }
            return@LaunchedEffect
        }
        val missing = list.filter { refresh > 0 || it.uid !in perChannel }.take(60)
        pending = missing.size
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        kotlinx.coroutines.coroutineScope {
            missing.forEach { item ->
                launch {
                    gate.acquire()
                    try { perChannel[item.uid] = loadChannelVideos(item) } finally { gate.release(); pending-- }
                }
            }
        }
    }
    val rows = remember(perChannel.toMap(), channel, filter) {
        val now = java.time.OffsetDateTime.now()
        perChannel.filterKeys { channel == null || it == channel }.values.flatten()
            .distinctBy { youtubeId(it.url) ?: it.url }
            .filter { video ->
                when (filter) {
                    SubsFilter.ALL -> true
                    SubsFilter.TODAY -> video.uploadDate?.offsetDateTime()?.isAfter(now.minusDays(1)) == true
                    SubsFilter.VIDEOS -> !video.isShortFormContent
                    SubsFilter.SHORTS -> video.isShortFormContent
                    SubsFilter.LIVE -> video.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
                }
            }
            .sortedByDescending { it.uploadDate?.offsetDateTime() }
    }
    val list = shownChannels
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (list == null) {
            items(3) { SubsSkeleton() }
            return@LazyColumn
        }
        if (list.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(96.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                        MIcon(Icons.Rounded.Subscriptions, p.accent, 44.dp)
                    }
                    BText("Don't miss new videos", 18f, p.text, FontWeight.Bold)
                    BText("Subscribe to channels you love and their newest uploads show up here.", 13f, p.muted,
                        maxLines = 3, lineHeight = 18f)
                    Box(Modifier.padding(top = 6.dp).clip(CircleShape).background(p.text).clickable(onClick = explore)
                        .padding(horizontal = 20.dp, vertical = 10.dp)) {
                        BText("Explore channels", 14f, p.bg, FontWeight.SemiBold)
                    }
                }
            }
            return@LazyColumn
        }
        item(key = "strip") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(list, key = { it.uid }) { item ->
                        val selected = channel == item.uid
                        val dimmed = channel != null && !selected
                        Column(Modifier.width(64.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { channel = if (selected) null else item.uid }
                            .graphicsLayer { alpha = if (dimmed) .45f else 1f },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(58.dp)
                                .then(if (selected) Modifier.border(2.dp, p.accent, CircleShape) else Modifier)
                                .padding(3.dp)) {
                                NetImage(item.avatarUrl, Modifier.fillMaxSize().clip(CircleShape), avatar = true)
                                if (perChannel[item.uid]?.any { v -> v.uploadDate?.offsetDateTime()
                                        ?.isAfter(java.time.OffsetDateTime.now().minusDays(2)) == true } == true) {
                                    Box(Modifier.align(Alignment.BottomEnd).size(12.dp)
                                        .border(2.dp, p.bg, CircleShape).background(p.accent, CircleShape))
                                }
                            }
                            BText(item.name, 11f, if (selected) p.text else p.muted,
                                if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { channel = null; filter = SubsFilter.ALL }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    BText("All", 14f, p.accent, FontWeight.SemiBold)
                }
            }
        }
        stickyHeader(key = "chips") {
            LazyRow(Modifier.fillMaxWidth().background(p.bg), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SubsFilter.entries) { option ->
                    val on = option == filter
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) p.chipActiveBg else p.elevated)
                        .clickable { filter = option }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        BText(option.label, 13f, if (on) p.chipActiveText else p.text, FontWeight.SemiBold)
                    }
                }
            }
        }
        val selected = list.firstOrNull { it.uid == channel }
        if (selected != null) item(key = "channel-head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NetImage(selected.avatarUrl, Modifier.size(40.dp).clip(CircleShape), avatar = true)
                BText(selected.name, 16f, p.text, FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).background(p.elevated).clickable { channelPage(selected.url, selected.name) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)) {
                    BText("View channel", 13f, p.text, FontWeight.SemiBold)
                }
            }
        }
        if (rows.isEmpty() && pending > 0) items(3) { SubsSkeleton() }
        else if (rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 32.dp), contentAlignment = Alignment.Center) {
                BText(when (filter) {
                    SubsFilter.TODAY -> "No new videos today"
                    SubsFilter.SHORTS -> "No Shorts from these channels"
                    SubsFilter.LIVE -> "Nobody is live right now"
                    else -> "Couldn't load videos. Pull the tab again to retry."
                }, 13f, p.muted, maxLines = 2)
            }
        }
        if (filter == SubsFilter.SHORTS) {
            items(rows.chunked(2), key = { pair -> "s-" + pair.first().url }) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { short ->
                        Box(Modifier.weight(1f).aspectRatio(9f / 16).clip(RoundedCornerShape(12.dp))
                            .clickable { openShort(short.url) }) {
                            NetImage(short.thumbnailUrl, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                BText(short.name, 12f, Color.White, FontWeight.SemiBold, maxLines = 2)
                                if (short.viewCount >= 0) BText("${watchCount(short.viewCount)} views", 11f,
                                    Color.White.copy(alpha = .8f))
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else items(rows, key = { "v-" + it.url }, contentType = { "video" }) { VideoCard(it, 0f, open, menu) }
        if (pending > 0 && rows.isNotEmpty()) item { SubsSkeleton() }
    }
}

@Composable
private fun SubsSkeleton() {
    val p = LocalBilaiColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(p.elevated))
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(p.elevated, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(12.dp).background(p.elevated, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth(.6f).height(10.dp).background(p.elevated, RoundedCornerShape(4.dp)))
            }
        }
    }
}

@Composable
internal fun YouTab(dark: Boolean, red: Boolean, fixtureHistory: Boolean, signedIn: Boolean, accountEmail: String?,
                    toggleDark: () -> Unit, toggleAccent: () -> Unit, signOut: () -> Unit,
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
    var laterCount by remember { mutableLongStateOf(0L) }
    DisposableEffect(context) {
        val subscription = LocalPlaylistManager(NewPipeDatabase.getInstance(context))
            .playlists.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ entries ->
                laterCount = entries.firstOrNull { it.name == WatchLater.NAME }?.streamCount ?: 0L
            }, {})
        onDispose { subscription.dispose() }
    }
    var downloads by remember { mutableStateOf<DownloadManager?>(null) }
    var downloadCount by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                downloads = (binder as? DownloadManagerService.DownloadManagerBinder)?.downloadManager
            }
            override fun onServiceDisconnected(name: ComponentName) { downloads = null }
        }
        val bound = context.bindService(Intent(context, DownloadManagerService::class.java),
            conn, Context.BIND_AUTO_CREATE)
        onDispose { if (bound) context.unbindService(conn) }
    }
    LaunchedEffect(downloads) {
        val iterator = downloads?.iterator ?: return@LaunchedEffect
        while (true) {
            iterator.start()
            iterator.end()
            downloadCount = iterator.oldListSize
            delay(2000)
        }
    }
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
                WatchPill(if (signedIn) "Sign out" else "Sign in", if (signedIn) p.text else p.onAccent,
                    if (signedIn) p.elevated else p.accent) {
                    if (signedIn) signOut() else show("Sign in")
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
                ProfileRow("Downloads", "$downloadCount", Icons.Rounded.Download) { show("Offline Downloads") }
                ProfileRow("Watch Later", "$laterCount", Icons.Rounded.WatchLater) { show("Watch Later") }
                ProfileRow("Playlists", "", Icons.AutoMirrored.Rounded.PlaylistPlay) { show("Playlists") }
                ProfileRow("History", "", Icons.Rounded.History) { show("History") }
                ProfileRow("SponsorBlock auto-skip", "", Icons.Rounded.FastForward, toggle = skip, click = toggleSkip)
                ProfileRow("Return YouTube Dislike", "", Icons.Rounded.ThumbDown, toggle = dislike) {
                    dislike = !dislike; prefs.edit().putBoolean(dislikeKey, dislike).apply()
                }
                ProfileRow("Hide Shorts on Home", "", Icons.Rounded.Bolt, toggle = hideShorts, last = true,
                    click = toggleHideShorts)
            }
        }
    }
}

@Composable
private fun ProfileRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                       toggle: Boolean? = null, last: Boolean = false, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MIcon(icon, p.text, 22.dp)
            Spacer(Modifier.width(16.dp))
            BText(title, 15f, p.text, modifier = Modifier.weight(1f))
            if (toggle != null) PlayerSwitch(toggle, title, click)
            else {
                if (value.isNotEmpty()) BText(value, 13f, p.muted)
                MIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, p.muted, 20.dp)
            }
        }
        if (!last) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(1.dp).background(p.border))
    }
}
