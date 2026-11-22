package org.schabi.newpipe.bilai

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.bilai.engine.PlaybackEngine
import org.schabi.newpipe.extractor.stream.StreamInfoItem

@Composable
internal fun Watch(url: String, title: String, engine: PlaybackEngine, compact: Boolean,
                   onCompact: (Boolean) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, speed: Float, close: () -> Unit, playNext: (String, String) -> Unit,
                   overlay: (String) -> Unit) {
    val p = LocalBilaiColors.current
    val playback by engine.state.collectAsState()
    val info by engine.current.collectAsState()
    val motion = remember { PlayerMotion() }
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val pointerCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var spring by remember { mutableStateOf<Job?>(null) }
    var controls by remember { mutableStateOf(true) }
    val latestPlayback by rememberUpdatedState(playback)
    var tapJob by remember { mutableStateOf<Job?>(null) }
    var lastTap by remember(url) { mutableLongStateOf(0) }
    var seekSide by remember(url) { mutableIntStateOf(0) }
    var seekCount by remember(url) { mutableIntStateOf(0) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    fun seek(direction: Int) {
        val state = latestPlayback
        if (state.durationMs <= 0) return
        engine.seekTo((state.positionMs + direction * 10_000L).coerceIn(0, state.durationMs))
        seekCount = if (seekSide == direction && seekJob?.isActive == true) seekCount + 10 else 10
        seekSide = direction
        seekJob?.cancel()
        seekJob = scope.launch { kotlinx.coroutines.delay(750); seekCount = 0 }
    }
    val miniVisual by remember(fullscreen) { derivedStateOf { !fullscreen && motion.morph > .55f } }
    val detailsTouchable by remember { derivedStateOf { motion.morph < .2f } }
    fun settle() { spring?.cancel(); spring = scope.launch { motion.run() } }
    LaunchedEffect(url, fullscreen) { motion.opened(); settle(); controls = true }
    LaunchedEffect(compact) {
        if (compact != motion.minimized) {
            if (compact) motion.minimize(2.5f) else motion.expand(-1.4f)
            settle()
        }
    }
    LaunchedEffect(controls, playback.playing, miniVisual) {
        if (controls && playback.playing && !miniVisual) {
            kotlinx.coroutines.delay(3000)
            controls = false
        }
    }
    DisposableEffect(engine) { onDispose { engine.attachSurface(null) } }
    BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged {
        motion.resize(it.width / density, it.height / density)
    }) {
        val fullHeight = (maxWidth.value * 9 / 16).dp
        // The details and the single video view occupy distinct layers.
        val detailsVisible by remember { derivedStateOf { motion.morph < 1f / 1.45f } }
        val detailScroll = rememberScrollState()
        LaunchedEffect(url) { detailScroll.scrollTo(0) }
        if (!fullscreen && detailsVisible) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = (1 - 1.45f * motion.morph.coerceIn(0f, 1f)).coerceAtLeast(0f)
            }.background(p.bg))
            Column(Modifier.fillMaxSize().graphicsLayer {
                val t = motion.morph.coerceIn(0f, 1f)
                alpha = (1 - 1.45f * t).coerceAtLeast(0f)
                translationY = t * 90 * density
            }.padding(top = fullHeight)
                .verticalScroll(detailScroll, enabled = detailsTouchable)
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 144.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.watchClick(detailsTouchable) { overlay("Description") }) {
                    FeedText(info?.name ?: title, 17, p.text, FontWeight.Bold)
                    FeedText("${info?.viewCount?.takeIf { it >= 0 } ?: "—"} views  •  …more", 12, p.muted)
                }
                FeedText(info?.uploaderName.orEmpty(), 14, p.text, FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchPill("Float", p.accent, p.accentSoft, enabled = detailsTouchable) { onCompact(true) }
                    WatchPill("Share", p.text, p.elevated, enabled = detailsTouchable) { overlay("Share") }
                }
                Box(Modifier.fillMaxWidth().background(p.elevated, RoundedCornerShape(16.dp))
                    .watchClick(detailsTouchable) { overlay("Comments") }.padding(12.dp)) {
                    FeedText("Comments", 12, p.text, FontWeight.Bold)
                }
                FeedText("UP NEXT", 12, p.muted, FontWeight.Bold)
                info?.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>().take(25).forEach { item ->
                    Row(Modifier.fillMaxWidth().watchClick(detailsTouchable) { playNext(item.url, item.name) },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetImage(item.thumbnailUrl, Modifier.width(144.dp).aspectRatio(16f / 9))
                        FeedText(item.name, 12, p.text, FontWeight.SemiBold)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(if (fullscreen) maxHeight else fullHeight).graphicsLayer {
            val t = if (fullscreen) 0f else motion.morph.coerceIn(0f, 1.12f)
            val clamped = t.coerceAtMost(1f)
            val scale = 1 - clamped * (1 - motion.miniWidth / motion.width.coerceAtLeast(1f))
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale; scaleY = scale
            translationX = motion.x * t * density
            translationY = motion.y * t * density
            shape = RoundedCornerShape((14 * clamped / scale.coerceAtLeast(.01f)).dp)
            clip = true
            shadowElevation = if (clamped > .05f) 16f * clamped * density else 0f
        }.background(Color.Black).then(if (BuildConfig.DEBUG) Modifier.testTag("player_surface") else Modifier)) {
            AndroidView(factory = { context -> TextureView(context).also(engine::attachSurface) },
                modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().onGloballyPositioned { pointerCoordinates[0] = it }
                .pointerInput(url, density, fullscreen) {
                fun toRoot(position: Offset): Offset {
                    val coordinates = pointerCoordinates[0]
                    return if (coordinates?.isAttached == true) coordinates.localToRoot(position) else position
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    motion.down()
                    val tracker = VelocityTracker()
                    val origin = toRoot(down.position)
                    tracker.addPosition(down.uptimeMillis, origin)
                    var point = origin
                    var last = down
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        point = toRoot(change.position)
                        tracker.addPosition(change.uptimeMillis, point)
                        val delta = point - origin
                        if (change.pressed && delta.getDistance() >= 8 * density) {
                            if (!fullscreen) motion.move(delta.x / density, delta.y / density)
                            change.consume()
                        }
                    } while (last.pressed)
                    val delta = (point - origin) / density
                    val v = tracker.calculateVelocity()
                    if ((fullscreen && delta.y >= 14) || (!fullscreen && !motion.minimized && delta.y <= -14)) {
                        motion.release(0f, 0f, 0f, 0f)
                        onFullscreen(!fullscreen)
                        return@awaitEachGesture
                    }
                    if (motion.release(if (fullscreen) 0f else delta.x, if (fullscreen) 0f else delta.y, v.x / density, v.y / density)) close()
                    else {
                        if (!motion.minimized && (fullscreen || !compact) && delta.getDistance() < 8) {
                            if (last.uptimeMillis - lastTap in 1L..300L && tapJob?.isActive == true) {
                                tapJob?.cancel()
                                lastTap = 0
                                seek(if (down.position.x < size.width / 2) -1 else 1)
                            } else {
                                lastTap = last.uptimeMillis
                                tapJob?.cancel()
                                tapJob = scope.launch { kotlinx.coroutines.delay(300); controls = !controls }
                            }
                        }
                        onCompact(motion.minimized)
                        settle()
                    }
                }
            })
            if (playback.error) Box(Modifier.align(Alignment.Center)) {
                WatchPill("Couldn't play. Retry", Color.White, p.elevated) { engine.play(url) }
            }
            if (!miniVisual && controls) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)
                    .then(if (BuildConfig.DEBUG) Modifier.testTag("player_minimize") else Modifier)) {
                    WatchPill("⌄", Color.White, Color.Black.copy(alpha = .5f), if (fullscreen) "Exit fullscreen" else "Minimize") {
                        if (fullscreen) onFullscreen(false) else onCompact(true)
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    WatchPill(formatSpeed(speed), Color.White, Color.Black.copy(alpha = .55f)) { overlay("Playback speed") }
                }
                if (seekCount == 0) Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (fullscreen) WatchPill("↶ 10", Color.White, Color.Black.copy(alpha = .55f)) { seek(-1) }
                    WatchPill(if (playback.playing) "Ⅱ" else "▶", Color.White, Color.Black.copy(alpha = .55f),
                        if (playback.playing) "Pause" else "Play", click = engine::togglePlay)
                    if (fullscreen) WatchPill("10 ↷", Color.White, Color.Black.copy(alpha = .55f)) { seek(1) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .8f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        BasicText("${formatTime(playback.positionMs / 1000)} / ${formatTime(playback.durationMs / 1000)}",
                            style = TextStyle(color = Color.White, fontSize = 10.sp))
                        WatchPill("⛶", Color.White, Color.Transparent,
                            if (fullscreen) "Exit fullscreen" else "Fullscreen") { onFullscreen(!fullscreen) }
                    }
                    Box(Modifier.fillMaxWidth().height(24.dp).pointerInput(playback.durationMs) {
                        awaitEachGesture {
                            var point = awaitFirstDown()
                            val id = point.id
                            do {
                                point = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                                point.consume()
                            } while (point.pressed)
                            if (playback.durationMs > 0) engine.seekTo((point.position.x / size.width *
                                playback.durationMs).toLong().coerceIn(0L, playback.durationMs))
                        }
                    }, contentAlignment = Alignment.BottomStart) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .25f)))
                        val played = if (playback.durationMs == 0L) 0f else
                            (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth(played).height(4.dp).background(p.accent))
                    }
                }
            } else if (miniVisual) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .then(if (BuildConfig.DEBUG) Modifier.testTag("player_close") else Modifier)) {
                    WatchPill("×", Color.White, Color.Black.copy(alpha = .8f), "Close player", click = close)
                }
            }
            if (seekCount > 0 && !miniVisual) {
                Box(Modifier.align(if (seekSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight().fillMaxWidth(.42f).background(Color.White.copy(alpha = .30f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FeedText(if (seekSide < 0) "◀ ◀ ◀" else "▶ ▶ ▶", 22, Color.White)
                        FeedText("$seekCount seconds", 13, Color.White, FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

internal fun formatSpeed(speed: Float): String = String.format(java.util.Locale.ROOT, "%.2fx", speed)

@Composable
internal fun WatchPill(label: String, color: Color, bg: Color, description: String = label, enabled: Boolean = true, click: () -> Unit) {
    BasicText(label, Modifier.background(bg, CircleShape).semantics { contentDescription = description }
        .watchClick(enabled, click).padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
}

private fun Modifier.watchClick(enabled: Boolean, action: () -> Unit) =
    if (enabled) clickable(onClick = action) else this
