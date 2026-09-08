package io.legado.app.help.tts

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal data class TtsPlaybackStreamProfile(
    val periodUid: Any,
    val profile: TtsPlaybackProfile?,
)

internal data class TtsPlaybackProfileActivation(
    val profile: TtsPlaybackProfile?,
)

/**
 * Mirrors MediaCodecRenderer's input/output stream queue so voice parameters are activated at
 * the processed PCM boundary instead of the later application-level media-item callback.
 */
internal class TtsPlaybackProfileTransitionQueue {

    private val pending = ArrayDeque<TtsPlaybackStreamProfile>()
    private var active: TtsPlaybackStreamProfile? = null
    private var lastObservedPeriodUid: Any? = null

    fun onStreamChanged(stream: TtsPlaybackStreamProfile): TtsPlaybackProfileActivation? {
        if (stream.periodUid == lastObservedPeriodUid) return null
        lastObservedPeriodUid = stream.periodUid
        if (active == null) {
            active = stream
            return TtsPlaybackProfileActivation(stream.profile)
        }
        pending.addLast(stream)
        return null
    }

    fun onProcessedStreamChange(): TtsPlaybackProfileActivation? {
        val stream = pending.pollFirst() ?: return null
        active = stream
        return TtsPlaybackProfileActivation(stream.profile)
    }

    fun reset(stream: TtsPlaybackStreamProfile?): TtsPlaybackProfileActivation? {
        pending.clear()
        active = stream
        lastObservedPeriodUid = stream?.periodUid
        return stream?.let { TtsPlaybackProfileActivation(it.profile) }
    }

    fun clear() {
        pending.clear()
        active = null
        lastObservedPeriodUid = null
    }
}

internal class TtsProfilePlaybackController(
    private val processorChain: TtsMedia3AudioProcessorChain,
) {

    private val voiceParams = ConcurrentHashMap<TtsPlaybackProfile, TtsVoicePlaybackParams>()
    private var baseRate = 1f
    private var activeProfile: TtsPlaybackProfile? = null

    @Volatile
    private var activeParams = TtsEffectivePlaybackParams(1f, 1f, 1f)

    fun register(profile: TtsPlaybackProfile, params: TtsVoicePlaybackParams) {
        voiceParams[profile] = params.normalized()
    }

    @Synchronized
    fun setBaseRate(rate: Float): TtsEffectivePlaybackParams {
        baseRate = rate.coerceIn(0.1f, 5f)
        return resolveActiveParams()
    }

    @Synchronized
    fun activate(profile: TtsPlaybackProfile?): TtsEffectivePlaybackParams {
        activeProfile = profile
        return resolveActiveParams()
    }

    @Synchronized
    fun clearActive(): TtsEffectivePlaybackParams {
        activeProfile = null
        return resolveActiveParams()
    }

    fun applyVolumeGain(gain: Float) {
        processorChain.setVoiceVolumeGain(gain)
    }

    fun queueVolumeGain(gain: Float) {
        processorChain.queueVoiceVolumeGain(gain)
    }

    fun currentParams(): TtsEffectivePlaybackParams = activeParams

    private fun resolveActiveParams(): TtsEffectivePlaybackParams {
        return TtsPlaybackAdjustmentPolicy.resolve(
            baseRate = baseRate,
            voiceParams = activeProfile?.let(voiceParams::get) ?: TtsVoicePlaybackParams(),
        ).also { activeParams = it }
    }
}

@Suppress("UnsafeOptInUsageError")
internal class TtsProfileAwareAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val controller: TtsProfilePlaybackController,
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink,
) {

    private val transitionQueue = TtsPlaybackProfileTransitionQueue()
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private var lastEffectiveParams: TtsEffectivePlaybackParams? = null

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId,
    ) {
        val activation = transitionQueue.onStreamChanged(
            playbackStreamProfile(mediaPeriodId)
        )
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        activation?.let(::applyActivation)
    }

    override fun onProcessedStreamChange() {
        super.onProcessedStreamChange()
        transitionQueue.onProcessedStreamChange()
            ?.let(::applyActivation)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        val current = mediaPeriodId?.let { currentPeriodId ->
            runCatching { playbackStreamProfile(currentPeriodId) }.getOrNull()
        }
        transitionQueue.reset(current)
            ?.let(::applyActivation)
    }

    override fun onDisabled() {
        transitionQueue.clear()
        controller.clearActive()
        lastEffectiveParams = null
        super.onDisabled()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // ExoPlayer may re-send its public/base parameters while enabling or re-syncing the
        // renderer. The controller is the source of truth for this TTS-only renderer because it
        // already combines that base rate with the active voice profile.
        applyEffectiveParams(controller.currentParams())
    }

    private fun applyActivation(activation: TtsPlaybackProfileActivation) {
        val params = controller.activate(activation.profile)
        applyEffectiveParams(params)
    }

    private fun playbackStreamProfile(mediaPeriodId: MediaPeriodId): TtsPlaybackStreamProfile {
        val currentTimeline = timeline
        val periodIndex = currentTimeline.getIndexOfPeriod(mediaPeriodId.periodUid)
        val profile = if (periodIndex == C.INDEX_UNSET) {
            null
        } else {
            currentTimeline.getPeriod(periodIndex, period)
            currentTimeline.getWindow(period.windowIndex, window)
            window.mediaItem.localConfiguration?.tag as? TtsPlaybackProfile
        }
        return TtsPlaybackStreamProfile(mediaPeriodId.periodUid, profile)
    }

    private fun applyEffectiveParams(params: TtsEffectivePlaybackParams) {
        controller.queueVolumeGain(params.volumeGain)
        if (lastEffectiveParams == params) return
        lastEffectiveParams = params
        // Even when speed and pitch are unchanged, a gain-only profile change must pass through
        // AudioSink's drain checkpoint so the old Sonic tail keeps the old voice gain.
        super.setPlaybackParameters(params.playbackParameters)
    }

}
