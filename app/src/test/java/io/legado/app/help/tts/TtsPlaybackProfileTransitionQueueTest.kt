package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsPlaybackProfileTransitionQueueTest {

    @Test
    fun `prebuffered profiles activate only when their output stream is processed`() {
        val queue = TtsPlaybackProfileTransitionQueue()
        val first = profile("first")
        val second = profile("second")
        val third = profile("third")

        assertEquals(first, queue.onStreamChanged(stream("p1", first))?.profile)
        assertNull(queue.onStreamChanged(stream("p2", second)))
        assertNull(queue.onStreamChanged(stream("p3", third)))

        assertEquals(second, queue.onProcessedStreamChange()?.profile)
        assertEquals(third, queue.onProcessedStreamChange()?.profile)
        assertNull(queue.onProcessedStreamChange())
    }

    @Test
    fun `duplicate stream callbacks do not shift profile order`() {
        val queue = TtsPlaybackProfileTransitionQueue()
        val first = profile("first")
        val second = profile("second")

        queue.onStreamChanged(stream("p1", first))
        assertNull(queue.onStreamChanged(stream("p1", first)))
        queue.onStreamChanged(stream("p2", second))
        assertNull(queue.onStreamChanged(stream("p2", second)))

        assertEquals(second, queue.onProcessedStreamChange()?.profile)
        assertNull(queue.onProcessedStreamChange())
    }

    @Test
    fun `position reset discards stale prebuffered profiles`() {
        val queue = TtsPlaybackProfileTransitionQueue()
        val first = profile("first")
        val stale = profile("stale")
        val target = profile("target")

        queue.onStreamChanged(stream("p1", first))
        queue.onStreamChanged(stream("p2", stale))

        assertEquals(target, queue.reset(stream("p3", target))?.profile)
        assertNull(queue.onProcessedStreamChange())
    }

    @Test
    fun `controller keeps active params stable until profile activation`() {
        val controller = TtsProfilePlaybackController(TtsMedia3AudioProcessorChain())
        val voice = profile("voice")

        controller.setBaseRate(1.2f)
        controller.register(
            voice,
            TtsVoicePlaybackParams(speedRatio = 1.3f, volumeGain = 1.2f, pitchRatio = 1.05f),
        )
        assertEquals(1.2f, controller.currentParams().speed, 0.0001f)

        val activated = controller.activate(voice)
        assertEquals(1.56f, activated.speed, 0.0001f)
        assertEquals(1.2f, activated.volumeGain, 0.0001f)
        assertEquals(1.05f, activated.pitch, 0.0001f)

        controller.register(voice, TtsVoicePlaybackParams(speedRatio = 1.5f))
        assertEquals(1.56f, controller.currentParams().speed, 0.0001f)
        assertEquals(1.8f, controller.activate(voice).speed, 0.0001f)
    }

    private fun profile(voiceId: String) = TtsPlaybackProfile(
        engineId = "engine",
        voiceId = voiceId,
    )

    private fun stream(periodUid: String, profile: TtsPlaybackProfile) =
        TtsPlaybackStreamProfile(periodUid, profile)
}
