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
    val firstFrame: Boolean = false,
)

/** One entry of the download picker. [secondary] is the audio track muxed into a video-only stream. */
internal data class DownloadOption(
    val label: String,
    val detail: String,
    val audioOnly: Boolean,
    val primary: org.schabi.newpipe.extractor.stream.Stream,
    val secondary: org.schabi.newpipe.extractor.stream.AudioStream?,
    val bytes: Long,
)

internal interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    val current: StateFlow<StreamInfo?>
    fun play(url: String, startMs: Long = 0)
    fun prefetch(url: String)
    fun playOffline(uri: Uri)
    fun attachSurface(surface: TextureView?)
    fun togglePlay()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun setQuality(height: Int?)
    fun setCaptions(language: String?)
    fun setLoop(enabled: Boolean)
    fun replay()
    fun download(): Boolean
    fun downloadOptions(): List<DownloadOption>
    fun download(option: DownloadOption): Boolean
    val lastDownloadError: String?
    fun stop()
    fun detach()
}

internal class ServicePlaybackEngine(context: Context) : PlaybackEngine, PlayerServiceExtendedEventListener {
    private val app = context.applicationContext
    private val holder = PlayerHolder.getInstance()
    private val request = SerialDisposable()
    private val prefetchRequest = SerialDisposable()
    private var requestedStreamId: String? = null
    private var localFirstFrame = false
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

    override fun download(): Boolean = downloadOptions().firstOrNull { !it.audioOnly }?.let(::download) ?: false

    override fun downloadOptions(): List<DownloadOption> {
        val info = metadata.value ?: return emptyList()
        val videos = ArrayList(org.schabi.newpipe.util.VideoStreamSelector.getSortedStreamVideosList(
            app, info.videoStreams, info.videoOnlyStreams, false, false))
        HlsDownloadStreamHelper.addManifestFallbackIfNeeded(videos, info)
        val audios = org.schabi.newpipe.util.AudioStreamSelector
            .filterDownloadableAudioStreams(info.audioStreams.orEmpty())
        val seconds = info.duration.coerceAtLeast(0)
        fun estimate(videoBitsPerSecond: Int, audioKbps: Int): Long {
            val bits = videoBitsPerSecond.coerceAtLeast(0).toLong() + audioKbps.coerceAtLeast(0) * 1000L
            return if (bits <= 0 || seconds <= 0) 0 else bits * seconds / 8
        }
        val options = ArrayList<DownloadOption>()
        val seen = HashSet<String>()
        for (video in videos) {
            val secondary = if (video.isVideoOnly) {
                org.schabi.newpipe.util.SecondaryStreamHelper.getAudioStreamFor(app,
                    us.shandian.giga.get.SabrDownloadStreamHelper.audioStreamsForVideo(audios, video), video)
                    ?.takeIf { us.shandian.giga.get.SabrDownloadStreamHelper.isCompatibleSecondaryStream(video, it) }
            } else null
            if (video.isVideoOnly && secondary == null) continue
            val label = video.resolution?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(label)) continue
            val format = video.format?.name ?: "MP4"
            options += DownloadOption(label, format, false, video, secondary,
                estimate(video.bitrate, secondary?.averageBitrate ?: 0))
        }
        val audio = audios.filter { it.format == org.schabi.newpipe.extractor.MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate } ?: audios.maxByOrNull { it.averageBitrate }
        if (audio != null) {
            val kbps = audio.averageBitrate
            options += DownloadOption("Audio only", (audio.format?.name ?: "AUDIO") +
                (if (kbps > 0) " · ${kbps} kbps" else ""), true, audio, null, estimate(0, kbps))
        }
        return options
    }

    /** Last download error, shown to the user by the UI. */
    override var lastDownloadError: String? = null
        private set

    /**
     * Creates the target file the way PipePipe's download dialog does (the downloader only writes
     * into an existing file). Public Movies/Music first so the result appears in the gallery and
     * music apps; app-specific storage as a fallback when shared storage isn't writable.
     */
    private fun createTarget(name: String, mime: String, audio: Boolean, category: String?): StoredFileHelper? {
        val publicDir = android.os.Environment.getExternalStoragePublicDirectory(
            if (audio) android.os.Environment.DIRECTORY_MUSIC else android.os.Environment.DIRECTORY_MOVIES)
        // Videos are grouped by YouTube's category (Music, Gaming, ...) when it is known.
        val known = setOf("Film & Animation", "Autos & Vehicles", "Music", "Pets & Animals", "Sports",
            "Travel & Events", "Gaming", "People & Blogs", "Comedy", "Entertainment", "News & Politics",
            "Howto & Style", "Education", "Science & Technology", "Nonprofits & Activism")
        // Only YouTube's English category names become folders; anything else goes to BilaiTube/.
        val folder = category?.trim()?.takeIf { !audio && it in known }?.replace("&", "and")
        val candidates = listOfNotNull(
            if (folder != null) File(File(publicDir, "BilaiTube"), folder) else null,
            File(publicDir, "BilaiTube"),
            app.getExternalFilesDir(if (audio) android.os.Environment.DIRECTORY_MUSIC
                else android.os.Environment.DIRECTORY_MOVIES),
            File(app.filesDir, "offline"),
        )
        for (dir in candidates) {
            try {
                if (!dir.exists() && !dir.mkdirs()) continue
                var file = File(dir, name)
                var index = 2
                while (file.exists()) {
                    file = File(dir, name.substringBeforeLast('.') + " ($index)." + name.substringAfterLast('.'))
                    index++
                }
                if (!file.createNewFile()) continue
                val helper = org.schabi.newpipe.streams.io.StoredDirectoryHelper(app, Uri.fromFile(dir),
                    if (audio) "audio" else "video")
                return StoredFileHelper(app, Uri.fromFile(dir), Uri.fromFile(file), helper.tag)
            } catch (e: Exception) {
                android.util.Log.w("BilaiDownload", "cannot write to $dir", e)
            }
        }
        return null
    }

    override fun download(option: DownloadOption): Boolean {
        lastDownloadError = null
        val info = metadata.value ?: return false.also { lastDownloadError = "Video info not loaded yet" }
        val primary = option.primary
        val secondary = option.secondary
        val m4a = primary.format == org.schabi.newpipe.extractor.MediaFormat.M4A
        val suffix = when {
            option.audioOnly && m4a -> "m4a"
            else -> primary.format?.suffix ?: if (option.audioOnly) "m4a" else "mp4"
        }
        val safeTitle = info.name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), " ").trim().take(80)
            .ifBlank { info.id }
        val mime = primary.format?.mimeType ?: if (option.audioOnly) "audio/mp4" else "video/mp4"
        val storage = createTarget("$safeTitle (${option.label}).$suffix", mime, option.audioOnly,
            runCatching { info.category }.getOrNull())
            ?: return false.also { lastDownloadError = "No writable storage" }
        var psName: String? = when {
            option.audioOnly && m4a -> us.shandian.giga.postprocessing.Postprocessing.ALGORITHM_M4A_NO_DASH
            option.audioOnly -> null
            secondary == null -> null
            primary.format == org.schabi.newpipe.extractor.MediaFormat.MPEG_4 ->
                us.shandian.giga.postprocessing.Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER
            else -> us.shandian.giga.postprocessing.Postprocessing.ALGORITHM_WEBM_MUXER
        }
        val urls = if (secondary == null) arrayOf(primary.content) else arrayOf(primary.content, secondary.content)
        val recovery = if (secondary == null) arrayOf(MissionRecoveryInfo(primary))
            else arrayOf(MissionRecoveryInfo(primary), MissionRecoveryInfo(secondary))
        val methods = HlsDownloadStreamHelper.buildResourceDeliveryMethods(primary, secondary)
        val manifests = HlsDownloadStreamHelper.buildResourceManifestUrls(primary, secondary)
        val isUrls = HlsDownloadStreamHelper.buildResourceIsUrls(primary, secondary)
        val headers = HlsDownloadStreamHelper.buildResourceRequestHeaders(primary, secondary)
        if (HlsDownloadStreamHelper.containsHlsResource(methods, manifests, urls)
            || us.shandian.giga.get.SabrDownloadStreamHelper.containsSabrStream(primary, secondary)) {
            psName = null
        }
        return try {
            DownloadManagerService.startMission(app, urls, storage, if (option.audioOnly) 'a' else 'v',
                3, info.url, psName, null, option.bytes, recovery, methods, manifests, isUrls, headers)
            true
        } catch (e: Exception) {
            android.util.Log.w("BilaiDownload", "startMission failed", e)
            lastDownloadError = e.javaClass.simpleName
            false
        }
    }

    private fun trackState(state: PlaybackState, player: ExoPlayer): PlaybackState {
        val groups = player.currentTracks.groups
        return state.copy(
            firstFrame = if (sample != null) localFirstFrame else requestedStreamId != null &&
                requestedStreamId == connected?.bilaiRenderedStreamId,
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
        localFirstFrame = false
    }
    override val state: StateFlow<PlaybackState> = playback
    override val current: StateFlow<StreamInfo?> = metadata

    override fun play(url: String, startMs: Long) {
        releaseSample()
        requestedStreamId = null
        playback.value = PlaybackState()
        metadata.value = null
        val debugFixture = Uri.parse(url)
        if (BuildConfig.DEBUG && debugFixture.scheme == "file" && debugFixture.authority.isNullOrEmpty()
            && debugFixture.path in setOf("${app.applicationInfo.dataDir}/sample.mp4",
                "/data/data/${app.packageName}/sample.mp4")) {
            metadata.value = org.schabi.newpipe.bilai.WatchReviewFixture.stream(url)
            playLocal(debugFixture, startMs)
            return
        }
        request.set(ExtractorHelper.getStreamInfo(ServiceList.YouTube.serviceId, url, false)
            .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe({ info ->
                requestedStreamId = info.id
                metadata.value = info
                val intent = NavigationHelper.getPlayerIntent(
                    app, DeviceUtils.getPlayerServiceClass(), SinglePlayQueue(info, startMs), false, true,
                )
                ContextCompat.startForegroundService(app, intent)
                holder.startService(false, this)
            }, { playback.value = PlaybackState(error = true) }))
    }

    override fun prefetch(url: String) {
        if (!url.startsWith("https://")) return
        prefetchRequest.set(ExtractorHelper.getStreamInfo(ServiceList.YouTube.serviceId, url, false)
            .subscribeOn(Schedulers.io()).subscribe({}, {}))
    }

    override fun playOffline(uri: Uri) {
        if (uri.scheme !in listOf("file", "content")) return
        releaseSample()
        metadata.value = null
        playback.value = PlaybackState()
        playLocal(uri, 0)
    }

    private fun playLocal(uri: Uri, startMs: Long) {
        request.set(null)
        if (holder.isBound) holder.stopService()
        sample = ExoPlayer.Builder(app).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            setVideoTextureView(surface)
            addListener(object : com.google.android.exoplayer2.Player.Listener {
                override fun onRenderedFirstFrame() { localFirstFrame = true }
            })
            prepare()
            seekTo(startMs)
            playWhenReady = true
        }
        setSpeed(requestedSpeed)
        setLoop(loop)
        main.post(pollSample)
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
        prefetchRequest.dispose()
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
        if (info.id != requestedStreamId) return
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
