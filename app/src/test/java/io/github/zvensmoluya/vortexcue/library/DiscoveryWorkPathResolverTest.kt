package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryWorkPathResolverTest {
    @Test
    fun technicalVariantDirectoriesCollapseIntoTheirWorkRoot() {
        assertPath("downloads/work", "Work", "Downloads/Work/WAV/SEあり")
        assertPath("downloads/work", "Work", "Downloads\\Work\\MP3")
        assertPath("downloads/work", "Work", "Downloads/Work/WAV 24bit 96kHz")
    }

    @Test
    fun rjCodeProvidesAWorkRootAcrossContentSubdirectories() {
        assertPath(
            expectedStablePath = "downloads/rj123456_作品名",
            expectedDisplayName = "作品名",
            folder = "Downloads/RJ123456_作品名/本編/Chapter 01",
        )
    }

    @Test
    fun codeOnlyReleaseFolderUsesMeaningfulParentAsTitle() {
        assertPath(
            expectedStablePath = "downloads/静かな夜/rj123456",
            expectedDisplayName = "静かな夜",
            folder = "Downloads/静かな夜/RJ123456/WAV",
        )
        assertPath(
            expectedStablePath = "downloads/rj123456",
            expectedDisplayName = "RJ123456",
            folder = "Downloads/RJ123456/WAV",
        )
    }

    @Test
    fun contentDirectoriesAndFormatWordsInsideTitlesStayPhysical() {
        assertPath("downloads/work/本編", "本編", "Downloads/Work/本編")
        assertPath("downloads/my mp3 story", "My MP3 Story", "Downloads/My MP3 Story")
        assertPath("downloads/work/disc 1", "Disc 1", "Downloads/Work/Disc 1")
    }

    @Test
    fun technicalDirectoryWithoutAWorkCandidateDoesNotCollapse() {
        assertPath("downloads/wav", "WAV", "Downloads/WAV")
        assertPath("wav", "WAV", "WAV")
    }

    @Test
    fun missingLocationFallsBackToOtherAudio() {
        assertPath("_other", "其他音频", null)
    }

    @Test
    fun defaultSelectionPrefersWavOnlyForExplicitParallelVariants() {
        val candidates =
            listOf(
                candidate("wav", "01.wav", "Downloads/Work/WAV"),
                candidate("mp3", "01.mp3", "Downloads/Work/MP3"),
                candidate("quality-wav", "02.wav", "Downloads/Work/WAV 24bit 96kHz"),
                candidate("quality-mp3", "02.mp3", "Downloads/Work/MP3 320kbps"),
                candidate("other", "03.mp3", "Downloads/Work/MP3"),
            )

        assertEquals(setOf("wav", "quality-wav", "other"), defaultDiscoverySelection(candidates))
    }

    @Test
    fun defaultSelectionDoesNotGuessAcrossNamesOrPhysicalMixes() {
        val candidates =
            listOf(
                candidate("same-wav", "01.wav", "Downloads/Work"),
                candidate("same-mp3", "01.mp3", "Downloads/Work"),
                candidate("named-wav", "Track A.wav", "Downloads/Work/WAV"),
                candidate("named-mp3", "Track B.mp3", "Downloads/Work/MP3"),
                candidate("se-wav", "02.wav", "Downloads/Work/WAV/SEあり"),
                candidate("se-mp3", "02.mp3", "Downloads/Work/MP3/SEなし"),
            )

        assertEquals(candidates.mapTo(linkedSetOf(), DiscoverySelectionCandidate::contentId), defaultDiscoverySelection(candidates))
    }

    private fun assertPath(
        expectedStablePath: String,
        expectedDisplayName: String,
        folder: String?,
    ) {
        val resolved = resolveDiscoveryWorkPath(folder)

        assertEquals(expectedStablePath, resolved.stablePath)
        assertEquals(expectedDisplayName, resolved.displayName)
    }

    private fun candidate(
        contentId: String,
        displayName: String,
        folder: String,
    ) = DiscoverySelectionCandidate(contentId, displayName, folder)
}
