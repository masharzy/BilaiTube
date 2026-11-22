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

@Composable
internal fun BrowseScreen(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalBilaiColors.current
    Column(Modifier.fillMaxSize().background(p.bg).clickable(interactionSource = null, indication = null) {}) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchPill("‹", p.text, p.elevated, "Back", click = close)
            FeedText(title, 16, p.text, FontWeight.Bold)
        }
        content()
    }
}

@Composable
internal fun SearchPage(close: () -> Unit, play: (String, String) -> Unit,
                        onChannel: (String, String) -> Unit, onPlaylist: (String, String) -> Unit) {
    val p = LocalBilaiColors.current
    val service = ServiceList.YouTube
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Videos") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<InfoItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<Page?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val request = remember { SerialDisposable() }
    val list = rememberLazyListState()
    fun submit(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        query = cleaned
        submitted = cleaned
        revision++
    }
    DisposableEffect(request) { onDispose { request.dispose() } }
    LaunchedEffect(query, submitted) {
        suggestions = emptyList()
        if (query.isBlank() || query == submitted) return@LaunchedEffect
        delay(250)
        val current = query
        val pending = ExtractorHelper.suggestionsFor(service.serviceId, current)
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({
                if (query == current) suggestions = it
            }, {})
        try { kotlinx.coroutines.awaitCancellation() } finally { pending.dispose() }
    }
    val filters = remember(filter) {
        val factory = service.searchQHFactory
        listOfNotNull((0..60).asSequence().mapNotNull { runCatching { factory.getFilterItem(it) }.getOrNull() }
            .firstOrNull { it.name.equals(filter, ignoreCase = true) })
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
            val incoming = when (filter) {
                "Shorts" -> value.first.filterIsInstance<StreamInfoItem>().filter { it.isShortFormContent }
                "Videos" -> value.first.filterIsInstance<StreamInfoItem>().filterNot { it.isShortFormContent }
                "Channels" -> value.first.filterIsInstance<ChannelInfoItem>()
                "Playlists" -> value.first.filterIsInstance<PlaylistInfoItem>()
                else -> value.first
            }
            rows = ((if (page == null) emptyList() else rows) + incoming).distinctBy { it.url }
            continuation = value.second?.takeIf { it.url != page?.url || it.id != page?.id }
            loading = false
        }, { loading = false; failed = true }))
    }
    LaunchedEffect(submitted, filter, revision) {
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
    BrowseScreen("Search", close) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, { query = it }, Modifier.weight(1f).background(p.elevated, CircleShape).padding(12.dp),
                textStyle = TextStyle(color = p.text, fontSize = 14.sp), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                decorationBox = { field -> Box {
                    if (query.isEmpty()) FeedText("Search BilaiTube…", 14, p.muted)
                    field()
                } })
            WatchPill("Search", p.onAccent, p.accent) { submit(query) }
        }
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Videos", "Shorts", "Channels", "Playlists")) { option ->
                WatchPill(option, if (option == filter) p.onAccent else p.text,
                    if (option == filter) p.accent else p.elevated) { filter = option }
            }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (query != submitted && suggestions.isNotEmpty()) items(suggestions) { value ->
                BrowseRow(value, "", null) { submit(value) }
            } else {
                items(rows, key = { it.url }) { item ->
                    when (item) {
                        is StreamInfoItem -> BrowseRow(item.name, item.uploaderName.orEmpty(), item.thumbnailUrl) {
                            play(item.url, item.name)
                        }
                        is ChannelInfoItem -> BrowseRow(item.name, "Channel", item.thumbnailUrl) {
                            onChannel(item.url, item.name)
                        }
                        is PlaylistInfoItem -> BrowseRow(item.name, "Playlist", item.thumbnailUrl) {
                            onPlaylist(item.url, item.name)
                        }
                    }
                }
                if (loading) item { FeedText("Loading…", 12, p.muted) }
                if (failed) item { WatchPill("Retry", p.onAccent, p.accent) { load(continuation) } }
                if (submitted.isNotEmpty() && rows.isEmpty() && !loading && !failed) item {
                    FeedText("No results", 12, p.muted)
                }
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
internal fun LibraryPage(name: String, close: () -> Unit, play: (String, String) -> Unit) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    val manager = remember(context) { LocalPlaylistManager(NewPipeDatabase.getInstance(context)) }
    var rows by remember(name) { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistMetadataEntry>>(emptyList()) }
    var active by remember(name) { mutableStateOf<Long?>(null) }
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
    Row(Modifier.fillMaxWidth().clickable(onClick = click),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (image != null) NetImage(image, Modifier.width(144.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            FeedText(title, 12, p.text, FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) FeedText(subtitle, 11, p.muted)
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
