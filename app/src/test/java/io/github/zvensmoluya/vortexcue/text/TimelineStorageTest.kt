package io.github.zvensmoluya.vortexcue.text

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase_Impl
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TimelineStorageTest {
    private lateinit var database: VortexDatabase
    private lateinit var artifactStore: TestTextArtifactStore
    private lateinit var storage: TextStorageRepository
    private lateinit var reader: TimelineRevisionReader
    private var clock = 1L

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<VortexDatabase> { VortexDatabase_Impl() }
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        artifactStore = TestTextArtifactStore()
        storage = TextStorageRepository(database, artifactStore, nowMs = { clock++ })
        reader = TimelineRevisionReader(storage)
        runBlocking { database.contentDao().upsertContent(ContentEntity(ContentId, 1L)) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `revision publication is idempotent and partial reader reuses work artifacts`() = runBlocking {
        val first = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(0L, 1_000L, "one")))
        val second = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(900L, 2_000L, "two")))
        val firstArtifact = storage.publishTimelineArtifact(TextArtifactKind.AsrWorkUnitOutput, first)
        val secondArtifact = storage.publishTimelineArtifact(TextArtifactKind.AsrWorkUnitOutput, second)
        val assembled = TimelineAssembler.assemblePartial(listOf(first, second))
        val revision =
            storage.createRevision(
                contentId = ContentId,
                role = TimelineRole.Source,
                completeness = TimelineCompleteness.Partial,
                artifacts =
                    listOf(
                        RevisionArtifactRole.AsrWorkUnit to firstArtifact,
                        RevisionArtifactRole.AsrWorkUnit to secondArtifact,
                    ),
                contentHash = canonicalContentHash(assembled),
                cueCount = assembled.cues.size,
                coveredEndMs = assembled.cues.maxOf(CanonicalTextCue::endMs),
            )
        val repeated =
            storage.createRevision(
                contentId = ContentId,
                role = TimelineRole.Source,
                completeness = TimelineCompleteness.Partial,
                artifacts =
                    listOf(
                        RevisionArtifactRole.AsrWorkUnit to firstArtifact,
                        RevisionArtifactRole.AsrWorkUnit to secondArtifact,
                    ),
                contentHash = canonicalContentHash(assembled),
                cueCount = assembled.cues.size,
                coveredEndMs = assembled.cues.maxOf(CanonicalTextCue::endMs),
            )

        assertEquals(revision, repeated)
        assertEquals(assembled, reader.read(revision.revisionId))
        assertEquals(2, database.textStorageDao().revisionArtifacts(revision.revisionId).size)
    }

    @Test
    fun `translation delta is pinned to one source revision`() = runBlocking {
        val sourceTimeline = CanonicalTextNormalizer.normalizeGenerated(listOf(SubtitleCue(0L, 1_000L, "source")))
        val sourceArtifact = storage.publishTimelineArtifact(TextArtifactKind.FinalTimeline, sourceTimeline)
        val sourceRevision =
            storage.createRevision(
                contentId = ContentId,
                role = TimelineRole.Source,
                completeness = TimelineCompleteness.Final,
                artifacts = listOf(RevisionArtifactRole.Timeline to sourceArtifact),
                contentHash = canonicalContentHash(sourceTimeline),
                cueCount = 1,
                coveredEndMs = 1_000L,
            )
        val targetTimeline =
            CanonicalTimeline(sourceTimeline.cues.map { it.copy(text = "target") })
        val delta =
            storage.publishTranslationDelta(
                TranslationDelta(
                    sourceRevisionId = sourceRevision.revisionId,
                    sourceRevisionHash = sourceRevision.contentHash,
                    entries = targetTimeline.cues.map { TranslationDeltaEntry(it.cueId, it.text) },
                ),
            )
        val targetRevision =
            storage.createRevision(
                contentId = ContentId,
                role = TimelineRole.Target,
                completeness = TimelineCompleteness.Final,
                artifacts = listOf(RevisionArtifactRole.TranslationDelta to delta),
                contentHash = canonicalContentHash(targetTimeline),
                cueCount = 1,
                coveredEndMs = 1_000L,
                sourceRevision = sourceRevision,
            )
        assertEquals(targetTimeline, reader.read(targetRevision.revisionId))

        val wrongDelta =
            storage.publishTranslationDelta(
                TranslationDelta(
                    sourceRevisionId = sourceRevision.revisionId,
                    sourceRevisionHash = "0".repeat(64),
                    entries = targetTimeline.cues.map { TranslationDeltaEntry(it.cueId, "wrong") },
                ),
            )
        val wrongTarget =
            storage.createRevision(
                contentId = ContentId,
                role = TimelineRole.Target,
                completeness = TimelineCompleteness.Final,
                artifacts = listOf(RevisionArtifactRole.TranslationDelta to wrongDelta),
                contentHash = canonicalContentHash(targetTimeline),
                cueCount = 1,
                coveredEndMs = 1_000L,
                sourceRevision = sourceRevision,
            )
        assertThrows(IOException::class.java) { runBlocking { reader.read(wrongTarget.revisionId) } }
        Unit
    }

    private companion object {
        const val ContentId = "content"
    }
}
