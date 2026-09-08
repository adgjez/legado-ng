package io.legado.app.help.tts

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.ArrayList
import java.util.Collections
import java.util.WeakHashMap

/**
 * Creates players for synthesized speech.
 *
 * SoundTouch replaces Media3 and Android's Sonic-based time stretching, which can introduce
 * audible artifacts in synthesized speech above 1x.
 */
@SuppressLint("UnsafeOptInUsageError")
object TtsPlayerFactory {

    private data class PlayerState(
        val processorChain: TtsAdjustableAudioProcessorChain,
        val profileController: TtsProfilePlaybackController?,
    )

    private val playerStates = Collections.synchronizedMap(
        WeakHashMap<ExoPlayer, PlayerState>()
    )

    fun create(context: Context, allowFormatChanges: Boolean = false): ExoPlayer {
        val multiRoleChain = if (allowFormatChanges) TtsMedia3AudioProcessorChain() else null
        val profileController = multiRoleChain?.let(::TtsProfilePlaybackController)
        val processorChain = multiRoleChain ?: TtsSoundTouchAudioProcessorChain()
        val renderersFactory = TtsRenderersFactory(
            context = context,
            processorChain = processorChain,
            profileController = profileController,
        )
        return ExoPlayer.Builder(context, renderersFactory).build().also { player ->
            playerStates[player] = PlayerState(processorChain, profileController)
        }
    }

    internal fun registerPlaybackProfile(
        player: ExoPlayer,
        profile: TtsPlaybackProfile,
        voiceParams: TtsVoicePlaybackParams,
    ) {
        playerStates[player]?.profileController?.register(profile, voiceParams)
    }

    fun applyPlaybackBaseRate(player: ExoPlayer, baseRate: Float) {
        val state = playerStates[player]
        val controller = state?.profileController
        if (controller == null) {
            applyPlaybackAdjustments(player, baseRate, TtsVoicePlaybackParams())
            return
        }
        val params = controller.setBaseRate(baseRate)
        controller.applyVolumeGain(params.volumeGain)
        player.setPlaybackParameters(params.playbackParameters)
    }

    fun currentPlaybackSpeed(player: ExoPlayer): Float {
        return playerStates[player]
            ?.profileController
            ?.currentParams()
            ?.speed
            ?: player.playbackParameters.speed
    }

    fun usesProfileAwareRenderer(player: ExoPlayer): Boolean {
        return playerStates[player]?.profileController != null
    }

    fun applyPlaybackAdjustments(
        player: ExoPlayer,
        baseRate: Float,
        voiceParams: TtsVoicePlaybackParams,
    ) {
        val params = TtsPlaybackAdjustmentPolicy.resolve(baseRate, voiceParams)
        val activationChanged = playerStates[player]
            ?.processorChain
            ?.applyPlaybackAdjustments(params)
            ?: false
        val playbackParametersChanged = player.playbackParameters != params.playbackParameters
        player.setPlaybackParameters(params.playbackParameters)
        if (activationChanged && !playbackParametersChanged &&
            player.playbackState != Player.STATE_IDLE
        ) {
            player.seekTo(player.currentPosition)
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private class TtsRenderersFactory(
    context: Context,
    private val processorChain: TtsAdjustableAudioProcessorChain,
    private val profileController: TtsProfilePlaybackController?,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(processorChain)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val firstAddedIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        val controller = profileController ?: return
        val standardRendererIndex = (firstAddedIndex until out.size)
            .firstOrNull { index -> out[index] is MediaCodecAudioRenderer }
            ?: error("Media3 standard audio renderer was not created")
        out[standardRendererIndex] = TtsProfileAwareAudioRenderer(
            context = context,
            codecAdapterFactory = codecAdapterFactory,
            mediaCodecSelector = mediaCodecSelector,
            enableDecoderFallback = enableDecoderFallback,
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink,
            controller = controller,
        )
    }
}
