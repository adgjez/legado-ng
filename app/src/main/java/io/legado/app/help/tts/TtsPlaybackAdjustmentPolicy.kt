package io.legado.app.help.tts

import androidx.media3.common.PlaybackParameters

internal data class TtsPlaybackProfile(
    val engineId: String,
    val voiceId: String?,
)

internal data class TtsEffectivePlaybackParams(
    val speed: Float,
    val pitch: Float,
    val volumeGain: Float,
) {
    val playbackParameters: PlaybackParameters
        get() = PlaybackParameters(speed, pitch)
}

internal object TtsPlaybackAdjustmentPolicy {

    fun resolve(
        baseRate: Float,
        voiceParams: TtsVoicePlaybackParams,
    ): TtsEffectivePlaybackParams {
        val normalized = voiceParams.normalized()
        return TtsEffectivePlaybackParams(
            speed = (baseRate * normalized.speedRatio).coerceIn(0.1f, 5f),
            pitch = normalized.pitchRatio,
            volumeGain = normalized.volumeGain,
        )
    }
}
