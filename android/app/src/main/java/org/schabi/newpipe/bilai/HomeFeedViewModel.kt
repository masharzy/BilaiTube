package org.schabi.newpipe.bilai

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

private class FeedPage(val items: List<StreamInfoItem>, val next: String?)

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
    private var continuation: String? = null

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
                    applyPage(FeedPage(home.items, home.continuation), append = false)
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
                loading = false
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

    private fun loadPage(source: Source, token: String?, append: Boolean) {
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
                loading = false
            }
        }
    }

    private fun fetch(source: Source, token: String?): FeedPage {
        val loc = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        return when (source) {
            is Source.Home ->
                if (token == null) {
                    YoutubeHomeFeedExtractor.fetchHome(loc, country).let { FeedPage(it.items, it.continuation) }
                } else {
                    YoutubeHomeFeedExtractor.fetchContinuation(token, loc, country)
                        .let { FeedPage(it.items, it.continuation) }
                }
            is Source.Token ->
                YoutubeHomeFeedExtractor.fetchContinuation(token ?: source.token, loc, country)
                    .let { FeedPage(it.items, it.continuation) }
            is Source.Query -> {
                val service = ServiceList.YouTube
                val factory = service.searchQHFactory
                // The YouTube search URL builder requires exactly one content filter; use "videos".
                val videos = (0..60).asSequence()
                    .mapNotNull { id -> runCatching { factory.getFilterItem(id) }.getOrNull() }
                    .firstOrNull { it.name == "videos" }
                val handler = if (videos != null) {
                    factory.fromQuery(source.query, listOf(videos), null)
                } else {
                    factory.fromQuery(source.query)
                }
                val info = SearchInfo.getInfo(service, handler)
                FeedPage(info.relatedItems.filterIsInstance<StreamInfoItem>(), null)
            }
            is Source.Trending -> {
                val info = KioskInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/feed/trending")
                FeedPage(info.relatedItems.filterIsInstance<StreamInfoItem>(), null)
            }
        }
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
        val shorts = ArrayList<StreamInfoItem>()
        for (item in allItems) {
            if (item.isShortFormContent) {
                if (!hideShorts) shorts.add(item)
            } else {
                result.add(FeedRow.Video(item))
            }
        }
        if (shorts.isNotEmpty()) result.add(minOf(2, result.size), FeedRow.Shorts(shorts))
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
