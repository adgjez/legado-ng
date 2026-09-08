package io.legado.app.help.tts

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSoundTouchAudioProcessorTest {

    @Test
    fun `chain applies tempo without changing pitch`() {
        val chain = TtsSoundTouchAudioProcessorChain()
        val parameters = PlaybackParameters(1.5f, 1f)

        assertEquals(parameters, chain.applyPlaybackParameters(parameters))
        assertTrue(chain.audioProcessors.single().isActive)
        assertEquals(1_500_000L, chain.getMediaDuration(1_000_000L))
    }

    @Test
    fun `processor stays inactive at normal speed`() {
        val chain = TtsSoundTouchAudioProcessorChain()

        chain.applyPlaybackParameters(PlaybackParameters.DEFAULT)

        assertFalse(chain.audioProcessors.single().isActive)
        assertEquals(1_000_000L, chain.getMediaDuration(1_000_000L))
    }

    @Test
    fun `multi-role chain uses media3 sonic before local gain`() {
        val chain = TtsMedia3AudioProcessorChain()
        val processors = chain.audioProcessors

        assertEquals(2, processors.size)
        assertTrue(processors[0] is SonicAudioProcessor)
        assertTrue(processors[1] is TtsPcmGainAudioProcessor)
    }

    @Test
    fun `multi-role chain reports gain activation while sonic remains active`() {
        val chain = TtsMedia3AudioProcessorChain()

        assertTrue(
            chain.applyPlaybackAdjustments(
                TtsEffectivePlaybackParams(speed = 1.5f, pitch = 1f, volumeGain = 1f)
            )
        )
        assertTrue(
            chain.applyPlaybackAdjustments(
                TtsEffectivePlaybackParams(speed = 1.5f, pitch = 1f, volumeGain = 1.2f)
            )
        )
        assertFalse(
            chain.applyPlaybackAdjustments(
                TtsEffectivePlaybackParams(speed = 1.5f, pitch = 1f, volumeGain = 1.4f)
            )
        )
    }
}
