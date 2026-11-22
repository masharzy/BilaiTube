package org.schabi.newpipe.bilai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import org.schabi.newpipe.util.Localization
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFeed(model: HomeFeedViewModel, list: LazyListState, open: (String, String) -> Unit,
                      openShorts: () -> Unit, menu: (StreamInfoItem) -> Unit) {
    val p = LocalBilaiColors.current
    LaunchedEffect(model) { model.start() }
    val rows = model.rows
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 400.dp.roundToPx() }
    LaunchedEffect(model.selected) { list.scrollToItem(0) }
    LaunchedEffect(rows, list, model.loading, model.error) {
        if (rows.isEmpty() || model.loading || model.error != null) return@LaunchedEffect
        snapshotFlow {
            val info = list.layoutInfo
            val tail = info.visibleItemsInfo.lastOrNull { it.index == rows.size }
            tail != null && tail.offset + tail.size - info.viewportEndOffset <= threshold
        }.collect { closeToEnd -> if (closeToEnd) model.loadMore() }
    }
    val pull = rememberPullToRefreshState()
    val refreshing = model.loading && rows.isNotEmpty()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = model::refresh, state = pull,
        modifier = Modifier.fillMaxSize(), indicator = {
            PullToRefreshDefaults.Indicator(state = pull, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter), color = p.accent, containerColor = p.surface)
        }) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 80.dp)) {
            stickyHeader {
                LazyRow(Modifier.fillMaxWidth().background(p.bg),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(model.chipTitles.ifEmpty { listOf("All") }) { index, label ->
                        val selected = index == model.selected
                        BasicText(label, Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (selected) p.chipActiveBg else p.elevated)
                            .clickable { model.select(index) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = TextStyle(if (selected) p.chipActiveText else p.text,
                                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            if (model.loading && rows.isEmpty()) {
                items(3) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).skeleton(p.elevated, p.elevatedHover))
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).background(p.elevated, CircleShape))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth(.8f).height(14.dp).background(p.elevated))
                                Box(Modifier.fillMaxWidth(.5f).height(12.dp).background(p.elevated))
                            }
                        }
                    }
                }
            } else if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FeedText("Couldn't load.", 14, p.text)
                        BasicText("Retry", Modifier.clickable(onClick = model::refresh).padding(12.dp),
                            style = TextStyle(color = p.accent, fontSize = 14.sp))
                    }
                }
            }
            itemsIndexed(rows, key = { index, row -> when (row) {
                is FeedRow.Video -> row.item.url
                is FeedRow.Shorts -> "shorts:$index"
            } }, contentType = { _, row -> row is FeedRow.Shorts }) { _, row ->
                when (row) {
                    is FeedRow.Video -> VideoCard(row.item, model.watched[row.item.url] ?: 0f, open, menu)
                    is FeedRow.Shorts -> Column(Modifier.padding(vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            FeedText("ϟ  Shorts", 14, p.accent, FontWeight.Bold)
                            BasicText("View all", Modifier.clickable(onClick = openShorts),
                                style = TextStyle(color = p.accent, fontSize = 12.sp))
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(row.items) { _, short ->
                                Box(Modifier.width(144.dp).aspectRatio(9f / 15).clip(RoundedCornerShape(12.dp))
                                    .clickable { openShorts() }) {
                                    NetImage(short.thumbnailUrl, Modifier.fillMaxSize())
                                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                        .background(Color.Black.copy(alpha = .8f)).padding(8.dp)) {
                                        FeedText(short.name, 11, Color.White, FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (rows.isNotEmpty() && model.loading) items(2, contentType = { "paging-skeleton" }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).skeleton(p.elevated, p.elevatedHover))
            }
            if (rows.isNotEmpty() && model.error != null) item {
                BasicText("Retry loading more", Modifier.clickable(onClick = model::loadMore).padding(16.dp),
                    style = TextStyle(color = p.accent, fontSize = 14.sp))
            }
        }
    }
}

@Composable
internal fun VideoCard(video: StreamInfoItem, progress: Float, open: (String, String) -> Unit,
                      menu: (StreamInfoItem) -> Unit) {
    val p = LocalBilaiColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { open(video.url, video.name) }.padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9)) {
            NetImage(video.thumbnailUrl, Modifier.fillMaxSize())
            if (video.duration > 0) {
                BasicText(formatTime(video.duration), Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.Black.copy(alpha = .85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp).background(p.accent))
        }
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(p.elevated), contentAlignment = Alignment.Center) {
                if (video.uploaderAvatarUrl.isNullOrEmpty()) FeedText(video.uploaderName.orEmpty().take(2).uppercase(), 12, p.text)
                else NetImage(video.uploaderAvatarUrl, Modifier.fillMaxSize(), avatar = true)
            }
            Column(Modifier.weight(1f)) {
                BasicText(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = p.text, fontSize = 14.5.sp,
                        lineHeight = 19.94.sp, fontWeight = FontWeight.SemiBold))
                FeedText(listOfNotNull(video.uploaderName,
                    video.viewCount.takeIf { it >= 0 }?.let { Localization.localizeViewCount(context, it) },
                    video.textualUploadDate).joinToString(" • "), 12, p.muted)
            }
            BasicText("⋮", Modifier.clickable { menu(video) }.padding(4.dp),
                style = TextStyle(color = p.text, fontSize = 16.sp))
        }
    }
}

@Composable
internal fun FeedText(text: String, size: Int, color: Color, weight: FontWeight = FontWeight.Normal) {
    BasicText(text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, fontFamily = BilaiFont))
}

internal fun formatTime(seconds: Long): String = if (seconds >= 3600)
    String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)

@Composable
private fun Modifier.skeleton(base: Color, highlight: Color): Modifier {
    val phase = rememberInfiniteTransition(label = "feed shimmer").animateFloat(
        initialValue = -.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmer phase",
    )
    return drawBehind {
        val x = size.width * phase.value
        drawRect(Brush.linearGradient(listOf(base, highlight, base),
            Offset(x - size.width * .3f, 0f), Offset(x + size.width * .3f, size.height)))
    }
}
