package io.github.zvensmoluya.vortexcue

import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayProfileTest {
    @Test
    fun `uses named preset when independent values are absent`() {
        val restored =
            DisplayProfile.restore(
                name = DisplayProfile.Soft.name,
                windowBrightness = null,
                subtitleLevel = null,
                controlLevel = null,
                subtitleSizeSp = null,
                subtitleVerticalBias = null,
            )

        assertEquals(DisplayProfile.Soft, restored)
    }

    @Test
    fun `clamps custom display values to safe product ranges`() {
        val restored =
            DisplayProfile.restore(
                name = DisplayProfile.CustomName,
                windowBrightness = 2f,
                subtitleLevel = 255,
                controlLevel = -1,
                subtitleSizeSp = Float.NaN,
                subtitleVerticalBias = -2f,
            )

        assertEquals(DisplayProfile.CustomName, restored.name)
        assertEquals(0.30f, restored.glowWindowBrightness, 0f)
        assertEquals(96, restored.glowSubtitleLevel)
        assertEquals(24, restored.glowControlLevel)
        assertEquals(DisplayProfile.Balanced.glowSubtitleSizeSp, restored.glowSubtitleSizeSp, 0f)
        assertEquals(-0.60f, restored.glowSubtitleVerticalBias, 0f)
    }
}
