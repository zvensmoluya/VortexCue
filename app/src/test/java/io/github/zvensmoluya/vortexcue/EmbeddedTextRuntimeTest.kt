package io.github.zvensmoluya.vortexcue.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedTextRuntimeTest {
    @Test
    fun `runtime refresh retains a cue for the same media and selected track`() {
        val previous =
            EmbeddedTextRuntimeState(
                mediaUriValue = "content://media/episode",
                cueTrackId = "track-a",
                cueText = "Current cue",
            )

        val retained =
            EmbeddedTextRuntimeState(mediaUriValue = previous.mediaUriValue)
                .retainCueFrom(previous, selectedTrackId = "track-a")

        assertEquals("track-a", retained.cueTrackId)
        assertEquals("Current cue", retained.cueText)
    }

    @Test
    fun `runtime refresh clears a cue after media or track changes`() {
        val previous =
            EmbeddedTextRuntimeState(
                mediaUriValue = "content://media/episode",
                cueTrackId = "track-a",
                cueText = "Current cue",
            )

        listOf(
            EmbeddedTextRuntimeState(mediaUriValue = previous.mediaUriValue)
                .retainCueFrom(previous, selectedTrackId = "track-b"),
            EmbeddedTextRuntimeState(mediaUriValue = "content://media/next")
                .retainCueFrom(previous, selectedTrackId = "track-a"),
            EmbeddedTextRuntimeState(mediaUriValue = previous.mediaUriValue)
                .retainCueFrom(previous, selectedTrackId = null),
        ).forEach { refreshed ->
            assertNull(refreshed.cueTrackId)
            assertNull(refreshed.cueText)
        }
    }
}
