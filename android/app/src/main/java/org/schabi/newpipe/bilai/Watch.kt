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
                   onCompact: (Boolean) -> Unit, close: () -> Unit, playNext: (String, String) -> Unit,
                   overlay: (String) -> Unit) {
    val p = LocalBilaiColors.current
    val playback by engine.state.collectAsState()
    val info by engine.current.collectAsState()
    val motion = remember { PlayerMotion() }
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var spring by remember { mutableStateOf<Job?>(null) }
    var controls by remember { mutableStateOf(true) }
    var speed by remember { mutableIntStateOf(0) }
    val speeds = remember { floatArrayOf(1f, 1.25f, 1.5f, 2f, .75f) }
    val miniVisual by remember { derivedStateOf { motion.morph > .55f } }
    val detailsTouchable by remember { derivedStateOf { motion.morph < .2f } }
    fun settle() { spring?.cancel(); spring = scope.launch { motion.run() } }
    LaunchedEffect(url) { motion.opened(); settle(); controls = true; speed = 0 }
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
        if (detailsVisible) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = (1 - 1.45f * motion.morph.coerceIn(0f, 1f)).coerceAtLeast(0f)
            }.background(p.bg))
            Column(Modifier.fillMaxSize().graphicsLayer {
                val t = motion.morph.coerceIn(0f, 1f)
                alpha = (1 - 1.45f * t).coerceAtLeast(0f)
                translationY = t * 90 * density
            }.padding(top = fullHeight)
                .verticalScroll(rememberScrollState(), enabled = detailsTouchable)
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
        Box(Modifier.fillMaxWidth().height(fullHeight).graphicsLayer {
            val t = motion.morph.coerceIn(0f, 1.12f)
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
            Box(Modifier.fillMaxSize().pointerInput(url, density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    motion.down()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var last = down
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = change.position - down.position
                        if (change.pressed && delta.getDistance() >= 8 * density) {
                            motion.move(delta.x / density, delta.y / density)
                            change.consume()
                        }
                    } while (last.pressed)
                    val delta = (last.position - down.position) / density
                    val v = tracker.calculateVelocity()
                    if (motion.release(delta.x, delta.y, v.x / density, v.y / density)) close()
                    else {
                        if (!motion.minimized && !compact && delta.getDistance() < 8) controls = !controls
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
                    WatchPill("⌄", Color.White, Color.Black.copy(alpha = .5f), "Minimize") { onCompact(true) }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    WatchPill("${speeds[speed]}x", Color.White, Color.Black.copy(alpha = .55f)) {
                        speed = (speed + 1) % speeds.size
                        engine.setSpeed(speeds[speed])
                    }
                }
                Box(Modifier.align(Alignment.Center)) {
                    WatchPill(if (playback.playing) "Ⅱ" else "▶", Color.White, Color.Black.copy(alpha = .55f),
                        if (playback.playing) "Pause" else "Play", click = engine::togglePlay)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .8f))) {
                    BasicText("${formatTime(playback.positionMs / 1000)} / ${formatTime(playback.durationMs / 1000)}",
                        Modifier.padding(8.dp), style = TextStyle(color = Color.White, fontSize = 10.sp))
                    Box(Modifier.fillMaxWidth().height(24.dp).pointerInput(playback.durationMs) {
                        awaitEachGesture {
                            val point = awaitFirstDown()
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
        }
    }
}

@Composable
internal fun WatchPill(label: String, color: Color, bg: Color, description: String = label, enabled: Boolean = true, click: () -> Unit) {
    BasicText(label, Modifier.background(bg, CircleShape).semantics { contentDescription = description }
        .watchClick(enabled, click).padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
}

private fun Modifier.watchClick(enabled: Boolean, action: () -> Unit) =
    if (enabled) clickable(onClick = action) else this
