package org.schabi.newpipe.bilai

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.util.ExtractorHelper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.App
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeHomeFeedExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager

/** One entry in the feed list: a single video or a shelf of Shorts. */
sealed class FeedRow {
    data class Video(val item: StreamInfoItem) : FeedRow()
    data class Shorts(val items: List<StreamInfoItem>) : FeedRow()
}

private sealed class Source {
    object Home : Source()
    data class Token(val token: String) : Source()
    data class Query(val query: String) : Source()
    object Trending : Source()
}

private data class FeedChip(val title: String, val source: Source)

private sealed interface FeedCursor {
    data class Home(val token: String) : FeedCursor
    data class Search(val source: Source.Query, val page: Page?) : FeedCursor
}

private class FeedPage(val items: List<StreamInfoItem>, val next: FeedCursor?)

/**
 * State of the home feed. Videos, chips and Shorts all come from YouTube's own home feed
 * (loaded on the device). If YouTube returns nothing for a signed out session, the screen falls
 * back to Trending plus a set of category searches so it is never empty.
 */
class HomeFeedViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private var chipList: List<FeedChip> = emptyList()
    private var allItems: List<StreamInfoItem> = emptyList()
    private var continuation: FeedCursor? = null
    private var generation = 0

    var chipTitles by mutableStateOf<List<String>>(emptyList())
        private set
    var selected by mutableIntStateOf(0)
        private set
    var rows by mutableStateOf<List<FeedRow>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var shortItems by mutableStateOf<List<StreamInfoItem>>(emptyList())
        private set
    val watched = mutableStateMapOf<String, Float>()

    var hideShorts: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                rebuildRows()
            }
        }

    private var started = false

    fun start() {
        if (started) return
        started = true
        bootstrap()
    }

    /** The account changed (sign in / out): start over with the new session. */
    fun restart() {
        chipList = emptyList()
        chipTitles = emptyList()
        allItems = emptyList()
        rows = emptyList()
        continuation = null
        selected = 0
        started = true
        bootstrap()
    }

    fun refresh() {
        started = true
        if (chipList.isEmpty()) bootstrap() else loadSource(chipList[selected].source, append = false)
    }

    fun select(index: Int) {
        if (index == selected || index !in chipList.indices) return
        selected = index
        loadSource(chipList[index].source, append = false)
    }

    fun loadMore() {
        val next = continuation ?: return
        if (loading) return
        val source = chipList.getOrNull(selected)?.source ?: return
        loadPage(source, next, append = true)
    }

    private fun bootstrap() {
        val request = ++generation
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val home = withContext(Dispatchers.IO) {
                    YoutubeHomeFeedExtractor.fetchHome(
                        NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry())
                }
                if (home.items.isNotEmpty()) {
                    chipList = buildList {
                        add(FeedChip("All", Source.Home))
                        home.chips.forEach { chip ->
                            val token = chip.token
                            if (!chip.selected && token != null) add(FeedChip(chip.title, Source.Token(token)))
                        }
                    }
                    publishChips()
                    selected = 0
                    applyPage(FeedPage(home.items, home.continuation?.let { FeedCursor.Home(it) }
                        ?: FeedCursor.Search(Source.Query("trending videos today"), null)), append = false)
                } else {
                    android.util.Log.w("BilaiFeed", "home feed returned no items; using fallback")
                    useFallback()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BilaiFeed", "home feed failed: $e")
                try {
                    useFallback()
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    error = inner.message ?: e.message ?: "Couldn't load videos"
                }
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    private suspend fun useFallback() {
        chipList = listOf(
            FeedChip("Trending", Source.Query("trending videos today")),
            FeedChip("Music", Source.Query("music")),
            FeedChip("Gaming", Source.Query("gaming")),
            FeedChip("Live", Source.Query("live now")),
            FeedChip("News", Source.Query("news today")),
            FeedChip("Sports", Source.Query("sports highlights")),
            FeedChip("Podcasts", Source.Query("podcast")),
            FeedChip("Learning", Source.Query("learn")),
        )
        publishChips()
        selected = 0
        val page = withContext(Dispatchers.IO) { fetch(chipList[0].source, null) }
        applyPage(page, append = false)
    }

    private fun publishChips() {
        chipTitles = chipList.map { it.title }
    }

    private fun loadSource(source: Source, append: Boolean) = loadPage(source, null, append)

    private fun loadPage(source: Source, token: FeedCursor?, append: Boolean) {
        val request = ++generation
        job?.cancel()
        loading = true
        error = null
        if (!append) {
            allItems = emptyList()
            rows = emptyList()
            continuation = null
        }
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { fetch(source, token) }
                applyPage(page, append)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load videos"
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    private fun fetch(source: Source, token: FeedCursor?): FeedPage {
        if (token is FeedCursor.Search) return searchPage(token.source, token.page)
        val homeToken = (token as? FeedCursor.Home)?.token
        val loc = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        fun homePage(items: List<StreamInfoItem>, next: String?, fallback: String): FeedPage =
            FeedPage(items, next?.takeUnless { it == homeToken }?.let { FeedCursor.Home(it) }
                ?: FeedCursor.Search(Source.Query(fallback), null))
        return when (source) {
            is Source.Home -> {
                val page = if (homeToken == null) YoutubeHomeFeedExtractor.fetchHome(loc, country)
                    else YoutubeHomeFeedExtractor.fetchContinuation(homeToken, loc, country)
                homePage(page.items, page.continuation, "trending videos today")
            }
            is Source.Token -> {
                val page = YoutubeHomeFeedExtractor.fetchContinuation(homeToken ?: source.token, loc, country)
                val title = chipList.firstOrNull { it.source == source }?.title ?: "trending videos today"
                homePage(page.items, page.continuation, title)
            }
            is Source.Query -> searchPage(source, null)
            is Source.Trending -> {
                val info = KioskInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/feed/trending")
                FeedPage(info.relatedItems.filterIsInstance<StreamInfoItem>(),
                    FeedCursor.Search(Source.Query("trending videos today"), null))
            }
        }
    }

    private fun searchPage(source: Source.Query, token: Page?): FeedPage {
        val service = ServiceList.YouTube
        val factory = service.searchQHFactory
        val videos = (0..60).asSequence()
            .mapNotNull { id -> runCatching { factory.getFilterItem(id) }.getOrNull() }
            .firstOrNull { it.name == "videos" }
        val filters = listOfNotNull(videos)
        val results = if (token == null) {
            val handler = if (videos != null) factory.fromQuery(source.query, filters, null)
                else factory.fromQuery(source.query)
            val info = SearchInfo.getInfo(service, handler)
            info.relatedItems to info.nextPage
        } else {
            val info = ExtractorHelper.getMoreSearchItems(service.serviceId, source.query,
                filters, emptyList(), token).blockingGet()
            info.items to info.nextPage
        }
        return FeedPage(results.first.filterIsInstance<StreamInfoItem>(),
            results.second?.takeIf { Page.isValid(it) && (it.url != token?.url || it.id != token?.id) }
                ?.let { FeedCursor.Search(source, it) })
    }

    private suspend fun applyPage(page: FeedPage, append: Boolean) {
        val known = if (append) allItems.map { it.url }.toHashSet() else HashSet()
        val fresh = page.items.filter { known.add(it.url) }
        allItems = if (append) allItems + fresh else fresh
        continuation = page.next
        rebuildRows()
        if (fresh.isNotEmpty()) loadProgress(fresh)
    }

    private fun rebuildRows() {
        val result = ArrayList<FeedRow>()
        shortItems = allItems.filter { it.isShortFormContent }
        val shorts = ArrayList<StreamInfoItem>()
        for (item in allItems) {
            if (item.isShortFormContent) {
                if (!hideShorts) shorts.add(item)
            } else {
                result.add(FeedRow.Video(item))
            }
        }
        shorts.chunked(8).forEachIndexed { index, group ->
            val insertion = 2 + index * 13
            if (index == 0 || insertion <= result.size) {
                result.add(minOf(insertion, result.size), FeedRow.Shorts(group))
            }
        }
        rows = result
        if (result.isEmpty() && allItems.isNotEmpty() && continuation != null) loadMore()
    }

    private suspend fun loadProgress(items: List<StreamInfoItem>) {
        val states = withContext(Dispatchers.IO) {
            try {
                HistoryRecordManager(App.getApp()).loadStreamStateBatch(items as List<InfoItem>).blockingGet()
            } catch (e: Exception) {
                null
            }
        } ?: return
        items.forEachIndexed { index, item ->
            val state = states.getOrNull(index) ?: return@forEachIndexed
            if (item.duration > 0) {
                watched[item.url] = state.progressMillis / (item.duration * 1000f)
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
