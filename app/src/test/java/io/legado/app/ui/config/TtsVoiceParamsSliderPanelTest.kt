package io.legado.app.ui.config

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVoiceParamsSliderPanelTest {

    @Test
    fun pitchDisplayOnlyShowsHundredthsWhenNeeded() {
        assertEquals("1.0x", formatTtsVoicePlaybackRatio(1f, 2))
        assertEquals("1.05x", formatTtsVoicePlaybackRatio(1.05f, 2))
        assertEquals("1.1x", formatTtsVoicePlaybackRatio(1.1f, 2))
    }
}
