package org.schabi.newpipe.bilai.engine

import android.content.Context
import android.view.TextureView
import android.net.Uri
import java.io.File
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.get.HlsDownloadStreamHelper
import us.shandian.giga.get.MissionRecoveryInfo
import us.shandian.giga.service.DownloadManagerService
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import org.schabi.newpipe.BuildConfig
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerError
import org.schabi.newpipe.player.PlayerPlaybackParameters
import org.schabi.newpipe.player.PlayerPlaybackState
import org.schabi.newpipe.player.RepeatMode
import org.schabi.newpipe.player.event.PlayerServiceExtendedEventListener
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.NavigationHelper

internal data class PlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPercent: Int = 0,
    val error: Boolean = false,
    val ended: Boolean = false,
    val caption: String = "",
    val subtitles: List<String> = emptyList(),
    val qualities: List<Int> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

internal interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    val current: StateFlow<StreamInfo?>
    fun play(url: String, startMs: Long = 0)
    fun attachSurface(surface: TextureView?)
    fun togglePlay()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun setQuality(height: Int?)
    fun setCaptions(language: String?)
    fun setLoop(enabled: Boolean)
    fun replay()
    fun download(): Boolean
    fun stop()
    fun detach()
}

internal class ServicePlaybackEngine(context: Context) : PlaybackEngine, PlayerServiceExtendedEventListener {
    private val app = context.applicationContext
    private val holder = PlayerHolder.getInstance()
    private val request = SerialDisposable()
    private val playback = MutableStateFlow(PlaybackState())
    private val metadata = MutableStateFlow<StreamInfo?>(null)
    private var surface: TextureView? = null
    private var connected: Player? = null
    private var sample: ExoPlayer? = null
    private val activePlayer get() = sample ?: connected?.bilaiPlayback
    private var captionLanguage: String? = null
    private var loop = false
    private var requestedSpeed = 1f

    override fun setQuality(height: Int?) {
        val player = activePlayer ?: return
        val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (height != null) {
            for (group in player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }) {
                val index = (0 until group.length).firstOrNull { group.getTrackFormat(it).height == height }
                if (index != null) {
                    parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    break
                }
            }
        }
        player.trackSelectionParameters = parameters.build()
    }

    override fun setCaptions(language: String?) {
        captionLanguage = language
        val player = activePlayer ?: return
        val parameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, language == null)
        if (language != null) parameters.setPreferredTextLanguage(language)
        player.trackSelectionParameters = parameters.build()
    }

    override fun setLoop(enabled: Boolean) {
        loop = enabled
        activePlayer?.repeatMode = if (enabled) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF
    }

    override fun replay() {
        activePlayer?.apply { seekTo(0); play() }
    }

    override fun download(): Boolean {
        val info = metadata.value ?: return false
        val streams = info.videoStreams.filter { it.isUrl &&
            (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.HLS) }.toMutableList()
        HlsDownloadStreamHelper.addManifestFallbackIfNeeded(streams, info)
        val stream = streams.firstOrNull() ?: return false
        val directory = File(app.filesDir, "offline")
        if (!directory.exists() && !directory.mkdirs()) return false
        val suffix = stream.format?.suffix ?: "mp4"
        val filename = info.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val target = File(directory, "$filename.$suffix")
        if (target.exists()) return false
        return try {
            val storage = StoredFileHelper(app, Uri.fromFile(directory), Uri.fromFile(target), "video")
            DownloadManagerService.startMission(app, arrayOf(stream.content), storage,
                'v', 1, info.url, null, null, 0L, arrayOf(MissionRecoveryInfo(stream)),
                HlsDownloadStreamHelper.buildResourceDeliveryMethods(stream, null),
                HlsDownloadStreamHelper.buildResourceManifestUrls(stream, null),
                HlsDownloadStreamHelper.buildResourceIsUrls(stream, null),
                HlsDownloadStreamHelper.buildResourceRequestHeaders(stream, null))
            true
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun trackState(state: PlaybackState, player: ExoPlayer): PlaybackState {
        val groups = player.currentTracks.groups
        return state.copy(
            ended = player.playbackState == ExoPlayer.STATE_ENDED,
            videoWidth = player.videoSize.width,
            videoHeight = player.videoSize.height,
            caption = if (captionLanguage == null) "" else
                player.currentCues.cues.mapNotNull { it.text }.joinToString("\n"),
            subtitles = groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
                (0 until group.length).mapNotNull { group.getTrackFormat(it).language }
            }.distinct(),
            qualities = groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                (0 until group.length).map { group.getTrackFormat(it).height }.filter { it > 0 }
            }.distinct().sortedDescending(),
        )
    }
    private val main = Handler(Looper.getMainLooper())
    private val pollSample = object : Runnable {
        override fun run() {
            val player = sample ?: return
            playback.value = trackState(PlaybackState(player.isPlaying, player.currentPosition,
                player.duration.coerceAtLeast(0), player.bufferedPercentage, player.playerError != null), player)
            main.postDelayed(this, 200)
        }
    }

    private fun releaseSample() {
        main.removeCallbacks(pollSample)
        sample?.release()
        sample = null
    }
    override val state: StateFlow<PlaybackState> = playback
    override val current: StateFlow<StreamInfo?> = metadata

    override fun play(url: String, startMs: Long) {
        releaseSample()
        playback.value = PlaybackState()
        metadata.value = null
        if (BuildConfig.DEBUG && (url.startsWith("file://") || url.contains("/sample.mp4"))) {
            request.set(null)
            if (holder.isBound) holder.stopService()
            sample = ExoPlayer.Builder(app).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                setVideoTextureView(surface)
                prepare()
                seekTo(startMs)
                playWhenReady = true
            }
            main.post(pollSample)
            return
        }
        request.set(ExtractorHelper.getStreamInfo(ServiceList.YouTube.serviceId, url, false)
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ info ->
                metadata.value = info
                val intent = NavigationHelper.getPlayerIntent(
                    app, DeviceUtils.getPlayerServiceClass(), SinglePlayQueue(info, startMs), false, true,
                )
                ContextCompat.startForegroundService(app, intent)
                holder.startService(false, this)
            }, { playback.value = PlaybackState(error = true) }))
    }

    override fun attachSurface(surface: TextureView?) {
        this.surface = surface
        if (sample != null) sample?.setVideoTextureView(surface) else connected?.setBilaiSurface(surface)
    }

    override fun togglePlay() { sample?.let { it.playWhenReady = !it.playWhenReady } ?: connected?.playPause() }
    override fun seekTo(ms: Long) { sample?.seekTo(ms) ?: connected?.seekTo(ms) }
    override fun setSpeed(speed: Float) {
        requestedSpeed = speed
        sample?.setPlaybackSpeed(speed) ?: connected?.setPlaybackSpeed(speed)
    }

    override fun stop() {
        request.set(null)
        releaseSample()
        attachSurface(null)
        holder.stopService()
        connected = null
        playback.value = PlaybackState()
        metadata.value = null
    }

    override fun detach() {
        releaseSample()
        request.dispose()
        attachSurface(null)
        if (holder.listener === this) holder.setListener(null)
    }

    override fun onServiceConnected(player: Player, service: PlayerServiceInterface, playAfterConnect: Boolean) {
        connected = player
        player.setBilaiSurface(surface)
    }
    override fun onServiceDisconnected() { connected = null }
    override fun onServiceStopped() { connected = null; playback.value = PlaybackState() }
    override fun onQueueUpdate(queue: PlayQueue) = Unit
    override fun onMetadataUpdate(info: StreamInfo, queue: PlayQueue) {
        metadata.value = info
        setCaptions(captionLanguage)
        setLoop(loop)
        setSpeed(requestedSpeed)
    }
    override fun onPlaybackUpdate(state: PlayerPlaybackState, repeatMode: RepeatMode, shuffled: Boolean,
                                  parameters: PlayerPlaybackParameters) {
        playback.value = playback.value.copy(playing = connected?.isPlaying == true, ended = state.isCompleted)
    }
    override fun onProgressUpdate(currentProgress: Int, duration: Int, bufferPercent: Int) {
        val snapshot = playback.value.copy(positionMs = currentProgress.toLong(),
            durationMs = duration.coerceAtLeast(0).toLong(), bufferedPercent = bufferPercent)
        playback.value = activePlayer?.let { trackState(snapshot, it) } ?: snapshot
    }
    override fun onFullscreenStateChanged(fullscreen: Boolean) = Unit
    override fun onMoreOptionsLongClicked() = Unit
    override fun hideSystemUiIfNeeded() = Unit
    override fun onPlayerError(error: PlayerError, isCatchableException: Boolean) {
        playback.value = playback.value.copy(error = true)
    }
}
