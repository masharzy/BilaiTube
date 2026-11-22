package org.schabi.newpipe.bilai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.FinishedMission
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManagerService

private data class OfflineItem(val title: String, val uri: Uri, val finished: Boolean, val progress: Int?)

@Composable
internal fun OfflineDownloads(close: () -> Unit, play: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    var manager by remember { mutableStateOf<DownloadManager?>(null) }
    var entries by remember { mutableStateOf<List<OfflineItem>>(emptyList()) }
    DisposableEffect(context) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                manager = (binder as? DownloadManagerService.DownloadManagerBinder)?.downloadManager
            }
            override fun onServiceDisconnected(name: ComponentName) { manager = null }
        }
        val bound = context.bindService(Intent(context, DownloadManagerService::class.java),
            conn, Context.BIND_AUTO_CREATE)
        onDispose { if (bound) context.unbindService(conn) }
    }
    LaunchedEffect(manager) {
        val service = manager ?: return@LaunchedEffect
        val missions = service.iterator
        while (true) {
            missions.start()
            missions.end()
            entries = (0 until missions.oldListSize).mapNotNull { index ->
                val mission = missions.getItem(index).mission ?: return@mapNotNull null
                val storage = mission.storage ?: return@mapNotNull null
                val uri = storage.uri ?: return@mapNotNull null
                val done = mission is FinishedMission
                val progress = if (mission is DownloadMission && mission.length > 0)
                    ((mission.done.toDouble() / mission.length) * 100).toInt().coerceIn(0, 100) else null
                OfflineItem(storage.name, uri, done, progress)
            }
            delay(800)
        }
    }
    BrowseScreen("Offline Downloads", close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) item {
                FeedText(if (manager == null) "Loading downloads…" else "No downloads yet", 12, p.muted)
            }
            items(entries.distinctBy { it.uri }, key = { it.uri.toString() }) { entry ->
                val status = if (entry.finished) "Saved offline"
                    else entry.progress?.let { "$it% downloaded" } ?: "Queued"
                BrowseRow(entry.title, status, null) {
                    if (entry.finished) play(entry.uri, entry.title)
                }
            }
        }
    }
}
