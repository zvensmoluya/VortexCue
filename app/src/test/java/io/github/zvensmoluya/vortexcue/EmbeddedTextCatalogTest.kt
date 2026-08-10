package io.github.zvensmoluya.vortexcue.library

import io.github.zvensmoluya.vortexcue.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedTextCatalogTest {
    @Test
    fun `stable identity prefers container ids over discovery indexes`() {
        val first = metadata(groupIndex = 1, trackIndex = 2)
        val reordered = first.copy(groupIndex = 9, trackIndex = 7)

        assertEquals(
            embeddedTextTrackIdentity(first),
            embeddedTextTrackIdentity(reordered),
        )
    }

    @Test
    fun `stable identity falls back to discovery indexes when ids are absent`() {
        val first = metadata(groupId = null, formatId = null, groupIndex = 1, trackIndex = 2)
        val other = first.copy(trackIndex = 3)

        assertFalse(embeddedTextTrackIdentity(first) == embeddedTextTrackIdentity(other))
    }

    @Test
    fun `text metadata becomes a ready embedded candidate with language and roles`() {
        val candidate =
            buildEmbeddedTextTrackCandidate(
                mediaUriValue = "content://media/episode",
                metadata =
                    metadata(
                        mimeType = "application/x-subrip",
                        language = "zh-Hans",
                        label = "简体中文",
                        isDefault = true,
                        isForced = true,
                        isSdh = true,
                        isClosedCaptions = true,
                    ),
            )

        val track = requireNotNull(candidate).track
        assertEquals(TimedTextOrigin.Embedded, track.origin)
        assertEquals("srt", track.format)
        assertEquals("zh-Hans", track.language)
        assertEquals("简体中文", track.label)
        assertEquals(
            listOf(
                TimedTextRole.Default,
                TimedTextRole.Forced,
                TimedTextRole.Sdh,
                TimedTextRole.ClosedCaptions,
            ),
            track.roles,
        )
        assertEquals(TimedTextReadiness.Ready, track.readiness)
        assertTrue(track.locator.stableIdentity.startsWith("embedded:media3:"))
    }

    @Test
    fun `unsupported text track remains visible with a recoverable state`() {
        val candidate =
            buildEmbeddedTextTrackCandidate(
                mediaUriValue = "content://media/episode",
                metadata = metadata(isSupported = false),
            )

        val track = requireNotNull(candidate).track
        assertEquals(TimedTextReadiness.Unreadable, track.readiness)
        assertEquals("当前设备不支持此内嵌文字轨", track.failureMessage)
    }

    @Test
    fun `graphical subtitle formats are not exposed as glow text tracks`() {
        listOf(
            "application/pgs",
            "application/vobsub",
            "application/dvbsubs",
        ).forEach { mimeType ->
            assertNull(
                buildEmbeddedTextTrackCandidate(
                    mediaUriValue = "content://media/episode",
                    metadata = metadata(mimeType = mimeType),
                ),
            )
        }
    }

    private fun metadata(
        groupId: String? = "subtitle-group",
        formatId: String? = "subtitle-format",
        groupIndex: Int = 0,
        trackIndex: Int = 0,
        mimeType: String? = "text/vtt",
        language: String? = "en",
        label: String? = "English",
        isDefault: Boolean = false,
        isForced: Boolean = false,
        isSdh: Boolean = false,
        isClosedCaptions: Boolean = false,
        isSupported: Boolean = true,
    ): EmbeddedTextTrackMetadata =
        EmbeddedTextTrackMetadata(
            groupId = groupId,
            formatId = formatId,
            groupIndex = groupIndex,
            trackIndex = trackIndex,
            mimeType = mimeType,
            language = language,
            label = label,
            isDefault = isDefault,
            isForced = isForced,
            isSdh = isSdh,
            isClosedCaptions = isClosedCaptions,
            isSupported = isSupported,
        )
}
