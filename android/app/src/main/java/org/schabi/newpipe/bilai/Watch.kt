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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.localization.DateWrapper
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Brush

@Composable
internal fun Watch(url: String, title: String, engine: PlaybackEngine, compact: Boolean,
                   onCompact: (Boolean) -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, speed: Float, pictureInPicture: Boolean,
                   captionsOn: Boolean, toggleCaptions: () -> Unit,
                   autoplay: Boolean, toggleAutoplay: () -> Unit, liked: Boolean, disliked: Boolean,
                   subscribed: Boolean, close: () -> Unit, playNext: (String, String) -> Unit,
                   autoplayNext: (String, String) -> Unit, overlay: (String) -> Unit) {
    val p = LocalBilaiColors.current
    val playback by engine.state.collectAsState()
    val info by engine.current.collectAsState()
    var preview by remember(url) { mutableStateOf<CommentsInfoItem?>(null) }
    var commentCount by remember(url) { mutableLongStateOf(-1L) }
    DisposableEffect(url, info?.id) {
        val request = if (BuildConfig.DEBUG && info?.id == "fixture0000") {
            preview = WatchReviewFixture.comments.first()
            commentCount = 2_900
            null
        } else if (info != null && url.startsWith("https://")) {
            ExtractorHelper.getCommentsInfo(ServiceList.YouTube.serviceId, url, false)
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe({ page -> preview = page.relatedItems.firstOrNull() }, {})
        } else null
        onDispose { request?.dispose() }
    }
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
    val countdown = remember(url) { Animatable(1f) }
    LaunchedEffect(url, playback.ended, autoplay, autoplayCancelled, next?.url) {
        countdown.snapTo(1f)
        if (playback.ended && autoplay && !autoplayCancelled && next != null) {
            countdown.animateTo(0f, tween(5000, easing = LinearEasing))
            autoplayNext(next.url, next.name)
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
    var scrub by remember(url) { mutableFloatStateOf(Float.NaN) }
    val scrubbing = !scrub.isNaN()
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
    LaunchedEffect(url, fullscreen) {
        motion.opened()
        if (compact && !fullscreen) motion.minimize(2.5f)
        settle()
        controls = true
    }
    LaunchedEffect(compact) {
        if (compact != motion.minimized) {
            if (compact) motion.minimize(2.5f) else motion.expand(-1.4f)
            settle()
        }
    }
    LaunchedEffect(controls, playback.playing, miniVisual, scrubbing) {
        if (controls && playback.playing && !miniVisual && !scrubbing) {
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
            if (query.isEmpty() || (BuildConfig.DEBUG && info?.id == "fixture0000")) return@LaunchedEffect
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
                Column(Modifier.watchClick(detailsTouchable) { overlay("Description") },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FeedText(info?.name ?: title, 17, p.text, FontWeight.Bold)
                    val meta = listOfNotNull(info?.viewCount?.takeIf { it >= 0 }?.let { "${watchCount(it)} views" },
                        watchAge(info?.uploadDate)).joinToString(" • ")
                    BasicText(buildAnnotatedString {
                        withStyle(SpanStyle(color = p.muted)) { append(meta) }
                        if (meta.isNotEmpty()) append(" ")
                        withStyle(SpanStyle(color = p.text, fontWeight = FontWeight.SemiBold)) { append("…more") }
                    }, style = TextStyle(fontSize = 12.sp, fontFamily = BilaiFont, lineHeight = 16.sp))
                }
                if (info != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).watchClick(detailsTouchable) { overlay("Channel") },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetImage(info?.uploaderAvatarUrl, Modifier.size(36.dp).clip(CircleShape), avatar = true)
                        Column {
                            FeedText(info?.uploaderName.orEmpty(), 14, p.text, FontWeight.SemiBold)
                            val subscribers = info?.uploaderSubscriberCount ?: -1L
                            if (subscribers >= 0) FeedText("${watchCount(subscribers)} subscribers", 11, p.muted)
                        }
                    }
                    Box(Modifier.clip(CircleShape).background(if (subscribed) p.elevated else p.accent)
                        .watchClick(detailsTouchable) { overlay("Subscribe") }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        FeedText(if (subscribed) "Subscribed" else "Subscribe", 12,
                            if (subscribed) p.text else p.onAccent, FontWeight.Bold)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clip(CircleShape).background(p.elevated), verticalAlignment = Alignment.CenterVertically) {
                        WatchAction(Glyph.THUMB_UP, watchCount(info?.likeCount ?: -1), "Like",
                            if (liked) p.accent else p.text, detailsTouchable) { overlay("Like") }
                        Spacer(Modifier.width(1.dp).height(16.dp).background(Color.White.copy(alpha = .15f)))
                        WatchAction(Glyph.THUMB_DOWN, watchCount(info?.dislikeCount ?: -1), "Dislike",
                            if (disliked) p.accent else p.muted, detailsTouchable, horizontal = 12) { overlay("Dislike") }
                    }
                    Box(Modifier.clip(CircleShape).background(p.elevated)) {
                        WatchAction(Glyph.SHARE, "Share", "Share", p.text, detailsTouchable) { overlay("Share") }
                    }
                    if (info != null) Box(Modifier.clip(CircleShape).background(p.elevated)) {
                        WatchAction(Glyph.DOWNLOAD, "Download", "Download", p.text, detailsTouchable) { overlay("Download") }
                    }
                    Box(Modifier.clip(CircleShape).background(p.accentSoft)) {
                        WatchAction(Glyph.FLOAT, "Float", "Float", p.accent, detailsTouchable) { onCompact(true) }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)
                    .watchClick(detailsTouchable) { overlay("Comments") }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FeedText("Comments", 12, p.text, FontWeight.Bold)
                            if (commentCount >= 0) FeedText(watchCount(commentCount), 12, p.muted)
                        }
                        Icon(Glyph.CHEVRONS, p.muted, Modifier.size(14.dp))
                    }
                    preview?.let { comment ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NetImage(comment.uploaderAvatarUrl, Modifier.size(24.dp).clip(CircleShape), avatar = true)
                            BasicText(comment.commentText.content, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = p.text.copy(alpha = .9f), fontSize = 12.sp,
                                    lineHeight = 16.sp, fontFamily = BilaiFont))
                        }
                    }
                }
                FeedText("UP NEXT", 12, p.muted, FontWeight.Bold)
                }
                }
                items(recommendations, key = { it.url }, contentType = { "related-video" }) { item ->
                    Row(Modifier.fillMaxWidth().watchClick(detailsTouchable) { playNext(item.url, item.name) },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(144.dp).aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp))) {
                            NetImage(item.thumbnailUrl, Modifier.fillMaxSize())
                            if (item.duration > 0) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                BasicText(formatTime(item.duration), style = TextStyle(color = Color.White,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                            BasicText(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(
                                color = p.text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                                fontFamily = BilaiFont))
                            Spacer(Modifier.height(4.dp))
                            FeedText(item.uploaderName.orEmpty(), 11, p.muted)
                            FeedText(listOfNotNull(item.viewCount.takeIf { it >= 0 }?.let { "${watchCount(it)} views" },
                                watchAge(item.uploadDate)).joinToString(" • "), 10, p.muted)
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
            if (pictureInPicture) return@Box
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
            if (playback.error && !miniVisual) Box(Modifier.align(Alignment.Center)) {
                WatchPill("Couldn't play · Tap to retry", Color.White, Color.Black.copy(alpha = .6f)) { val uri = android.net.Uri.parse(url)
                    if (uri.scheme in listOf("file", "content")) engine.playOffline(uri) else engine.play(url)
                }
            }
            val controlsAlpha by animateFloatAsState(if (controls && !miniVisual) 1f else 0f, tween(220),
                label = "player controls")
            val fraction = if (!scrub.isNaN()) scrub else if (playback.durationMs <= 0) 0f
                else (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
            if (!miniVisual && controlsAlpha > .01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = controlsAlpha }) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)))
                if (!fullscreen) Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(40.dp, 4.dp)
                    .background(Color.White.copy(alpha = .5f), CircleShape))
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .padding(horizontal = if (fullscreen) 16.dp else 4.dp, vertical = if (fullscreen) 10.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Rounded.KeyboardArrowDown, if (fullscreen) "Exit fullscreen" else "Minimize",
                        Color.White, Color.Transparent, 44.dp, 30.dp,
                        modifier = if (BuildConfig.DEBUG) Modifier.testTag("player_minimize") else Modifier) {
                        if (fullscreen) onFullscreen(false) else onCompact(true)
                    }
                    if (fullscreen) Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        BText(info?.name ?: title, 15f, Color.White, FontWeight.SemiBold)
                        BText(info?.uploaderName.orEmpty(), 12f, Color.White.copy(alpha = .7f))
                    } else Spacer(Modifier.weight(1f))
                    PlayerSwitch(autoplay, "Autoplay", toggleAutoplay)
                    Spacer(Modifier.width(6.dp))
                    RoundIconButton(Icons.Rounded.ClosedCaption, "Captions",
                        if (captionsOn) p.accent else Color.White, Color.Transparent, 40.dp, 24.dp,
                        enabled = playback.subtitles.isNotEmpty() || captionsOn, click = toggleCaptions)
                    RoundIconButton(Icons.Rounded.Settings, "Player settings", Color.White, Color.Transparent,
                        40.dp, 23.dp) { overlay("Player settings") }
                }
                if (seekCount == 0 && !playback.ended) Row(Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 64.dp else 40.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Rounded.Replay10, "Back 10 seconds", Color.White,
                        Color.Black.copy(alpha = .3f), 48.dp, 30.dp) { seek(-1); controls = true }
                    RoundIconButton(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.playing) "Pause" else "Play", Color.White, Color.Black.copy(alpha = .4f),
                        64.dp, 42.dp) { engine.togglePlay(); controls = true }
                    RoundIconButton(Icons.Rounded.Forward10, "Forward 10 seconds", Color.White,
                        Color.Black.copy(alpha = .3f), 48.dp, 30.dp) { seek(1); controls = true }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .65f))))) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val displayedPosition = if (scrubbing) (scrub * playback.durationMs).toLong() else playback.positionMs
                        BasicText(formatTime(displayedPosition / 1000), style = TextStyle(color = Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = BilaiFont))
                        BasicText(" / " + formatTime(playback.durationMs / 1000), style = TextStyle(
                            color = Color.White.copy(alpha = .7f), fontSize = 12.sp, fontFamily = BilaiFont))
                        if (speed != 1f) Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = .18f)).clickable { overlay("Player settings") }
                            .padding(horizontal = 6.dp, vertical = 2.dp)) {
                            BText(formatSpeed(speed).replace(".00x", "x"), 11f, Color.White, FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        RoundIconButton(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            if (fullscreen) "Exit fullscreen" else "Fullscreen", Color.White, Color.Transparent,
                            40.dp, 26.dp) { onFullscreen(!fullscreen) }
                    }
                    Canvas(Modifier.fillMaxWidth().height(if (fullscreen) 28.dp else 18.dp)
                        .padding(horizontal = if (fullscreen) 16.dp else 0.dp)
                        .pointerInput(url, playback.durationMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (playback.durationMs <= 0 || size.width == 0) return@awaitEachGesture
                            down.consume()
                            scrub = (down.position.x / size.width).coerceIn(0f, 1f)
                            try {
                                while (true) {
                                    val point = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (point.isConsumed) break
                                    scrub = (point.position.x / size.width).coerceIn(0f, 1f)
                                    point.consume()
                                    if (!point.pressed) {
                                        engine.seekTo((scrub * playback.durationMs).toLong())
                                        break
                                    }
                                }
                            } finally {
                                scrub = Float.NaN
                            }
                        }
                    }) {
                        val y = if (fullscreen) center.y else size.height - 2.dp.toPx()
                        val stroke = (if (scrubbing) 5 else 3).dp.toPx()
                        val start = Offset(0f, y)
                        val end = Offset(size.width, y)
                        val thumb = Offset(size.width * fraction, y)
                        drawLine(Color.White.copy(alpha = .3f), start, end, stroke)
                        drawLine(Color.White.copy(alpha = .5f), start,
                            Offset(size.width * playback.bufferedPercent.coerceIn(0, 100) / 100f, y), stroke)
                        drawLine(p.accent, start, thumb, stroke)
                        drawCircle(p.accent, (if (scrubbing) 9 else 6).dp.toPx(), thumb)
                    }
                }
            }
            if (!miniVisual && !fullscreen && controlsAlpha < .99f) Box(Modifier.align(Alignment.BottomStart)
                .fillMaxWidth().height(2.dp).graphicsLayer { alpha = 1f - controlsAlpha }
                .background(Color.White.copy(alpha = .25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(p.accent))
            }
            if (miniVisual) {
                // The surface is scaled down in mini mode; counter-scale the controls so they keep their size.
                fun counterScale(origin: TransformOrigin) = Modifier.graphicsLayer {
                    val t = motion.morph.coerceIn(0f, 1f)
                    val s = (1 - t * (1 - motion.miniWidth / motion.width.coerceAtLeast(1f))).coerceAtLeast(.05f)
                    transformOrigin = origin
                    scaleX = 1f / s; scaleY = 1f / s
                }
                Box(Modifier.align(Alignment.TopEnd).then(counterScale(TransformOrigin(1f, 0f))).padding(4.dp)
                    .then(if (BuildConfig.DEBUG) Modifier.testTag("player_close") else Modifier)) {
                    RoundIconButton(Icons.Rounded.Close, "Close player", Color.White, Color.Black.copy(alpha = .7f),
                        28.dp, 18.dp, click = close)
                }
                Box(Modifier.align(Alignment.BottomStart).then(counterScale(TransformOrigin(0f, 1f)))
                    .padding(start = 4.dp, bottom = 7.dp)) {
                    RoundIconButton(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.playing) "Pause" else "Play", Color.White, Color.Black.copy(alpha = .6f),
                        28.dp, 18.dp, click = engine::togglePlay)
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(p.accent))
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
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Canvas(Modifier.size(22.dp)) {
                                drawCircle(Color.White.copy(alpha = .25f), style = Stroke(2.dp.toPx()))
                                drawArc(p.accent, -90f, countdown.value * 360f, false, style = Stroke(2.dp.toPx()))
                            }
                            FeedText("Up next", 14, Color.White, FontWeight.Bold)
                        }
                        NetImage(next.thumbnailUrl, Modifier.width(96.dp).aspectRatio(16f / 9))
                        FeedText(next.name, 14, Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WatchPill("Cancel", p.text, p.elevated) { autoplayCancelled = true }
                            WatchPill("Play now", p.onAccent, p.accent) { playNext(next.url, next.name) }
                        }
                    } else RoundIconButton(Icons.Rounded.Replay, "Replay", Color.White,
                        Color.White.copy(alpha = .18f), 64.dp, 36.dp, click = engine::replay)
                }
            }
            if (seekCount > 0 && !miniVisual) {
                SeekFeedback(seekSide, seekCount, seekFlash, seekPoint)
            }

        }
    }
}

@Composable
internal fun PlayerSwitch(checked: Boolean, label: String, onToggle: () -> Unit) {
    val p = LocalBilaiColors.current
    val offset by animateFloatAsState(
        targetValue = if (checked) 16f else 0f,
        animationSpec = tween(260, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)),
        label = "player switch",
    )
    Box(Modifier.size(36.dp, 20.dp).background(if (checked) p.accent else p.muted.copy(alpha = .5f), CircleShape)
        .toggleable(checked, role = Role.Switch, onValueChange = { onToggle() })
        .semantics { contentDescription = label }.padding(2.dp)) {
        Box(Modifier.offset(x = offset.dp).size(16.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            if (label == "Autoplay") MIcon(if (checked) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, Color.Black, 12.dp)
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


internal fun watchCount(count: Long): String {
    if (count < 0) return ""
    val (divisor, unit) = when {
        count >= 1_000_000_000 -> 1_000_000_000 to "B"
        count >= 1_000_000 -> 1_000_000 to "M"
        count >= 1_000 -> 1_000 to "K"
        else -> return count.toString()
    }
    return String.format(java.util.Locale.ROOT, "%.1f", count.toDouble() / divisor).removeSuffix(".0") + unit
}

private fun watchAge(date: DateWrapper?): String? {
    val time = date?.offsetDateTime() ?: return null
    val now = OffsetDateTime.now()
    for ((unit, label) in listOf(ChronoUnit.YEARS to "year", ChronoUnit.MONTHS to "month",
        ChronoUnit.WEEKS to "week", ChronoUnit.DAYS to "day", ChronoUnit.HOURS to "hour",
        ChronoUnit.MINUTES to "minute")) {
        val size = unit.between(time, now)
        if (size > 0) return "$size $label${if (size == 1L) "" else "s"} ago"
    }
    return "Just now"
}

@Composable
private fun WatchAction(glyph: Glyph, text: String, label: String, color: Color, enabled: Boolean,
                        horizontal: Int = 14, click: () -> Unit) {
    Row(Modifier.watchClick(enabled, click).semantics { contentDescription = label }
        .padding(horizontal = horizontal.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(glyph, color, Modifier.size(14.dp))
        if (text.isNotEmpty()) FeedText(text, 12, color, FontWeight.SemiBold)
    }
}
