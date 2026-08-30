package io.github.zvensmoluya.vortexcue.synctext

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrSourceTextNormalizerTest {
    @Test
    fun `explicit simplified Chinese converts text without changing timeline identity`() {
        val source = listOf(SubtitleCue(120L, 1_250L, "生命不息，奮鬥不止", "cue-7"))

        val normalized = AsrSourceTextNormalizer.normalize(source, "zh-CN")

        assertEquals(listOf(SubtitleCue(120L, 1_250L, "生命不息，奋斗不止", "cue-7")), normalized)
    }

    @Test
    fun `automatic and traditional Chinese modes do not force script conversion`() {
        val source = listOf(SubtitleCue(0L, 1_000L, "繁體中文", "cue-1"))

        assertEquals(source, AsrSourceTextNormalizer.normalize(source, null))
        assertEquals(source, AsrSourceTextNormalizer.normalize(source, "zh-Hant"))
    }
}
