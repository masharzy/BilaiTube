package org.schabi.newpipe.bilai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class BarItem(val label: String, val icon: ImageVector)

/** The 56dp bottom navigation bar: Home, Subscriptions, Library. */
@Composable
fun BilaiBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = remember {
        listOf(
            BarItem("Home", Icons.Filled.Home),
            BarItem("Subscriptions", Icons.Filled.Subscriptions),
            BarItem("Library", Icons.Filled.VideoLibrary),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Bilai.Surface),
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selected
            val tint = if (active) Bilai.Amber else Bilai.TextSecondary
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (active) Bilai.Amber.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
                }
                Text(text = item.label, color = tint, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
