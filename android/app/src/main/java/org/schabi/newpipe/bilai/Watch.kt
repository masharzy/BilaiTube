package org.schabi.newpipe.bilai

import android.view.TextureView
import android.graphics.Matrix
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.util.ExtractorHelper
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
                   onCompact: (Boolean) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, speed: Float,
                   captionsOn: Boolean, toggleCaptions: () -> Unit,
                   autoplay: Boolean, toggleAutoplay: () -> Unit, close: () -> Unit, playNext: (String, String) -> Unit,
                   overlay: (String) -> Unit) {
    val p = LocalBilaiColors.current
    val playback by engine.state.collectAsState()
    val info by engine.current.collectAsState()
    val related = remember(info) { info?.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>() }
    val next = related.firstOrNull()
    var more by remember(url) { mutableStateOf<List<StreamInfoItem>>(emptyList()) }
    var continuation by remember(url) { mutableStateOf<Page?>(null) }
    var searched by remember(url) { mutableStateOf(false) }
    var exhausted by remember(url) { mutableStateOf(false) }
    var loadingMore by remember(url) { mutableStateOf(false) }
    val recommendations = remember(url, related, more) {
        (related + more).filter { it.url != url }.distinctBy { it.url }
    }
    var autoplayCancelled by remember(url) { mutableStateOf(false) }
    var countdown by remember(url) { mutableIntStateOf(5) }
    LaunchedEffect(url, playback.ended, autoplay, autoplayCancelled, next?.url) {
        if (playback.ended && autoplay && !autoplayCancelled && next != null) {
            for (seconds in 5 downTo 1) {
                countdown = seconds
                kotlinx.coroutines.delay(1000)
            }
            playNext(next.url, next.name)
        }
    }
    val motion = remember { PlayerMotion() }
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var adjustment by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(adjustment) { if (adjustment != null) { kotlinx.coroutines.delay(800); adjustment = null } }
    val scope = rememberCoroutineScope()
    val latestPlayback by rememberUpdatedState(playback)
    val pointerCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val matrix = remember { Matrix() }
    val fitVideo by rememberUpdatedState(fullscreen)
    fun resizeTexture(view: TextureView) {
        val state = latestPlayback
        if (state.videoWidth <= 0 || state.videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
        val available = view.width.toFloat() / view.height
        val videoAspect = state.videoWidth.toFloat() / state.videoHeight
        val ratio = videoAspect / available
        val x = if (fitVideo) ratio.coerceAtMost(1f) else ratio.coerceAtLeast(1f)
        val y = if (fitVideo) (1f / ratio).coerceAtMost(1f) else (1f / ratio).coerceAtLeast(1f)
        matrix.setScale(x, y, view.width / 2f, view.height / 2f)
        view.setTransform(matrix)
    }
    var spring by remember { mutableStateOf<Job?>(null) }
    var controls by remember { mutableStateOf(true) }
    var tapJob by remember { mutableStateOf<Job?>(null) }
    var lastTap by remember(url) { mutableLongStateOf(0) }
    var seekSide by remember(url) { mutableIntStateOf(0) }
    var seekCount by remember(url) { mutableIntStateOf(0) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var seekPoint by remember(url) { mutableStateOf<Offset?>(null) }
    var seekFlash by remember(url) { mutableIntStateOf(0) }
    var lastSeekAt by remember(url) { mutableLongStateOf(0L) }
    var pendingSeekPosition by remember(url) { mutableLongStateOf(0L) }
    fun seek(direction: Int, finger: Offset? = null) {
        val state = latestPlayback
        if (state.durationMs <= 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val withinSequence = now - lastSeekAt < 700 && seekJob?.isActive == true
        val base = if (withinSequence) pendingSeekPosition else state.positionMs
        pendingSeekPosition = (base + direction * 10_000L).coerceIn(0, state.durationMs)
        engine.seekTo(pendingSeekPosition)
        seekCount = if (seekSide == direction && withinSequence) seekCount + 10 else 10
        lastSeekAt = now
        seekPoint = finger
        seekFlash++
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
        val detailScroll = rememberLazyListState()
        LaunchedEffect(url) { detailScroll.scrollToItem(0) }
        LaunchedEffect(url, info, detailScroll, recommendations.size) {
            val query = info?.name.orEmpty().trim()
            if (query.isEmpty()) return@LaunchedEffect
            snapshotFlow { detailScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { index ->
                if (index < recommendations.size - 4 || exhausted || loadingMore) return@collect
                loadingMore = true
                try {
                    val page = withContext(Dispatchers.IO) {
                        val service = ServiceList.YouTube.serviceId
                        val token = continuation
                        if (!searched) {
                            val result = ExtractorHelper.searchFor(service, query, emptyList(), emptyList()).blockingGet()
                            result.relatedItems to result.nextPage
                        } else if (Page.isValid(token)) {
                            val result = ExtractorHelper.getMoreSearchItems(service, query, emptyList(), emptyList(), token).blockingGet()
                            result.items to result.nextPage
                        } else emptyList<org.schabi.newpipe.extractor.InfoItem>() to null
                    }
                    searched = true
                    continuation = page.second
                    exhausted = !Page.isValid(page.second)
                    more += page.first.filterIsInstance<StreamInfoItem>()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    exhausted = true
                } finally {
                    loadingMore = false
                }
            }
        }
        if (!fullscreen && detailsVisible) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = (1 - 1.45f * motion.morph.coerceIn(0f, 1f)).coerceAtLeast(0f)
            }.background(p.bg))
            LazyColumn(Modifier.fillMaxSize().graphicsLayer {
                val t = motion.morph.coerceIn(0f, 1f)
                alpha = (1 - 1.45f * t).coerceAtLeast(0f)
                translationY = t * 90 * density
            }.padding(top = fullHeight),
                state = detailScroll, userScrollEnabled = detailsTouchable,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 144.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.watchClick(detailsTouchable) { overlay("Description") }) {
                    FeedText(info?.name ?: title, 17, p.text, FontWeight.Bold)
                    FeedText("${info?.viewCount?.takeIf { it >= 0 } ?: "—"} views  •  …more", 12, p.muted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.watchClick(detailsTouchable) { overlay("Channel") }) {
                        FeedText(info?.uploaderName.orEmpty(), 14, p.text, FontWeight.SemiBold)
                    }
                    if (info != null) WatchPill("Subscribe", p.onAccent, p.accent, enabled = detailsTouchable) {
                        overlay("Sign in to continue")
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchPill("Like", p.text, p.elevated, enabled = detailsTouchable) { overlay("Sign in to continue") }
                    WatchPill("Dislike", p.text, p.elevated, enabled = detailsTouchable) { overlay("Sign in to continue") }
                    WatchPill("Float", p.accent, p.accentSoft, enabled = detailsTouchable) { onCompact(true) }
                    WatchPill("Share", p.text, p.elevated, enabled = detailsTouchable) { overlay("Share") }
                    if (info != null) WatchPill("Download", p.text, p.elevated, enabled = detailsTouchable) {
                        overlay("Download")
                    }
                }
                Box(Modifier.fillMaxWidth().background(p.elevated, RoundedCornerShape(16.dp))
                    .watchClick(detailsTouchable) { overlay("Comments") }.padding(12.dp)) {
                    FeedText("Comments", 12, p.text, FontWeight.Bold)
                }
                FeedText("UP NEXT", 12, p.muted, FontWeight.Bold)
                }
                }
                items(recommendations, key = { it.url }) { item ->
                    Row(Modifier.fillMaxWidth().watchClick(detailsTouchable) { playNext(item.url, item.name) },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetImage(item.thumbnailUrl, Modifier.width(144.dp).aspectRatio(16f / 9))
                        Column {
                            FeedText(item.name, 12, p.text, FontWeight.SemiBold)
                            FeedText(item.uploaderName.orEmpty(), 11, p.muted)
                        }
                    }
                }
                if (loadingMore) items(3) {
                    Spacer(Modifier.fillMaxWidth().height(81.dp).background(p.elevated, RoundedCornerShape(12.dp)))
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
            val videoWidth = playback.videoWidth
            val videoHeight = playback.videoHeight
            AndroidView(factory = { context ->
                TextureView(context).also { texture ->
                    texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeTexture(texture) }
                    engine.attachSurface(texture)
                }
            }, modifier = Modifier.fillMaxSize(), update = { texture ->
                if (videoWidth > 0 && videoHeight > 0) resizeTexture(texture)
            })
            Box(Modifier.fillMaxSize().onGloballyPositioned { pointerCoordinates[0] = it }
                .pointerInput(url, density, fullscreen) {
                fun toRoot(position: Offset): Offset {
                    val coordinates = pointerCoordinates[0]
                    return if (coordinates?.isAttached == true) coordinates.localToRoot(position) else position
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val isMini = !fullscreen && motion.morph >= .5f
                    val zone = if (isMini) 0 else when {
                        down.position.x < size.width * .2f -> -1
                        down.position.x > size.width * .8f -> 1
                        else -> 0
                    }
                    val canMorph = zone == 0 && (isMini || fullscreen || down.position.y < size.height * .6f)
                    val startBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: .5f
                    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (canMorph) motion.down()
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
                            when {
                                zone < 0 && abs(delta.y) > abs(delta.x) -> {
                                    val value = (startBrightness - delta.y / size.height).coerceIn(.02f, 1f)
                                    window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
                                    adjustment = "Brightness · ${(value * 100).roundToInt()}%"
                                }
                                zone > 0 && abs(delta.y) > abs(delta.x) -> {
                                    val value = (startVolume - delta.y * maxVolume / size.height)
                                        .roundToInt().coerceIn(0, maxVolume)
                                    if (value != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                    }
                                    adjustment = "Volume · ${value * 100 / maxVolume}%"
                                }
                                canMorph && !fullscreen -> motion.move(delta.x / density, delta.y / density)
                            }
                            change.consume()
                        }
                    } while (last.pressed)
                    val delta = (point - origin) / density
                    val v = tracker.calculateVelocity()
                    if (canMorph && ((fullscreen && delta.y >= 14) || (!fullscreen && !isMini && delta.y <= -14))) {
                        motion.release(0f, 0f, 0f, 0f)
                        onFullscreen(!fullscreen)
                        return@awaitEachGesture
                    }
                    if (!canMorph && delta.getDistance() >= 8) return@awaitEachGesture
                    if (canMorph && motion.release(if (fullscreen) 0f else delta.x, if (fullscreen) 0f else delta.y, v.x / density, v.y / density)) close()
                    else {
                        if (!motion.minimized && (fullscreen || !compact) && delta.getDistance() < 8) {
                            if (last.uptimeMillis - lastTap in 1L..300L && tapJob?.isActive == true) {
                                tapJob?.cancel()
                                lastTap = 0
                                seek(if (down.position.x < size.width / 2) -1 else 1, down.position)
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
            adjustment?.let { label ->
                BasicText(label, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .75f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = TextStyle(color = Color.White, fontSize = 12.sp))
            }
            if (playback.error) Box(Modifier.align(Alignment.Center)) {
                WatchPill("Couldn't play. Retry", Color.White, p.elevated) { val uri = android.net.Uri.parse(url)
                    if (uri.scheme in listOf("file", "content")) engine.playOffline(uri) else engine.play(url)
                }
            }
            if (!miniVisual && controls) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)
                    .then(if (BuildConfig.DEBUG) Modifier.testTag("player_minimize") else Modifier)) {
                    WatchPill("⌄", Color.White, Color.Black.copy(alpha = .5f), if (fullscreen) "Exit fullscreen" else "Minimize") {
                        if (fullscreen) onFullscreen(false) else onCompact(true)
                    }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WatchPill(if (autoplay) "▶" else "Ⅱ", Color.White,
                        if (autoplay) p.accent else Color.Black.copy(alpha = .55f), "Autoplay", click = toggleAutoplay)
                    if (playback.subtitles.isNotEmpty()) WatchPill("CC", Color.White,
                        if (captionsOn) p.accent else Color.Black.copy(alpha = .55f), "Captions", click = toggleCaptions)
                    WatchPill("⚙", Color.White, Color.Black.copy(alpha = .55f), "Player settings") { overlay("Player settings") }
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
                        WatchPill(formatSpeed(speed), Color.White, Color.Transparent) { overlay("Player settings") }
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
            if (playback.caption.isNotEmpty() && !miniVisual) {
                BasicText(playback.caption,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 64.dp else 12.dp)
                        .background(Color.Black.copy(alpha = .75f), RoundedCornerShape(4.dp)).padding(4.dp),
                    style = TextStyle(color = Color.White, fontSize = if (fullscreen) 18.sp else 13.sp))
            }
            if (playback.ended && !miniVisual) {
                Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .85f)),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (autoplay && next != null && !autoplayCancelled) {
                        FeedText("Up next · $countdown", 14, Color.White, FontWeight.Bold)
                        FeedText(next.name, 14, Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchPill("Cancel", p.text, p.elevated) { autoplayCancelled = true }
                            WatchPill("Play now", p.onAccent, p.accent) { playNext(next.url, next.name) }
                        }
                    } else WatchPill("Replay", p.onAccent, p.accent, click = engine::replay)
                }
            }
            if (seekCount > 0 && !miniVisual) {
                SeekFeedback(seekSide, seekCount, seekFlash, seekPoint)
            }

        }
    }
}

@Composable
private fun SeekFeedback(side: Int, seconds: Int, flash: Int, finger: Offset?) {
    val fade = remember(side) { Animatable(0f) }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(side) { fade.animateTo(1f, tween(160)) }
    LaunchedEffect(flash) {
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(550, easing = LinearEasing))
    }
    val triangles = remember { List(3) { Path() } }
    val pulse = rememberInfiniteTransition(label = "seek chevrons").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val zone = size.width * .42f
            val left = side < 0
            drawOval(Color.White.copy(alpha = .30f * fade.value),
                topLeft = Offset(if (left) -zone else size.width - zone, 0f),
                size = Size(zone * 2, size.height))
            val middle = Offset(if (left) zone * .45f else size.width - zone * .45f, size.height * .44f)
            val triangleSize = 22.dp.toPx()
            repeat(3) { i ->
                val center = middle.x + (i - 1) * triangleSize
                val phase = (pulse.value + 1f - i / 6f) % 1f
                val opacity = .2f + .8f * (1f - abs(phase * 2 - 1f))
                val triangle = triangles[i].apply {
                    reset()
                    moveTo(center + side * triangleSize * .4f, middle.y)
                    lineTo(center - side * triangleSize * .3f, middle.y - triangleSize * .35f)
                    lineTo(center - side * triangleSize * .3f, middle.y + triangleSize * .35f)
                    close()
                }
                drawPath(triangle, Color.White.copy(alpha = opacity * fade.value))
            }
            if (ripple.value < 1f) drawCircle(Color.White.copy(alpha = .28f * (1 - ripple.value)),
                radius = 30.dp.toPx() * (.3f + 4.7f * ripple.value), center = finger ?: middle)
        }
        Box(Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxWidth(.42f).padding(top = 20.dp), contentAlignment = Alignment.Center) {
            FeedText("$seconds seconds", 13, Color.White, FontWeight.SemiBold)
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
