package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteWorkAnalyzerTest {
    @Test
    fun formatCopiesBecomeOnePartWhileSemanticVariantsStayIndependent() {
        val plan =
            analyzeDlsiteWork(
                listOf(
                    candidate("wav", "01.wav", "Download/RJ01658524 Work/01_WAV版", 60_000L),
                    candidate("mp3", "01.mp3", "Download/RJ01658524 Work/02_MP3版", 60_500L),
                    candidate("without-se", "01.wav", "Download/RJ01658524 Work/03_効果音無し", 59_000L),
                    candidate("bonus", "特典01.wav", "Download/RJ01658524 Work/04_特典", 30_000L),
                ),
            )

        assertEquals(3, plan.parts.size)
        assertEquals(setOf("wav", "mp3"), plan.parts.first { it.primaryContentId == "wav" }.equivalentContentIds.toSet())
        assertFalse(plan.parts.first { it.primaryContentId == "without-se" }.defaultIncluded)
        assertEquals("特典", plan.parts.first { it.primaryContentId == "bonus" }.sectionName)
        assertTrue("wav" in plan.defaultContentIds)
        assertTrue("bonus" in plan.defaultContentIds)
        assertTrue(plan.profileLabel.contains("WAV 优先"))
    }

    @Test
    fun durationMismatchPreventsRiskyFormatMerge() {
        val plan =
            analyzeDlsiteWork(
                listOf(
                    candidate("wav", "01.wav", "RJ123456 Work/WAV", 60_000L),
                    candidate("mp3", "01.mp3", "RJ123456 Work/MP3", 90_000L),
                ),
            )

        assertEquals(2, plan.parts.size)
        assertTrue(plan.parts.all { it.equivalentContentIds.size == 1 })
    }

    private fun candidate(id: String, name: String, folder: String, durationMs: Long) =
        DlsiteWorkCandidate(id, name, folder, durationMs)
}
