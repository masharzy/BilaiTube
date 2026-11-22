package org.schabi.newpipe.bilai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.Localization

/** Horizontally scrolling category chips. */
@Composable
fun ChipRow(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(titles) { index, title ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Bilai.Amber else Bilai.Pill)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = if (active) Bilai.OnAmber else Bilai.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, background: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** An edge-to-edge YouTube style video card. */
@Composable
fun VideoCard(
    item: StreamInfoItem,
    watchedFraction: Float?,
    onClick: () -> Unit,
    onChannelClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            NetImage(item.thumbnailUrl, Modifier.fillMaxSize())
            if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
                Badge("LIVE", Bilai.Live, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            } else if (item.duration > 0) {
                Badge(
                    Localization.getDurationString(item.duration),
                    Color(0xCC000000),
                    Modifier.align(Alignment.BottomEnd).padding(8.dp),
                )
            }
            if (watchedFraction != null && watchedFraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000)),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(watchedFraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Bilai.Amber),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 0.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onChannelClick),
            ) {
                NetImage(item.uploaderAvatarUrl, Modifier.fillMaxSize(), avatar = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name.orEmpty(),
                    color = Bilai.TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metadataLine(context, item),
                    color = Bilai.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Bilai.TextSecondary)
            }
        }
    }
}

fun metadataLine(context: android.content.Context, item: StreamInfoItem): String {
    val parts = ArrayList<String>(3)
    if (!item.uploaderName.isNullOrEmpty()) parts.add(item.uploaderName)
    if (item.viewCount >= 0) parts.add(Localization.shortViewCount(context, item.viewCount))
    if (!item.textualUploadDate.isNullOrEmpty()) parts.add(item.textualUploadDate)
    return parts.joinToString(" • ")
}

/** The "Shorts" shelf shown between videos. */
@Composable
fun ShortsShelf(
    items: List<StreamInfoItem>,
    onClick: (StreamInfoItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "Shorts",
            color = Bilai.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.url }) { item ->
                ShortCard(item, onClick = { onClick(item) })
            }
        }
    }
}

@Composable
fun ShortCard(item: StreamInfoItem, onClick: () -> Unit, modifier: Modifier = Modifier.width(150.dp)) {
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        NetImage(item.thumbnailUrl, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xCC000000),
                    ),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(
                text = item.name.orEmpty(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.viewCount >= 0) {
                Text(
                    text = Localization.shortViewCount(LocalContext.current, item.viewCount),
                    color = Color(0xFFE7E5E4),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
