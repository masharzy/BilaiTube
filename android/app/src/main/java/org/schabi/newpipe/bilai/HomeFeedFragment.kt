package org.schabi.newpipe.bilai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper

/** The BilaiTube home page: category chips on top of YouTube's own dynamic home feed. */
class HomeFeedFragment : Fragment() {
    private lateinit var viewModel: HomeFeedViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel = ViewModelProvider(this)[HomeFeedViewModel::class.java]
        viewModel.hideShorts = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(KEY_HIDE_SHORTS, false)
        viewModel.start()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BilaiTheme(amoled = ThemeHelper.isBlackThemeSelected(requireContext())) {
                    HomeScreen(
                        viewModel = viewModel,
                        onVideo = ::openVideo,
                        onChannel = ::openChannel,
                        onMenu = ::openMenu,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.hideShorts = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(KEY_HIDE_SHORTS, false)
    }

    private fun openVideo(item: StreamInfoItem) {
        NavigationHelper.openVideoDetailFragment(
            requireContext(), requireActivity().supportFragmentManager,
            item.serviceId, item.url, item.name.orEmpty(), null, false)
    }

    private fun openChannel(item: StreamInfoItem) {
        val url = item.uploaderUrl
        if (!url.isNullOrEmpty()) {
            NavigationHelper.openChannelFragment(
                requireActivity().supportFragmentManager, item.serviceId, url,
                item.uploaderName.orEmpty())
        }
    }

    private fun openMenu(item: StreamInfoItem) {
        try {
            InfoItemDialog.Builder(requireActivity(), requireContext(), this, item).create().show()
        } catch (e: Exception) {
            // the dialog can't be built without an activity; nothing to show
        }
    }

    companion object {
        const val KEY_HIDE_SHORTS = "bilai_hide_shorts"
    }
}

@Composable
private fun HomeScreen(
    viewModel: HomeFeedViewModel,
    onVideo: (StreamInfoItem) -> Unit,
    onChannel: (StreamInfoItem) -> Unit,
    onMenu: (StreamInfoItem) -> Unit,
) {
    val listState = rememberLazyListState()
    val rows = viewModel.rows

    // load the next page when the end is near
    LaunchedEffect(listState, rows.size) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }.collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
        if (viewModel.chipTitles.size > 1) {
            ChipRow(
                titles = viewModel.chipTitles,
                selected = viewModel.selected,
                onSelect = viewModel::select,
            )
        }
        PullToRefreshBox(
            isRefreshing = viewModel.loading && rows.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows) { _, row ->
                    when (row) {
                        is FeedRow.Video -> VideoCard(
                            item = row.item,
                            watchedFraction = viewModel.watched[row.item.url],
                            onClick = { onVideo(row.item) },
                            onChannelClick = { onChannel(row.item) },
                            onMenuClick = { onMenu(row.item) },
                        )
                        is FeedRow.Shorts -> ShortsShelf(row.items, onClick = onVideo)
                    }
                }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        viewModel.loading -> CircularProgressIndicator(color = Bilai.Amber)
                        viewModel.error != null -> Text(
                            text = viewModel.error.orEmpty() + "\n\nPull down to retry",
                            color = Bilai.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
