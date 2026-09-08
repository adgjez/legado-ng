package io.legado.app.help.tts

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoicePlaybackParamsTest {

    private val baseEngine = TtsEngineSetting(
        id = "script",
        name = "Script",
        type = TtsEngineType.SCRIPT,
        capabilities = setOf(TtsEngineCapability.SYNTHESIS_SPEED),
        defaultSpeed = 45,
        defaultVolume = 55,
        defaultPitch = 65,
        voiceParams = mapOf(
            "voice-a" to TtsVoicePlaybackParams(
                speedRatio = 1.2f,
                volumeGain = 0.8f,
                pitchRatio = 1.1f,
            ),
        ),
    )

    @Test
    fun missingVoiceParamsUseNeutralLocalPlayback() {
        assertEquals(TtsVoicePlaybackParams(), baseEngine.voicePlaybackParams("voice-b"))
        assertEquals(TtsVoicePlaybackParams(), baseEngine.voicePlaybackParams(null))
        assertFalse(baseEngine.hasVoiceParams("voice-b"))
        assertTrue(baseEngine.hasVoiceParams("voice-a"))
    }

    @Test
    fun voicePlaybackParamsAreClampedToLocalRanges() {
        val params = TtsVoicePlaybackParams(
            speedRatio = 0f,
            volumeGain = 3f,
            pitchRatio = 0f,
        ).normalized()

        assertEquals(0.1f, params.speedRatio, 0.001f)
        assertEquals(2f, params.volumeGain, 0.001f)
        assertEquals(0.1f, params.pitchRatio, 0.001f)
    }

    @Test
    fun oldIntegerJsonDoesNotBecomePlaybackRatios() {
        val parsed = Gson().fromJson(
            """{"speed":30,"volume":40,"pitch":50}""",
            TtsVoicePlaybackParams::class.java,
        )

        assertTrue(parsed.isNeutral())
    }

    @Test
    fun unsupportedSynthesisParamsResolveToNormalBaseline() {
        assertEquals(
            TtsSynthesisParams(speed = 45, volume = 50, pitch = 50),
            baseEngine.effectiveSynthesisParams(),
        )
    }

    @Test
    fun audioCacheKeyIgnoresVoicePlaybackParamsAndUnsupportedDimensions() {
        val withVoiceParams = TtsScriptEngineClient.audioCacheKey(
            engine = baseEngine,
            text = "text",
            voiceId = "voice-a",
            volume = 0,
            pitch = 100,
        )
        val withoutVoiceParams = TtsScriptEngineClient.audioCacheKey(
            engine = baseEngine.copy(voiceParams = emptyMap()),
            text = "text",
            voiceId = "voice-a",
            volume = 100,
            pitch = 0,
        )
        val changedSupportedSpeed = TtsScriptEngineClient.audioCacheKey(
            engine = baseEngine,
            text = "text",
            voiceId = "voice-a",
            speed = 80,
        )

        assertEquals(withVoiceParams, withoutVoiceParams)
        assertNotEquals(withVoiceParams, changedSupportedSpeed)
    }
}
