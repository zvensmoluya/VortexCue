package io.github.zvensmoluya.vortexcue.text

import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalTextCodecTest {
    @Test
    fun `supported import formats normalize into the same canonical model`() {
        val inputs =
            listOf(
                "sample.srt" to "1\n00:00:01,000 --> 00:00:02,000\nHello\n",
                "sample.vtt" to "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n",
                "sample.lrc" to "[00:01.00]Hello\n[00:02.00]Next",
                "sample.ass" to
                    "[Events]\nFormat: Start, End, Style, Text\n" +
                    "Dialogue: 0:00:01.00,0:00:02.00,Default,{\\i1}Hello{\\i0}\n",
            )

        val timelines =
            inputs.map { (name, content) ->
                CanonicalTextNormalizer.normalizeImported(
                    SubtitleParser.parse(name, content.encodeToByteArray()),
                    mediaDurationMs = 7_000L,
                )
            }

        assertEquals("Hello", timelines[0].cues.single().text)
        assertEquals(timelines[0].cues.single(), timelines[1].cues.single())
        assertEquals("Hello", timelines[2].cues.first().text)
        assertEquals("Hello", timelines[3].cues.single().text)
    }

    @Test
    fun `lrc tail uses media duration or versioned fallback and clips imported cues`() {
        val parsed = SubtitleParser.parse("sample.lrc", "[00:01.00]First\n[00:03.00]Last".encodeToByteArray())
        val knownDuration = CanonicalTextNormalizer.normalizeImported(parsed, 4_200L)
        val unknownDuration = CanonicalTextNormalizer.normalizeImported(parsed, null)

        assertEquals(4_200L, knownDuration.cues.last().endMs)
        assertEquals(8_000L, unknownDuration.cues.last().endMs)
        assertEquals(CanonicalTextNormalizer.DefaultLrcTailMs, unknownDuration.cues.last().endMs - 3_000L)

        val clipped =
            CanonicalTextNormalizer.normalizeImported(
                listOf(SubtitleCue(1_000L, 9_000L, "clip")),
                mediaDurationMs = 5_000L,
            )
        assertEquals(5_000L, clipped.cues.single().endMs)
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalTextNormalizer.normalizeImported(
                listOf(SubtitleCue(5_000L, 6_000L, "outside")),
                mediaDurationMs = 5_000L,
            )
        }
    }

    @Test
    fun `cue ids are stable across input order and distinguish duplicates`() {
        val first = SubtitleCue(2_000L, 3_000L, "second")
        val duplicate = SubtitleCue(1_000L, 1_500L, "same")
        val forward = CanonicalTextNormalizer.normalizeImported(listOf(first, duplicate, duplicate), null)
        val reversed = CanonicalTextNormalizer.normalizeImported(listOf(duplicate, duplicate, first), null)

        assertEquals(forward, reversed)
        assertNotEquals(forward.cues[0].cueId, forward.cues[1].cueId)
        assertEquals(1_000L, forward.cues[0].startMs)
    }

    @Test
    fun `binary envelope is deterministic and rejects version damage and truncation`() {
        val timeline =
            CanonicalTextNormalizer.normalizeImported(
                listOf(SubtitleCue(0L, 1_000L, "alpha"), SubtitleCue(800L, 1_500L, "overlap")),
                null,
            )
        val first = TextArtifactCodec.encode(TextArtifactKind.ImportedTimeline, TextArtifactPayload.Timeline(timeline))
        val second = TextArtifactCodec.encode(TextArtifactKind.ImportedTimeline, TextArtifactPayload.Timeline(timeline))

        assertEquals(first.artifactId, second.artifactId)
        assertArrayEquals(first.bytes, second.bytes)
        assertEquals(timeline, (TextArtifactCodec.decode(first.bytes).payload as TextArtifactPayload.Timeline).value)

        val unsupportedSchema = first.bytes.copyOf().also { it[11] = 2 }
        assertThrows(TextArtifactCodecException::class.java) { TextArtifactCodec.decode(unsupportedSchema) }
        assertThrows(TextArtifactCodecException::class.java) { TextArtifactCodec.decode(first.bytes.copyOf(first.bytes.size - 1)) }
        assertThrows(TextArtifactCodecException::class.java) { TextArtifactCodec.decode(first.bytes + 0) }
    }

    @Test
    fun `empty asr work unit round trips while final normalization stays nonempty`() {
        val workUnit = CanonicalTextNormalizer.normalizeAsrWorkUnit(emptyList())
        val encoded =
            TextArtifactCodec.encode(
                TextArtifactKind.AsrWorkUnitOutput,
                TextArtifactPayload.Timeline(workUnit),
            )

        assertEquals(
            workUnit,
            (TextArtifactCodec.decode(encoded.bytes).payload as TextArtifactPayload.Timeline).value,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalTextNormalizer.normalizeGenerated(emptyList())
        }
    }
}
