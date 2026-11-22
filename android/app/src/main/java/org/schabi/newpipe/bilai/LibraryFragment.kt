package org.schabi.newpipe.bilai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper

/** Library: everything that lives on the device (history, playlists, downloads, settings). */
class LibraryFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            BilaiTheme(amoled = ThemeHelper.isBlackThemeSelected(requireContext())) {
                val fm = requireActivity().supportFragmentManager
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    LibraryRow(Icons.Filled.History, "History") {
                        NavigationHelper.openStatisticFragment(fm)
                    }
                    LibraryRow(Icons.Filled.WatchLater, "Watch later") {
                        WatchLater.open(requireContext(), fm)
                    }
                    LibraryRow(Icons.Filled.PlaylistPlay, "Playlists") {
                        NavigationHelper.openBookmarksFragment(fm)
                    }
                    LibraryRow(Icons.Filled.Download, "Downloads") {
                        NavigationHelper.openDownloads(requireActivity())
                    }
                    LibraryRow(Icons.Filled.Subscriptions, "Manage subscriptions") {
                        NavigationHelper.openSubscriptionFragment(fm)
                    }
                    LibraryRow(Icons.Filled.Settings, "Settings") {
                        NavigationHelper.openSettings(requireContext())
                    }
                    LibraryRow(Icons.Filled.Info, "About BilaiTube") {
                        NavigationHelper.openAbout(requireContext())
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LibraryRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Bilai.TextPrimary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(text = title, color = Bilai.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
