package org.schabi.newpipe.bilai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.schabi.newpipe.bilai.engine.DownloadOption
import org.schabi.newpipe.extractor.stream.StreamInfo
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.FinishedMission
import us.shandian.giga.get.Mission
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManagerService

// ---------------------------------------------------------------------------------------------
// Download picker (YouTube-style "Download quality" sheet)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun DownloadPickerSheet(info: StreamInfo?, options: List<DownloadOption>,
                                 dismiss: () -> Unit, start: (DownloadOption) -> Unit) {
    val p = LocalBilaiColors.current
    val video = options.filter { !it.audioOnly }
    val audio = options.filter { it.audioOnly }
    var selected by remember(options) {
        mutableStateOf(video.firstOrNull { it.label.startsWith("720") } ?: video.firstOrNull() ?: audio.firstOrNull())
    }
    AnimatedSheet(dismiss) { sheet ->
        SheetTitle("Download quality", close = sheet.close)
        if (info != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NetImage(info.thumbnailUrl,
                Modifier.width(96.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                BText(info.name, 13f, p.text, FontWeight.SemiBold, maxLines = 2)
                BText(info.uploaderName.orEmpty(), 12f, p.muted)
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            if (options.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    BText(if (info == null) "Loading video info…" else "No downloadable formats for this video",
                        13f, p.muted)
                }
            }
            if (video.isNotEmpty()) SectionLabel("Video")
            video.forEach { option -> QualityRow(option, option == selected) { selected = option } }
            if (audio.isNotEmpty()) SectionLabel("Audio")
            audio.forEach { option -> QualityRow(option, option == selected) { selected = option } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(CircleShape).background(p.elevated).clickable(onClick = sheet.close)
                .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                BText("Cancel", 14f, p.text, FontWeight.SemiBold)
            }
            val choice = selected
            Box(Modifier.weight(1f).clip(CircleShape).background(if (choice != null) p.accent else p.elevated)
                .clickable(enabled = choice != null) { choice?.let { start(it); sheet.close() } }
                .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                BText("Download", 14f, if (choice != null) p.onAccent else p.muted, FontWeight.Bold)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SectionLabel(text: String) {
    BText(text.uppercase(), 11f, LocalBilaiColors.current.muted, FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
private fun QualityRow(option: DownloadOption, selected: Boolean, click: () -> Unit) {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape)
            .background(if (selected) p.accent else Color.Transparent)
            .then(if (selected) Modifier else Modifier.background(p.elevated, CircleShape)),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).background(p.onAccent, CircleShape))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            BText(option.label, 15f, p.text, if (selected) FontWeight.SemiBold else FontWeight.Normal)
            BText(option.detail, 12f, p.muted)
        }
        if (option.bytes > 0) BText(formatBytes(option.bytes), 13f, p.muted)
    }
}

// ---------------------------------------------------------------------------------------------
// Downloads page
// ---------------------------------------------------------------------------------------------

private data class OfflineItem(
    val key: String, val title: String, val quality: String, val uri: Uri, val source: String?,
    val finished: Boolean, val running: Boolean, val failed: Boolean, val progress: Float,
    val done: Long, val total: Long, val audio: Boolean, val mission: Mission,
)

private fun describe(mission: Mission): OfflineItem? {
    val storage = mission.storage ?: return null
    val uri = storage.uri ?: return null
    val name = storage.name ?: return null
    val base = name.substringBeforeLast('.')
    val quality = Regex("\\(([^()]*)\\)\\s*$").find(base)?.groupValues?.get(1).orEmpty()
    val title = base.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "").ifBlank { base }
    val download = mission as? DownloadMission
    val total = when {
        download != null -> runCatching { download.getLength() }.getOrDefault(0L).takeIf { it > 0 } ?: download.nearLength
        else -> mission.length
    }
    val done = download?.done ?: total
    return OfflineItem(
        key = uri.toString(), title = title, quality = quality, uri = uri, source = mission.source,
        finished = mission is FinishedMission, running = download?.running == true,
        failed = download != null && download.errCode != DownloadMission.ERROR_NOTHING,
        progress = if (download == null) 1f else if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
        done = done, total = total, audio = mission.kind == 'a', mission = mission,
    )
}

@Composable
internal fun OfflineDownloads(close: () -> Unit, play: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val p = LocalBilaiColors.current
    var manager by remember { mutableStateOf<DownloadManager?>(null) }
    var entries by remember { mutableStateOf<List<OfflineItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<OfflineItem?>(null) }
    var tick by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(manager, tick) {
        val service = manager ?: return@LaunchedEffect
        val missions = service.iterator
        while (true) {
            missions.start()
            missions.end()
            entries = (0 until missions.oldListSize).mapNotNull { index ->
                missions.getItem(index).mission?.let(::describe)
            }.distinctBy { it.key }
            loaded = true
            delay(700)
        }
    }
    val active = entries.filter { !it.finished }
    val saved = entries.filter { it.finished }
    BrowseScreen("Downloads", close) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp)).background(p.elevated).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                        MIcon(Icons.Rounded.DownloadDone, p.accent, 24.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        BText("${saved.size} ${if (saved.size == 1) "video" else "videos"} saved", 15f, p.text, FontWeight.Bold)
                        BText(listOfNotNull(formatBytes(saved.sumOf { it.total }).ifBlank { null },
                            if (active.isNotEmpty()) "${active.size} in progress" else null, "Plays offline")
                            .joinToString(" · "), 12f, p.muted)
                    }
                }
            }
            if (loaded && entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(88.dp).background(p.elevated, CircleShape), contentAlignment = Alignment.Center) {
                        MIcon(Icons.Rounded.Download, p.muted, 40.dp)
                    }
                    BText("No downloads yet", 16f, p.text, FontWeight.Bold)
                    BText("Tap Download under any video to watch it later without internet.", 13f, p.muted, maxLines = 3)
                }
            }
            if (!loaded) items(3) { DownloadSkeleton() }
            if (active.isNotEmpty()) {
                item { SectionLabel("Downloading") }
                items(active, key = { it.key }) { entry ->
                    DownloadRow(entry, { menuFor = entry }) {
                        val m = entry.mission as? DownloadMission ?: return@DownloadRow
                        if (entry.running) manager?.pauseMission(m) else manager?.resumeMission(m)
                    }
                }
            }
            if (saved.isNotEmpty()) {
                item { SectionLabel("Saved") }
                items(saved, key = { it.key }) { entry ->
                    DownloadRow(entry, { menuFor = entry }) { play(entry.uri, entry.title) }
                }
            }
        }
    }
    menuFor?.let { entry ->
        AnimatedSheet({ menuFor = null }) { sheet ->
            BText(entry.title, 14f, p.text, FontWeight.SemiBold, maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (entry.finished) SheetRow(Icons.Rounded.PlayArrow, "Play") { sheet.close(); play(entry.uri, entry.title) }
            val m = entry.mission as? DownloadMission
            if (m != null) SheetRow(if (entry.running) Icons.Rounded.Pause else Icons.Rounded.Refresh,
                if (entry.running) "Pause download" else "Resume download") {
                if (entry.running) manager?.pauseMission(m) else manager?.resumeMission(m)
                sheet.close()
            }
            entry.source?.takeIf { it.startsWith("http") }?.let { url ->
                SheetRow(Icons.Rounded.Share, "Share link") {
                    sheet.close()
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                }
            }
            SheetRow(Icons.Rounded.DeleteOutline, "Delete from downloads") {
                manager?.deleteMission(entry.mission)
                tick++
                sheet.close()
            }
            Spacer(Modifier.height(12.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun DownloadRow(entry: OfflineItem, more: () -> Unit, click: () -> Unit) {
    val p = LocalBilaiColors.current
    val progress by animateFloatAsState(entry.progress, tween(500), label = "download progress")
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 16.dp, vertical = 8.dp)
        .animateContentSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(p.elevated)) {
            val id = youtubeId(entry.source)
            if (id != null) NetImage("https://i.ytimg.com/vi/$id/mqdefault.jpg", Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MIcon(if (entry.audio) Icons.Rounded.MusicNote else Icons.Rounded.Movie, p.muted, 32.dp)
            }
            if (!entry.finished) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = .6f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        MIcon(when {
                            entry.failed -> Icons.Rounded.ErrorOutline
                            entry.running -> Icons.Rounded.Pause
                            else -> Icons.Rounded.Download
                        }, Color.White, 22.dp)
                    }
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(p.accent))
                }
            } else {
                Row(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    MIcon(Icons.Rounded.DownloadDone, Color.White, 12.dp)
                    if (entry.quality.isNotEmpty()) BText(entry.quality, 10f, Color.White, FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BText(entry.title, 14f, p.text, FontWeight.Medium, maxLines = 2, lineHeight = 19f)
            val status = when {
                entry.finished -> listOf(entry.quality.ifBlank { null }, formatBytes(entry.total).ifBlank { null })
                    .filterNotNull().joinToString(" · ").ifBlank { "Saved" }
                entry.failed -> "Download failed · tap ⋮ to retry"
                entry.running -> "${(entry.progress * 100).toInt()}% · " +
                    listOf(formatBytes(entry.done), formatBytes(entry.total)).filter { it.isNotBlank() }.joinToString(" of ")
                else -> "Paused · ${(entry.progress * 100).toInt()}%"
            }
            BText(status, 12f, if (entry.failed) Color(0xFFEF4444) else p.muted)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = more), contentAlignment = Alignment.Center) {
            MIcon(Icons.Rounded.MoreVert, p.text, 20.dp, "More")
        }
    }
}

@Composable
private fun DownloadSkeleton() {
    val p = LocalBilaiColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(p.elevated))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(12.dp).background(p.elevated, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(.6f).height(10.dp).background(p.elevated, RoundedCornerShape(4.dp)))
        }
    }
}
