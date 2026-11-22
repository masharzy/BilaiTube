package org.schabi.newpipe.bilai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.delay
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeHomeFeedExtractor as YtFeed
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

@Composable
internal fun BrowseScreen(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalBilaiColors.current
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(280,
        easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(Modifier.fillMaxSize().graphicsLayer {
        translationX = (1f - enter.value) * size.width * .35f
        alpha = .4f + .6f * enter.value
    }.background(p.bg).clickable(interactionSource = null, indication = null) {}) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", p.text, Color.Transparent, 44.dp, 24.dp,
                click = close)
            BText(title, 18f, p.text, FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }
        content()
    }
}

private const val SEARCH_HISTORY = "search_history"

private fun loadHistory(context: android.content.Context): List<String> =
    context.getSharedPreferences("bilai_search", android.content.Context.MODE_PRIVATE)
        .getString(SEARCH_HISTORY, "").orEmpty().split('\n').filter { it.isNotBlank() }

private fun saveHistory(context: android.content.Context, items: List<String>) {
    context.getSharedPreferences("bilai_search", android.content.Context.MODE_PRIVATE).edit()
        .putString(SEARCH_HISTORY, items.take(30).joinToString("\n")).apply()
}

@Composable
internal fun SearchPage(close: () -> Unit, play: (String, String) -> Unit,
                        onChannel: (String, String) -> Unit, onPlaylist: (String, String) -> Unit,
                        openShort: (String) -> Unit = { play(it, "") }) {
    val p = LocalBilaiColors.current
    val context = LocalContext.current
    val service = ServiceList.YouTube
    var query by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var submitted by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(loadHistory(context)) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<InfoItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<Page?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val request = remember { SerialDisposable() }
    val list = rememberLazyListState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        appear.animateTo(1f, androidx.compose.animation.core.tween(220))
        runCatching { focus.requestFocus() }
    }
    fun animatedClose() {
        keyboard?.hide()
        scope.launch { appear.animateTo(0f, androidx.compose.animation.core.tween(160)); close() }
    }
    androidx.activity.compose.BackHandler { if (submitted.isNotEmpty() && query.text != submitted) {
        query = androidx.compose.ui.text.input.TextFieldValue(submitted, androidx.compose.ui.text.TextRange(submitted.length))
    } else animatedClose() }
    val editing = query.text != submitted || submitted.isEmpty()
    fun submit(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        query = androidx.compose.ui.text.input.TextFieldValue(cleaned, androidx.compose.ui.text.TextRange(cleaned.length))
        submitted = cleaned
        history = (listOf(cleaned) + history.filterNot { it.equals(cleaned, ignoreCase = true) }).take(30)
        saveHistory(context, history)
        keyboard?.hide()
        revision++
    }
    DisposableEffect(request) { onDispose { request.dispose() } }
    LaunchedEffect(query.text, submitted) {
        if (query.text.isBlank() || query.text == submitted) { suggestions = emptyList(); return@LaunchedEffect }
        delay(200)
        val current = query.text
        val pending = ExtractorHelper.suggestionsFor(service.serviceId, current)
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({
                if (query.text == current) suggestions = it
            }, {})
        try { kotlinx.coroutines.awaitCancellation() } finally { pending.dispose() }
    }
    // YouTube's own search returns everything mixed (channels, videos, Shorts, playlists).
    val filters = remember {
        val factory = service.searchQHFactory
        listOfNotNull((0..60).asSequence().mapNotNull { runCatching { factory.getFilterItem(it) }.getOrNull() }
            .firstOrNull { it.name.equals("all", ignoreCase = true) })
    }
    fun load(page: Page?) {
        loading = true
        failed = false
        val active = submitted
        request.set(io.reactivex.rxjava3.core.Single.defer {
            if (page == null) ExtractorHelper.searchFor(service.serviceId, active, filters, emptyList())
                .map { it.relatedItems to it.nextPage }
            else ExtractorHelper.getMoreSearchItems(service.serviceId, active, filters, emptyList(), page)
                .map { it.items to it.nextPage }
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ value ->
            rows = ((if (page == null) emptyList() else rows) + value.first).distinctBy { it.url }
            continuation = value.second?.takeIf { it.url != page?.url || it.id != page?.id }
            loading = false
        }, { loading = false; failed = true }))
    }
    LaunchedEffect(submitted, revision) {
        if (submitted.isNotEmpty()) {
            request.set(null)
            rows = emptyList()
            continuation = null
            list.scrollToItem(0)
            load(null)
        }
    }
    LaunchedEffect(continuation, loading, failed, rows.size) {
        val next = continuation ?: return@LaunchedEffect
        if (loading || failed || !Page.isValid(next)) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
            if (index >= rows.size - 3 && !loading) load(next)
        }
    }
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 24.dp.toPx() }
        .background(p.bg).clickable(interactionSource = null, indication = null) {}) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", p.text, Color.Transparent, 44.dp, 24.dp,
                click = ::animatedClose)
            Row(Modifier.weight(1f).height(40.dp).clip(CircleShape).background(p.elevated)
                .graphicsLayer { scaleX = .92f + .08f * appear.value }
                .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus)
                    .onFocusChanged { if (it.isFocused && submitted.isNotEmpty() && query.text == submitted) suggestions = emptyList() },
                    textStyle = TextStyle(color = p.text, fontSize = 16.sp, fontFamily = BilaiFont), singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query.text) }),
                    decorationBox = { field -> Box {
                        if (query.text.isEmpty()) BText("Search YouTube", 16f, p.muted)
                        field()
                    } })
                if (query.text.isNotEmpty()) RoundIconButton(Icons.Rounded.Close, "Clear", p.text, Color.Transparent,
                    36.dp, 20.dp) {
                    query = androidx.compose.ui.text.input.TextFieldValue("")
                    runCatching { focus.requestFocus() }; keyboard?.show()
                }
            }
            RoundIconButton(Icons.Rounded.Search, "Search", p.text, Color.Transparent, 44.dp, 24.dp) { submit(query.text) }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (editing) {
                val typed = query.text.trim()
                val matchingHistory = history.filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) }.take(if (typed.isEmpty()) 20 else 4)
                items(matchingHistory, key = { "h-$it" }) { value ->
                    SuggestionRow(Icons.Rounded.History, value, fill = {
                        query = androidx.compose.ui.text.input.TextFieldValue("$value ", androidx.compose.ui.text.TextRange(value.length + 1))
                    }, remove = {
                        history = history - value; saveHistory(context, history)
                    }) { submit(value) }
                }
                items(suggestions.filterNot { s -> matchingHistory.any { it.equals(s, ignoreCase = true) } }, key = { "s-$it" }) { value ->
                    SuggestionRow(Icons.Rounded.Search, value, fill = {
                        query = androidx.compose.ui.text.input.TextFieldValue("$value ", androidx.compose.ui.text.TextRange(value.length + 1))
                    }) { submit(value) }
                }
                if (typed.isEmpty() && history.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MIcon(Icons.Rounded.Search, p.muted, 48.dp)
                        BText("Search for videos, Shorts and channels", 14f, p.muted)
                    }
                }
            } else {
                val shorts = rows.filterIsInstance<StreamInfoItem>().filter { it.isShortFormContent }
                var shelfShown = false
                rows.forEachIndexed { index, entry ->
                    if (entry is StreamInfoItem && entry.isShortFormContent) {
                        if (!shelfShown && shorts.isNotEmpty()) {
                            shelfShown = true
                            item(key = "shorts-shelf") { ShortsShelf(shorts, openShort) }
                        }
                        return@forEachIndexed
                    }
                    item(key = "r-" + entry.url + index, contentType = entry.javaClass.simpleName) {
                        when (entry) {
                            is StreamInfoItem -> VideoCard(entry, 0f, play) { }
                            is ChannelInfoItem -> ChannelResult(entry) { onChannel(entry.url, entry.name) }
                            is PlaylistInfoItem -> PlaylistResult(entry) { onPlaylist(entry.url, entry.name) }
                            else -> Unit
                        }
                    }
                }
                if (loading) items(if (rows.isEmpty()) 3 else 1) { ResultSkeleton() }
                if (failed) item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        WatchPill("Couldn't load · Retry", p.text, p.elevated) { load(continuation) }
                    }
                }
                if (submitted.isNotEmpty() && rows.isEmpty() && !loading && !failed) item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        BText("No results for \"$submitted\"", 14f, p.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, fill: () -> Unit,
                          remove: (() -> Unit)? = null, click: () -> Unit) {
    val p = LocalBilaiColors.current
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = click, onLongClick = { if (remove != null) confirm = true })
        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, p.text, 22.dp)
        BText(text, 15f, p.text, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        if (confirm && remove != null) RoundIconButton(Icons.Rounded.DeleteOutline, "Remove", p.text, Color.Transparent,
            40.dp, 20.dp, click = remove)
        else RoundIconButton(Icons.Rounded.NorthWest, "Fill", p.muted, Color.Transparent, 40.dp, 20.dp, click = fill)
    }
}

@Composable
private fun ShortsShelf(shorts: List<StreamInfoItem>, open: (String) -> Unit) {
    val p = LocalBilaiColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MIcon(Icons.Rounded.Bolt, p.accent, 22.dp)
            BText("Shorts", 17f, p.text, FontWeight.Bold)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shorts, key = { it.url }) { short ->
                Box(Modifier.width(150.dp).aspectRatio(9f / 16).clip(RoundedCornerShape(12.dp)).clickable { open(short.url) }) {
                    NetImage(short.thumbnailUrl, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = .8f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                        BText(short.name, 12f, Color.White, FontWeight.SemiBold, maxLines = 2)
                        if (short.viewCount >= 0) BText("${watchCount(short.viewCount)} views", 11f, Color.White.copy(alpha = .8f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelResult(channel: ChannelInfoItem, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        NetImage(channel.thumbnailUrl, Modifier.padding(horizontal = 20.dp).size(88.dp).clip(CircleShape), avatar = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BText(channel.name, 16f, p.text, FontWeight.SemiBold)
            val subs = channel.subscriberCount.takeIf { it >= 0 }?.let { "${watchCount(it)} subscribers" }
            val videos = channel.streamCount.takeIf { it >= 0 }?.let { "$it videos" }
            BText(listOfNotNull(subs, videos).joinToString(" • "), 12f, p.muted)
        }
    }
}

@Composable
private fun PlaylistResult(playlist: PlaylistInfoItem, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp))) {
            NetImage(playlist.thumbnailUrl, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon(Icons.AutoMirrored.Rounded.PlaylistPlay, Color.White, 14.dp)
                if (playlist.streamCount >= 0) BText(" ${playlist.streamCount}", 11f, Color.White, FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BText(playlist.name, 14f, p.text, FontWeight.Medium, maxLines = 2)
            BText(playlist.uploaderName.orEmpty().ifBlank { "Playlist" }, 12f, p.muted)
        }
    }
}

@Composable
private fun ResultSkeleton() {
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
internal fun ChannelPage(url: String, title: String, close: () -> Unit,
                         play: (String, String) -> Unit, playlist: (String, String) -> Unit, subscribed: Boolean, onSubscribe: () -> Unit) {
    val p = LocalBilaiColors.current
    var info by remember(url) { mutableStateOf<org.schabi.newpipe.extractor.channel.ChannelInfo?>(null) }
    var entries by remember(url) { mutableStateOf<List<InfoItem>>(emptyList()) }
    var selected by remember(url) { mutableStateOf("Videos") }
    var failed by remember(url) { mutableStateOf(false) }
    DisposableEffect(url) {
        val request = ExtractorHelper.getChannelInfo(ServiceList.YouTube.serviceId, url, false)
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({
                info = it; entries = it.relatedItems
            }, { failed = true })
        onDispose { request.dispose() }
    }
    val channel = info
    DisposableEffect(channel, selected) {
        val tab = channel?.tabs?.firstOrNull { it.contentFilters.any { name -> name.name.equals(selected, ignoreCase = true) } }
        val request = if (tab == null) null else ExtractorHelper.getChannelTab(ServiceList.YouTube.serviceId, tab, false)
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({ entries = it.relatedItems }, { failed = true })
        onDispose { request?.dispose() }
    }
    BrowseScreen(title, close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                channel?.bannerUrl?.let { NetImage(it, Modifier.fillMaxWidth().aspectRatio(4f)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetImage(channel?.avatarUrl, Modifier.size(48.dp).clip(CircleShape), avatar = true)
                    Column(Modifier.weight(1f)) {
                        FeedText(channel?.name ?: title, 16, p.text, FontWeight.Bold)
                        channel?.subscriberCount?.takeIf { it >= 0 }?.let { FeedText("$it subscribers", 12, p.muted) }
                    }
                    WatchPill(if (subscribed) "Subscribed" else "Subscribe", p.onAccent, p.accent, click = onSubscribe)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tab in listOf("Videos", "Shorts", "Playlists", "About")) {
                        WatchPill(tab, if (selected == tab) p.onAccent else p.text,
                            if (selected == tab) p.accent else p.elevated) { selected = tab }
                    }
                }
            }
            if (selected == "About") item { FeedText(channel?.description.orEmpty(), 13, p.text) }
            else if (selected == "Playlists") items(entries.filterIsInstance<PlaylistInfoItem>().distinctBy { it.url },
                key = { it.url }) { entry ->
                BrowseRow(entry.name, "Playlist", entry.thumbnailUrl) { playlist(entry.url, entry.name) }
            } else items(entries.filterIsInstance<StreamInfoItem>().filter {
                selected == "Shorts" && it.isShortFormContent || selected == "Videos" && !it.isShortFormContent
            }.distinctBy { it.url }, key = { it.url }) { entry ->
                BrowseRow(entry.name, entry.uploaderName.orEmpty(), entry.thumbnailUrl) { play(entry.url, entry.name) }
            }
            if (failed) item { FeedText("Couldn't load channel.", 12, p.muted) }
        }
    }
}

@Composable
internal fun LibraryPage(name: String, close: () -> Unit, play: (String, String) -> Unit, initial: Long? = null) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    val manager = remember(context) { LocalPlaylistManager(NewPipeDatabase.getInstance(context)) }
    var rows by remember(name) { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistMetadataEntry>>(emptyList()) }
    var active by remember(name) { mutableStateOf(initial) }
    DisposableEffect(context, name, active) {
        val stream = when (name) {
            "History" -> HistoryRecordManager(context).streamHistory
                .map { list -> list.sortedByDescending { it.accessDate }.distinctBy { it.streamId }.map { it.toStreamInfoItem() } }
            else -> active?.let { manager.getPlaylistStreams(it)
                .map { list -> list.sortedBy { it.joinIndex }.map { it.toStreamInfoItem() } } }
        }
        val data = stream?.subscribeOn(Schedulers.io())?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ rows = it }, {})
        val lists = if (name != "History") manager.playlists.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe({ result ->
                playlists = result
                if (name == "Watch Later") active = result.firstOrNull { it.name == WatchLater.NAME }?.uid
            }, {}) else null
        onDispose { data?.dispose(); lists?.dispose() }
    }
    BrowseScreen(name, close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (name == "Playlists" && active == null) {
                items(playlists, key = { it.uid }) { list ->
                    BrowseRow(list.name, "${list.streamCount} videos", list.thumbnailUrl) { active = list.uid }
                }
                if (playlists.isEmpty()) item { FeedText("No playlists yet", 12, p.muted) }
            } else {
                if (rows.isEmpty()) item { FeedText("No videos yet", 12, p.muted) }
                items(rows.distinctBy { it.url }, key = { it.url }) { video ->
                    BrowseRow(video.name, video.uploaderName.orEmpty(), video.thumbnailUrl) { play(video.url, video.name) }
                }
            }
        }
    }
}

@Composable
internal fun BrowseRow(title: String, subtitle: String, image: String?, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = click),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (image != null) NetImage(image, Modifier.width(160.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BText(title, 14f, p.text, FontWeight.Medium, maxLines = 2, lineHeight = 19f)
            if (subtitle.isNotEmpty()) BText(subtitle, 12f, p.muted)
        }
    }
}

/** A playlist read with the signed-in account's cookies (Watch Later, Liked videos, own playlists). */
@Composable
internal fun AccountPlaylistPage(playlistId: String, title: String, close: () -> Unit, play: (String, String) -> Unit) {
    val p = LocalBilaiColors.current
    var rows by remember(playlistId) { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var next by remember(playlistId) { mutableStateOf<String?>(null) }
    var loading by remember(playlistId) { mutableStateOf(true) }
    var failed by remember(playlistId) { mutableStateOf(false) }
    var reason by remember(playlistId) { mutableStateOf<String?>(null) }
    var retry by remember(playlistId) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    suspend fun load(token: String?) {
        loading = true
        failed = false
        val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val loc = org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization()
                val country = org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry()
                if (token == null && playlistId.startsWith("FE")) YtFeed.fetchFeed(playlistId, loc, country)
                else if (token == null) YtFeed.fetchPlaylist(playlistId, loc, country)
                else org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeHomeFeedExtractor
                    .fetchContinuation(token, loc, country)
            }.onFailure { reason = it.message ?: it.javaClass.simpleName
                android.util.Log.w("BilaiAccount", "loading $playlistId failed", it) }.getOrNull()
        }
        if (page == null) failed = true
        else {
            rows = (rows + page.items).distinctBy { it.url }
            next = page.continuation
        }
        loading = false
    }
    LaunchedEffect(playlistId, retry) { rows = emptyList(); load(null) }
    LaunchedEffect(next, loading, rows.size) {
        val token = next ?: return@LaunchedEffect
        if (loading) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
            if (index >= rows.size - 3 && !loading && next == token) load(token)
        }
    }
    BrowseScreen(title, close) {
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.url }) { item ->
                BrowseRow(item.name, listOfNotNull(item.uploaderName,
                    item.duration.takeIf { it > 0 }?.let { formatTime(it) }).joinToString(" • "), item.thumbnailUrl) {
                    play(item.url, item.name)
                }
            }
            when {
                loading -> items(if (rows.isEmpty()) 4 else 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(160.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(p.elevated))
                        Box(Modifier.weight(1f).height(14.dp).background(p.elevated, RoundedCornerShape(4.dp)))
                    }
                }
                failed -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BText("Couldn't load from your YouTube account.", 14f, p.text, FontWeight.SemiBold)
                        reason?.let { BText(it, 12f, p.muted, maxLines = 3) }
                        WatchPill("Retry", p.onAccent, p.accent) { retry++ }
                    }
                }
                rows.isEmpty() -> item { BText("No videos here yet", 13f, p.muted) }
            }
        }
    }
}

@Composable
internal fun RemotePlaylistPage(url: String, title: String, close: () -> Unit,
                                play: (String, String) -> Unit) {
    val p = LocalBilaiColors.current
    var rows by remember(url) { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var loading by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var next by remember(url) { mutableStateOf<Page?>(null) }
    val requests = remember(url) { SerialDisposable() }
    val list = rememberLazyListState()
    fun load(page: Page?) {
        if (loading) return
        loading = true
        failed = false
        val service = ServiceList.YouTube.serviceId
        val data = if (page == null) ExtractorHelper.getPlaylistInfo(service, url, false)
            .map { it.relatedItems to it.nextPage }
        else ExtractorHelper.getMorePlaylistItems(service, url, page).map { it.items to it.nextPage }
        requests.set(data.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (items, continuation) ->
                rows = (rows + items).distinctBy { it.url }
                next = continuation?.takeIf { it.url != page?.url || it.id != page?.id }
                loading = false
            }, { loading = false; failed = true }))
    }
    DisposableEffect(url, requests) {
        load(null)
        onDispose { requests.dispose() }
    }
    LaunchedEffect(next, loading, failed, rows.size) {
        val page = next ?: return@LaunchedEffect
        if (!Page.isValid(page) || loading || failed) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
            if (index >= rows.size - 3) load(page)
        }
    }
    BrowseScreen(title, close) {
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.url }) { item ->
                BrowseRow(item.name, item.uploaderName.orEmpty(), item.thumbnailUrl) { play(item.url, item.name) }
            }
            when {
                loading -> item { FeedText("Loading playlist…", 12, p.muted) }
                failed -> item { WatchPill("Retry playlist", p.onAccent, p.accent) { load(next) } }
                rows.isEmpty() -> item { FeedText("No videos", 12, p.muted) }
            }
        }
    }
}

/** The signed-in account's playlists followed by the playlists kept on this device. */
@Composable
internal fun AccountPlaylistsPage(close: () -> Unit, openAccount: (String, String) -> Unit, play: (String, String) -> Unit) {
    val p = LocalBilaiColors.current
    val context = LocalContext.current
    var remote by remember { mutableStateOf<List<org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeHomeFeedExtractor.AccountPlaylist>?>(null) }
    var local by remember { mutableStateOf<List<PlaylistMetadataEntry>>(emptyList()) }
    var openLocal by remember { mutableStateOf<PlaylistMetadataEntry?>(null) }
    LaunchedEffect(Unit) {
        remote = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeHomeFeedExtractor.fetchLibraryPlaylists(
                    org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization(),
                    org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry())
            }.getOrDefault(emptyList())
        }
    }
    DisposableEffect(context) {
        val sub = LocalPlaylistManager(NewPipeDatabase.getInstance(context)).playlists.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread()).subscribe({ local = it }, {})
        onDispose { sub.dispose() }
    }
    val opened = openLocal
    if (opened != null) {
        LibraryPage("Playlists", { openLocal = null }, play, initial = opened.uid)
        return
    }
    BrowseScreen("Playlists", close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BText("Your YouTube playlists", 13f, p.muted, FontWeight.Bold) }
            val lists = remote
            if (lists == null) items(3) {
                Box(Modifier.fillMaxWidth().height(90.dp).background(p.elevated, RoundedCornerShape(12.dp)))
            } else if (lists.isEmpty()) item { BText("No playlists found in your account", 13f, p.muted) }
            else items(lists, key = { "r-" + it.id }) { entry ->
                BrowseRow(entry.title, entry.count.orEmpty().ifBlank { "Playlist" }, entry.thumbnail) {
                    openAccount(entry.id, entry.title)
                }
            }
            if (local.isNotEmpty()) item {
                BText("On this device", 13f, p.muted, FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            items(local, key = { "l-" + it.uid }) { entry ->
                BrowseRow(entry.name, "${entry.streamCount} videos", entry.thumbnailUrl) { openLocal = entry }
            }
        }
    }
}
