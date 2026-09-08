package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackAdjustmentPolicyTest {

    @Test
    fun listeningRateAndVoiceRateAreMultiplied() {
        val result = TtsPlaybackAdjustmentPolicy.resolve(
            baseRate = 1.5f,
            voiceParams = TtsVoicePlaybackParams(
                speedRatio = 1.2f,
                volumeGain = 0.8f,
                pitchRatio = 1.1f,
            ),
        )

        assertEquals(1.8f, result.speed, 0.001f)
        assertEquals(0.8f, result.volumeGain, 0.001f)
        assertEquals(1.1f, result.pitch, 0.001f)
    }

    @Test
    fun effectivePlaybackRateIsClampedToPlayerRange() {
        assertEquals(
            5f,
            TtsPlaybackAdjustmentPolicy.resolve(
                baseRate = 5f,
                voiceParams = TtsVoicePlaybackParams(speedRatio = 2f),
            ).speed,
            0.001f,
        )
        assertEquals(
            0.1f,
            TtsPlaybackAdjustmentPolicy.resolve(
                baseRate = 0.5f,
                voiceParams = TtsVoicePlaybackParams(speedRatio = 0.1f),
            ).speed,
            0.001f,
        )
    }

    @Test
    fun pcmGainUsesSaturationInsteadOfOverflow() {
        assertEquals(20_000.toShort(), applyTtsPcm16Gain(10_000.toShort(), 2f))
        assertEquals(Short.MAX_VALUE, applyTtsPcm16Gain(30_000.toShort(), 2f))
        assertEquals(Short.MIN_VALUE, applyTtsPcm16Gain((-30_000).toShort(), 2f))
        assertEquals(1_000.toShort(), applyTtsPcm16Gain(10_000.toShort(), 0f))
    }
}
