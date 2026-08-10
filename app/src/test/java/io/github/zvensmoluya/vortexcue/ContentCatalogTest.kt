package io.github.zvensmoluya.vortexcue.library

import io.github.zvensmoluya.vortexcue.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCatalogTest {
    @Test
    fun `content and text ids are deterministic and namespaced`() {
        val first = stableContentId("content://media/item/1")
        val repeated = stableContentId("content://media/item/1")
        val different = stableContentId("content://media/item/2")
        val track =
            stableTimedTextTrackId(
                contentId = first,
                origin = TimedTextOrigin.Sidecar,
                locatorIdentity = "uri:content://subtitle/item/1",
            )

        assertEquals(first, repeated)
        assertFalse(first == different)
        assertTrue(first.startsWith("content-"))
        assertTrue(track.startsWith("text-"))
    }

    @Test
    fun `language and role suffixes remain distinct track metadata`() {
        val chinese =
            resolveSidecarTrackMetadata(
                mediaDisplayName = "episode01.mp4",
                subtitleDisplayName = "episode01.zh-Hans.forced.srt",
            )
        val english =
            resolveSidecarTrackMetadata(
                mediaDisplayName = "episode01.mp4",
                subtitleDisplayName = "episode01.en.sdh.cc.vtt",
            )

        assertEquals("zh-hans", chinese?.language)
        assertEquals(listOf(TimedTextRole.Forced), chinese?.roles)
        assertEquals(TimedTextMatchBasis.LanguageSuffix, chinese?.matchBasis)
        assertEquals("en", english?.language)
        assertEquals(
            listOf(TimedTextRole.Sdh, TimedTextRole.ClosedCaptions),
            english?.roles,
        )
        assertNull(
            resolveSidecarTrackMetadata(
                mediaDisplayName = "episode01.mp4",
                subtitleDisplayName = "different.zh.srt",
            ),
        )
    }

    @Test
    fun `multiple language candidates are all retained without implicit selection`() {
        val chinese = track("zh", TimedTextMatchBasis.LanguageSuffix, language = "zh")
        val english = track("en", TimedTextMatchBasis.LanguageSuffix, language = "en")

        val merged = TextTrackCatalogState().mergeTracks(listOf(chinese, english))

        assertEquals(setOf("zh", "en"), merged.tracks.map { it.stableId }.toSet())
        assertNull(merged.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Automatic, merged.activeText.primaryMode)
    }

    @Test
    fun `a unique exact sidecar becomes the automatic primary`() {
        val exact = track("exact", TimedTextMatchBasis.ExactStem)

        val merged = TextTrackCatalogState().mergeTracks(listOf(exact))

        assertEquals(exact.stableId, merged.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Automatic, merged.activeText.primaryMode)
    }

    @Test
    fun `manual primary remains selected when automatic candidates change`() {
        val manual = track("manual", TimedTextMatchBasis.UserSelected)
        val selected = TextTrackCatalogState().selectManualPrimary(manual)
        val exact = track("exact", TimedTextMatchBasis.ExactStem)
        val embeddedDefault =
            track(
                id = "embedded",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
                roles = listOf(TimedTextRole.Default),
            )

        val merged = selected.mergeTracks(listOf(exact, embeddedDefault))

        assertEquals(manual.stableId, merged.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Manual, merged.activeText.primaryMode)
        assertEquals(manual.stableId, merged.tracks.first().stableId)
    }

    @Test
    fun `manually selecting an existing lower ranked track moves it first`() {
        val embeddedDefault =
            track(
                id = "embedded",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
                roles = listOf(TimedTextRole.Default),
            )
        val languageTrack =
            track("language", TimedTextMatchBasis.LanguageSuffix, language = "zh")
        val catalog = TextTrackCatalogState().mergeTracks(listOf(embeddedDefault, languageTrack))

        val selected = catalog.selectManualPrimary(languageTrack)

        assertEquals(languageTrack.stableId, selected.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Manual, selected.activeText.primaryMode)
        assertEquals(languageTrack.stableId, selected.tracks.first().stableId)
    }

    @Test
    fun `manual removal prevents a later automatic candidate from reappearing`() {
        val removed = TextTrackCatalogState().selectManualPrimary(null)
        val exact = track("exact", TimedTextMatchBasis.ExactStem)

        val merged = removed.mergeTracks(listOf(exact))

        assertNull(merged.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Manual, merged.activeText.primaryMode)
    }

    @Test
    fun `one embedded default wins automatic selection without hiding sidecars`() {
        val embedded =
            track(
                id = "embedded",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
                roles = listOf(TimedTextRole.Default),
            )
        val sidecar = track("sidecar", TimedTextMatchBasis.LanguageSuffix, language = "zh")

        val merged = TextTrackCatalogState().mergeTracks(listOf(sidecar, embedded))

        assertEquals(embedded.stableId, merged.activeText.primaryTrackId)
        assertEquals(setOf(sidecar.stableId, embedded.stableId), merged.tracks.map { it.stableId }.toSet())
    }

    @Test
    fun `rejected exact candidate is not automatically reactivated`() {
        val exact = track("exact", TimedTextMatchBasis.ExactStem)
        val initial =
            TextTrackCatalogState(
                activeText = ActiveTextSelection(rejectedTrackIds = listOf(exact.stableId)),
            )

        val merged = initial.mergeTracks(listOf(exact))

        assertNull(merged.activeText.primaryTrackId)
        assertEquals(listOf(exact.stableId), merged.activeText.rejectedTrackIds)
    }

    @Test
    fun `rejected decision survives while its candidate is absent`() {
        val exact = track("exact", TimedTextMatchBasis.ExactStem)
        val rejected =
            TextTrackCatalogState(
                activeText = ActiveTextSelection(rejectedTrackIds = listOf(exact.stableId)),
            ).mergeTracks(emptyList())

        val rediscovered = rejected.mergeTracks(listOf(exact))

        assertEquals(listOf(exact.stableId), rediscovered.activeText.rejectedTrackIds)
        assertNull(rediscovered.activeText.primaryTrackId)
    }

    @Test
    fun `legacy subtitle becomes a stable track with cached source intact`() {
        val track =
            legacyTimedTextTrack(
                contentId = "content-legacy",
                uriValue = "content://subtitle/legacy",
                displayName = "legacy.ass",
                mimeType = "text/x-ssa",
                cachedFileName = "cached.ass",
            )
        val selected = TextTrackCatalogState().selectManualPrimary(track)

        assertEquals(TimedTextMatchBasis.Legacy, track.matchBasis)
        assertEquals("cached.ass", track.locator.cachedFileName)
        assertEquals(track.stableId, selected.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Manual, selected.activeText.primaryMode)
    }

    private fun track(
        id: String,
        matchBasis: TimedTextMatchBasis,
        language: String? = null,
        origin: TimedTextOrigin = TimedTextOrigin.Sidecar,
        roles: List<TimedTextRole> = emptyList(),
    ): TimedTextTrack =
        TimedTextTrack(
            stableId = id,
            origin = origin,
            locator =
                TimedTextLocator(
                    uriValue = "content://subtitle/$id",
                    displayName = "$id.srt",
                ),
            format = "srt",
            language = language,
            roles = roles,
            matchBasis = matchBasis,
        )
}
