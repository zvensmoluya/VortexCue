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
        val firstIdentity = mediaIdentityKey("content://media/item/1")
        val differentIdentity = mediaIdentityKey("content://media/item/2")
        val first = stableContentId(firstIdentity)
        val repeated = stableContentId(firstIdentity)
        val different = stableContentId(differentIdentity)
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
    fun `document and tree uris share strong identity but remain separate locators`() {
        val documentUri =
            "content://com.android.externalstorage.documents/" +
                "document/primary%3AMovies%2Fsample.mp4"
        val treeUri =
            "content://com.android.externalstorage.documents/" +
                "tree/primary%3AMovies/document/primary%3aMovies%2fsample.mp4"

        val documentIdentity = mediaIdentityKey(documentUri)
        val treeIdentity = mediaIdentityKey(treeUri)

        assertEquals(documentIdentity, treeIdentity)
        assertEquals(stableContentId(documentIdentity), stableContentId(treeIdentity))
        assertFalse(
            stableMediaLocatorId(documentUri, "loose-files") ==
                stableMediaLocatorId(treeUri, "folder:movies"),
        )
    }

    @Test
    fun `lookalike uris do not merge without provider identity`() {
        val first = mediaIdentityKey("content://downloads/files/sample.mp4")
        val second = mediaIdentityKey("content://archive/files/sample.mp4")

        assertFalse(first == second)
        assertFalse(stableContentId(first) == stableContentId(second))
    }

    @Test
    fun `locator loss keeps referenced content or an available alternative`() {
        assertTrue(
            shouldRetainContentAfterLocatorLoss(
                hasRetainedReference = true,
                hasAvailableLocator = false,
            ),
        )
        assertTrue(
            shouldRetainContentAfterLocatorLoss(
                hasRetainedReference = false,
                hasAvailableLocator = true,
            ),
        )
        assertFalse(
            shouldRetainContentAfterLocatorLoss(
                hasRetainedReference = false,
                hasAvailableLocator = false,
            ),
        )
    }

    @Test
    fun `an unavailable active locator falls back without losing the last known route`() {
        assertEquals(
            "folder",
            resolveActiveMediaLocatorId(
                locatorAvailability =
                    listOf(
                        "single" to ContentAvailability.Unavailable,
                        "folder" to ContentAvailability.Available,
                    ),
                currentActiveId = "single",
                preferredActiveId = null,
            ),
        )
        assertEquals(
            "single",
            resolveActiveMediaLocatorId(
                locatorAvailability =
                    listOf(
                        "single" to ContentAvailability.Unavailable,
                        "folder" to ContentAvailability.Unavailable,
                    ),
                currentActiveId = "single",
                preferredActiveId = null,
            ),
        )
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
    fun `subtitle extension appended to full media name is an exact match`() {
        val wav =
            resolveSidecarTrackMetadata(
                mediaDisplayName = "01_episode.wav",
                subtitleDisplayName = "01_episode.wav.vtt",
            )
        val mp3 =
            resolveSidecarTrackMetadata(
                mediaDisplayName = "01_episode.mp3",
                subtitleDisplayName = "01_episode.mp3.srt",
            )

        assertEquals(TimedTextMatchBasis.ExactStem, wav?.matchBasis)
        assertNull(wav?.language)
        assertNull(wav?.label)
        assertEquals(TimedTextMatchBasis.ExactStem, mp3?.matchBasis)
        assertNull(mp3?.language)
        assertNull(mp3?.label)
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
    fun `primary and secondary selections remain distinct and ordered`() {
        val primary = track("primary", TimedTextMatchBasis.LanguageSuffix, language = "zh")
        val secondary = track("secondary", TimedTextMatchBasis.LanguageSuffix, language = "en")
        val catalog =
            TextTrackCatalogState()
                .mergeTracks(listOf(secondary, primary))
                .selectManualTrack(TextTrackSlot.Primary, primary)
                .selectManualTrack(TextTrackSlot.Secondary, secondary)

        assertEquals(primary.stableId, catalog.activeText.primaryTrackId)
        assertEquals(secondary.stableId, catalog.activeText.secondaryTrackId)
        assertEquals(
            listOf(primary.stableId, secondary.stableId),
            catalog.tracks.take(2).map(TimedTextTrack::stableId),
        )
    }

    @Test
    fun `one track cannot be selected as both primary and secondary`() {
        val track = track("shared", TimedTextMatchBasis.ExactStem)
        val catalog =
            TextTrackCatalogState()
                .selectManualTrack(TextTrackSlot.Primary, track)
                .selectManualTrack(TextTrackSlot.Secondary, track)

        assertEquals(track.stableId, catalog.activeText.primaryTrackId)
        assertNull(catalog.activeText.secondaryTrackId)
    }

    @Test
    fun `newly selected embedded track replaces an embedded track in the other slot`() {
        val chinese =
            track(
                id = "embedded-zh",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
            )
        val english =
            track(
                id = "embedded-en",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
            )
        val catalog =
            TextTrackCatalogState()
                .mergeTracks(listOf(chinese, english))
                .selectManualTrack(TextTrackSlot.Primary, chinese)
                .selectManualTrack(TextTrackSlot.Secondary, english)

        assertNull(catalog.activeText.primaryTrackId)
        assertEquals(english.stableId, catalog.activeText.secondaryTrackId)
    }

    @Test
    fun `one embedded and one sidecar can remain selected together`() {
        val embedded =
            track(
                id = "embedded",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
            )
        val sidecar = track("sidecar", TimedTextMatchBasis.LanguageSuffix)
        val catalog =
            TextTrackCatalogState()
                .mergeTracks(listOf(embedded, sidecar))
                .selectManualTrack(TextTrackSlot.Primary, embedded)
                .selectManualTrack(TextTrackSlot.Secondary, sidecar)

        assertEquals(embedded.stableId, catalog.activeText.primaryTrackId)
        assertEquals(sidecar.stableId, catalog.activeText.secondaryTrackId)
    }

    @Test
    fun `merging normalizes a duplicated stored primary and secondary`() {
        val shared = track("shared", TimedTextMatchBasis.ExactStem)
        val catalog =
            TextTrackCatalogState(
                activeText =
                    ActiveTextSelection(
                        primaryTrackId = shared.stableId,
                        secondaryTrackId = shared.stableId,
                    ),
            ).mergeTracks(listOf(shared))

        assertEquals(shared.stableId, catalog.activeText.primaryTrackId)
        assertNull(catalog.activeText.secondaryTrackId)
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
    fun `restoring automatic selection chooses the usable exact track`() {
        val exact = track("exact", TimedTextMatchBasis.ExactStem)
        val manualOff =
            TextTrackCatalogState()
                .mergeTracks(listOf(exact))
                .selectManualTrack(TextTrackSlot.Primary, null)

        val restored = manualOff.restoreAutomaticTrack(TextTrackSlot.Primary)

        assertEquals(exact.stableId, restored.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Automatic, restored.activeText.primaryMode)
    }

    @Test
    fun `automatic primary never duplicates the selected secondary`() {
        val exact = track("exact", TimedTextMatchBasis.ExactStem)
        val secondaryOnly =
            TextTrackCatalogState()
                .mergeTracks(listOf(exact))
                .selectManualTrack(TextTrackSlot.Primary, null)
                .selectManualTrack(TextTrackSlot.Secondary, exact)

        val restored = secondaryOnly.restoreAutomaticTrack(TextTrackSlot.Primary)

        assertNull(restored.activeText.primaryTrackId)
        assertEquals(exact.stableId, restored.activeText.secondaryTrackId)
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
    fun `failed automatic track is deselected and exposes a repair state`() {
        val exact = track("exact", TimedTextMatchBasis.ExactStem)
        val selected = TextTrackCatalogState().mergeTracks(listOf(exact))

        val failed =
            selected.withTrackReadiness(
                trackId = exact.stableId,
                readiness = TimedTextReadiness.ParseFailed,
                failureMessage = "无法解析字幕",
            )

        assertNull(failed.activeText.primaryTrackId)
        assertEquals(TimedTextReadiness.ParseFailed, failed.tracks.single().readiness)
        assertEquals("无法解析字幕", failed.tracks.single().failureMessage)
    }

    @Test
    fun `failed manual track remains selected for explicit repair`() {
        val manual = track("manual", TimedTextMatchBasis.UserSelected)
        val selected = TextTrackCatalogState().selectManualPrimary(manual)

        val failed =
            selected.withTrackReadiness(
                trackId = manual.stableId,
                readiness = TimedTextReadiness.Unreadable,
                failureMessage = "字幕文件不可用",
            )

        assertEquals(manual.stableId, failed.activeText.primaryTrackId)
        assertEquals(TextSelectionMode.Manual, failed.activeText.primaryMode)
        assertEquals("字幕文件不可用", failed.tracks.single().failureMessage)
    }

    @Test
    fun `embedded discovery marks a missing automatic track unavailable`() {
        val embedded =
            track(
                id = "embedded",
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                origin = TimedTextOrigin.Embedded,
                roles = listOf(TimedTextRole.Default),
            )
        val selected = TextTrackCatalogState().mergeTracks(listOf(embedded))

        val missing = selected.reconcileEmbeddedTracks(emptyList())

        assertNull(missing.activeText.primaryTrackId)
        assertEquals(TimedTextReadiness.Unreadable, missing.tracks.single().readiness)
        assertEquals("当前媒体未发现此内嵌文字轨", missing.tracks.single().failureMessage)
    }

    @Test
    fun `rekeying a merged catalog keeps primary secondary and rejection decisions`() {
        val primary = track("primary", TimedTextMatchBasis.UserSelected)
        val secondary = track("secondary", TimedTextMatchBasis.LanguageSuffix)
        val rejected = track("rejected", TimedTextMatchBasis.LanguageSuffix)
        val catalog =
            TextTrackCatalogState(
                tracks = listOf(primary, secondary, rejected),
                activeText =
                    ActiveTextSelection(
                        primaryTrackId = primary.stableId,
                        secondaryTrackId = secondary.stableId,
                        primaryMode = TextSelectionMode.Manual,
                        secondaryMode = TextSelectionMode.Manual,
                        rejectedTrackIds = listOf(rejected.stableId),
                    ),
            )

        val rekeyed = catalog.rekeyForContent("content-relinked")

        assertFalse(rekeyed.tracks.map(TimedTextTrack::stableId).contains(primary.stableId))
        assertEquals(rekeyed.tracks[0].stableId, rekeyed.activeText.primaryTrackId)
        assertEquals(rekeyed.tracks[1].stableId, rekeyed.activeText.secondaryTrackId)
        assertTrue(rekeyed.activeText.rejectedTrackIds.single() in rekeyed.tracks.map { it.stableId })
        assertEquals(TextSelectionMode.Manual, rekeyed.activeText.primaryMode)
        assertEquals(TextSelectionMode.Manual, rekeyed.activeText.secondaryMode)
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
                TimedTextLocator.Revision(
                    revisionId = "revision-$id",
                    displayName = "$id.srt",
                ),
            format = "srt",
            language = language,
            roles = roles,
            matchBasis = matchBasis,
        )
}
