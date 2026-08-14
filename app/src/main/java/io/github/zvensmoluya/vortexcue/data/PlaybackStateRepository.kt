package io.github.zvensmoluya.vortexcue.data

import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.core.model.PlayerSurface
import io.github.zvensmoluya.vortexcue.data.db.CurrentPlaybackSessionEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentQueueEntryEntity
import io.github.zvensmoluya.vortexcue.data.db.PlaybackDao
import io.github.zvensmoluya.vortexcue.data.db.QueueItemRow
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class PlaybackStateRepository(
    private val database: VortexDatabase,
    private val contentRepository: ContentRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val playbackDao: PlaybackDao = database.playbackDao()
    private val contentDao = database.contentDao()

    val recovery: Flow<PlaybackRecoveryProjection> =
        combine(playbackDao.observeSession(), playbackDao.observeQueueItems()) { session, queue ->
            projection(session ?: emptySession(), queue)
        }

    suspend fun initialize() {
        database.withWriteTransaction {
            if (playbackDao.session() == null) playbackDao.upsertSession(emptySession())
        }
    }

    suspend fun recovery(): PlaybackRecoveryProjection =
        database.withWriteTransaction {
            val session = playbackDao.session() ?: emptySession().also { playbackDao.upsertSession(it) }
            projection(session, playbackDao.queueItems())
        }

    suspend fun replace(
        contentIds: List<String>,
        currentIndex: Int = 0,
        positionMs: Long = 0L,
    ): PlaybackRecoveryProjection =
        database.withWriteTransaction {
            val canonical = canonicalExisting(contentIds)
            val index = currentIndex.coerceIn(0, (canonical.size - 1).coerceAtLeast(0))
            writeStructure(
                contentIds = canonical,
                currentContentId = canonical.getOrNull(index),
                positionMs = positionMs,
                durationMs = 0L,
            )
        }

    suspend fun append(contentIds: List<String>): PlaybackRecoveryProjection =
        database.withWriteTransaction {
            val session = sessionInside()
            val current = playbackDao.queueEntries().map { it.contentId }
            val appended = canonicalExisting(contentIds)
            for (contentId in appended) contentDao.clearDraftIfContent(contentId)
            val merged = (current + appended).distinct()
            if (merged == current) {
                return@withWriteTransaction projection(session, playbackDao.queueItems())
            }
            writeStructure(
                contentIds = merged,
                currentContentId = session.currentContentId ?: merged.firstOrNull(),
                positionMs = session.positionMs,
                durationMs = session.durationMs,
            )
        }

    suspend fun remove(contentId: String): PlaybackRecoveryProjection =
        database.withWriteTransaction {
            val canonical = contentRepository.canonicalContentId(contentId)
            val session = sessionInside()
            val previous = playbackDao.queueEntries().map { it.contentId }
            val removedIndex = previous.indexOf(canonical)
            if (removedIndex < 0) return@withWriteTransaction projection(session, playbackDao.queueItems())
            val retained = previous.filterNot { it == canonical }
            val nextCurrent =
                when {
                    retained.isEmpty() -> null
                    session.currentContentId != canonical && session.currentContentId in retained -> session.currentContentId
                    else -> retained[removedIndex.coerceAtMost(retained.lastIndex)]
                }
            writeStructure(
                contentIds = retained,
                currentContentId = nextCurrent,
                positionMs = if (nextCurrent == session.currentContentId) session.positionMs else 0L,
                durationMs = if (nextCurrent == session.currentContentId) session.durationMs else 0L,
            )
        }

    suspend fun move(fromIndex: Int, toIndex: Int): PlaybackRecoveryProjection =
        database.withWriteTransaction {
            val session = sessionInside()
            val queue = playbackDao.queueEntries().map { it.contentId }.toMutableList()
            if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) {
                return@withWriteTransaction projection(session, playbackDao.queueItems())
            }
            val moved = queue.removeAt(fromIndex)
            queue.add(toIndex, moved)
            writeStructure(
                contentIds = queue,
                currentContentId = session.currentContentId,
                positionMs = session.positionMs,
                durationMs = session.durationMs,
            )
        }

    suspend fun clear(): PlaybackRecoveryProjection =
        database.withWriteTransaction {
            writeStructure(
                contentIds = emptyList(),
                currentContentId = null,
                positionMs = 0L,
                durationMs = 0L,
            )
        }

    suspend fun setCurrent(
        contentId: String?,
        positionMs: Long,
        durationMs: Long,
        expectedRevision: Long,
    ): PlaybackRecoveryProjection? =
        database.withWriteTransaction {
            val session = sessionInside()
            if (session.queueRevision != expectedRevision) return@withWriteTransaction null
            val canonical = contentId?.let { contentRepository.canonicalContentId(it) }
            val queue = playbackDao.queueEntries().map { it.contentId }
            if (canonical != null && canonical !in queue) return@withWriteTransaction null
            val nextRevision =
                if (canonical == session.currentContentId) {
                    session.queueRevision
                } else {
                    session.queueRevision + 1L
                }
            val updated =
                session.copy(
                    currentContentId = canonical,
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    queueRevision = nextRevision,
                    updatedAtMs = nowMs(),
                )
            playbackDao.upsertSession(updated)
            projection(updated, playbackDao.queueItems())
        }

    suspend fun updatePlaybackParameters(
        playbackSpeed: Float,
        shuffleEnabled: Boolean,
        repeatMode: Int,
    ) {
        database.withWriteTransaction {
            val session = sessionInside()
            playbackDao.upsertSession(
                session.copy(
                    playbackSpeed = playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    updatedAtMs = nowMs(),
                ),
            )
        }
    }

    suspend fun updateProgress(
        contentId: String?,
        positionMs: Long,
        durationMs: Long,
        playbackSpeed: Float,
        expectedRevision: Long,
    ): Boolean =
        playbackDao.updateProgress(
            contentId = contentId,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            playbackSpeed = playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f,
            expectedRevision = expectedRevision,
            updatedAtMs = nowMs(),
        ) == 1

    suspend fun setLastSurface(surface: PlayerSurface) {
        playbackDao.updateLastSurface(surface.asLastSurface().name, nowMs())
    }

    private suspend fun writeStructure(
        contentIds: List<String>,
        currentContentId: String?,
        positionMs: Long,
        durationMs: Long,
    ): PlaybackRecoveryProjection {
        val previous = sessionInside()
        playbackDao.clearQueueEntries()
        val timestamp = nowMs()
        playbackDao.upsertQueueEntries(
            contentIds.distinct().mapIndexed { index, contentId ->
                CurrentQueueEntryEntity(contentId = contentId, position = index, addedAtMs = timestamp)
            },
        )
        for (contentId in contentIds) contentDao.clearDraftIfContent(contentId)
        val current = currentContentId?.takeIf { it in contentIds }
        val updated =
            previous.copy(
                currentContentId = current,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                queueRevision = previous.queueRevision + 1L,
                updatedAtMs = timestamp,
            )
        playbackDao.upsertSession(updated)
        return projection(updated, playbackDao.queueItems())
    }

    private suspend fun canonicalExisting(contentIds: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (contentId in contentIds) {
            val canonical = contentRepository.canonicalContentId(contentId)
            if (contentDao.content(canonical) != null && canonical !in result) result += canonical
        }
        return result
    }

    private suspend fun sessionInside(): CurrentPlaybackSessionEntity =
        playbackDao.session() ?: emptySession().also { playbackDao.upsertSession(it) }

    private fun emptySession(): CurrentPlaybackSessionEntity =
        CurrentPlaybackSessionEntity(updatedAtMs = nowMs())

    private fun projection(
        session: CurrentPlaybackSessionEntity,
        queue: List<QueueItemRow>,
    ): PlaybackRecoveryProjection {
        val items =
            queue.map { row ->
                val summary = row.toContentSummary()
                PlaybackItem(
                    contentId = summary.contentId,
                    locatorId = summary.locatorId,
                    media = summary.media,
                    availability = summary.availability,
                )
            }
        val currentIndex =
            items.indexOfFirst { it.contentId == session.currentContentId }
                .takeIf { it >= 0 }
                ?: 0
        return PlaybackRecoveryProjection(
            queue = items,
            currentIndex = currentIndex,
            positionMs = session.positionMs.coerceAtLeast(0L),
            durationMs = session.durationMs.coerceAtLeast(0L),
            playbackSpeed = session.playbackSpeed,
            shuffleEnabled = session.shuffleEnabled,
            repeatMode = session.repeatMode,
            lastSurface = PlayerSurface.restoreLastSurface(session.lastSurface),
            queueRevision = session.queueRevision,
        )
    }
}
