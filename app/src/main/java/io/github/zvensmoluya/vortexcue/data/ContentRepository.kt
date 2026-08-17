package io.github.zvensmoluya.vortexcue.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room3.withWriteTransaction
import io.github.zvensmoluya.vortexcue.core.model.ActiveTextSelection
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.SubtitleSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.mediaIdentityKey
import io.github.zvensmoluya.vortexcue.core.model.stableContentId
import io.github.zvensmoluya.vortexcue.core.model.stableTimedTextTrackId
import io.github.zvensmoluya.vortexcue.data.db.ActiveMediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentDao
import io.github.zvensmoluya.vortexcue.data.db.ContentDispositionEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentFolderEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentFolderMembershipEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentIdentityEntity
import io.github.zvensmoluya.vortexcue.data.db.ContentRedirectEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentPlaybackSessionEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentQueueEntryEntity
import io.github.zvensmoluya.vortexcue.data.db.CurrentDraftEntity
import io.github.zvensmoluya.vortexcue.data.db.RecentEntryEntity
import io.github.zvensmoluya.vortexcue.data.db.SourceEntity
import io.github.zvensmoluya.vortexcue.data.db.TextSelectionEntity
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.library.LibrarySourceIds
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind
import io.github.zvensmoluya.vortexcue.library.TextTrackCatalogState
import io.github.zvensmoluya.vortexcue.library.TimedTextTrackCandidate
import io.github.zvensmoluya.vortexcue.library.mergeTracks
import io.github.zvensmoluya.vortexcue.library.manualTimedTextTrack
import io.github.zvensmoluya.vortexcue.library.restoreAutomaticTrack
import io.github.zvensmoluya.vortexcue.library.selectManualTrack
import io.github.zvensmoluya.vortexcue.library.withTrackReadiness
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ContentRepository(
    private val database: VortexDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao: ContentDao = database.contentDao()

    /** Virtual folders are deliberately independent from source folders. */
    val contentFolders: Flow<List<ContentFolderSummary>> =
        dao.observeContentFolders().map { rows -> rows.map { it.toDomain() } }

    val ignoredContent: Flow<List<ContentSummary>> =
        dao.observeIgnored(IgnoredPreviewLimit).map { rows -> rows.map { it.toDomain() } }

    val sources: Flow<List<SourceSummary>> =
        dao.observeSourceSummaries().map { rows -> rows.map { it.toDomain() } }

    fun observeContent(
        folderId: String?,
        query: String = "",
        sort: ContentSort = ContentSort.RecentAdded,
    ): Flow<List<ContentSummary>> =
        dao.observeContent(folderId, query.trim(), sort.name).map { rows -> rows.map { it.toDomain() } }

    fun observeFolderContent(
        folderId: String?,
        query: String = "",
        sort: ContentSort = ContentSort.RecentAdded,
    ): Flow<List<ContentSummary>> = observeContent(folderId, query, sort)

    suspend fun createFolder(name: String): ContentFolderSummary = createContentFolder(name)

    suspend fun renameFolder(folderId: String, name: String) = renameContentFolder(folderId, name)

    suspend fun deleteFolder(folderId: String) = deleteContentFolder(folderId)

    suspend fun moveToFolder(contentId: String, folderId: String?) = moveContentToFolder(contentId, folderId)

    fun pagedCollected(): Flow<PagingData<ContentSummary>> =
        pager(dao::pagedCollected)

    fun pagedDiscovery(): Flow<PagingData<ContentSummary>> =
        pager(dao::pagedDiscovery)

    fun pagedRecent(): Flow<PagingData<ContentSummary>> =
        pager(dao::pagedRecent)

    fun pagedIgnored(): Flow<PagingData<ContentSummary>> =
        pager(dao::pagedIgnored)

    fun pagedSource(sourceId: String): Flow<PagingData<ContentSummary>> =
        pager { dao.pagedSource(sourceId) }

    fun pagedSearch(query: String): Flow<PagingData<ContentSummary>> =
        pager { dao.pagedSearch(query.trim()) }

    suspend fun createContentFolder(name: String): ContentFolderSummary {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank()) { "文件夹名称不能为空" }
        require(normalized.length <= 80) { "文件夹名称过长" }
        database.withWriteTransaction {
            require(dao.contentFolderByName(normalized) == null) { "文件夹已存在" }
            val timestamp = nowMs()
            dao.upsertContentFolder(
                ContentFolderEntity(
                    folderId = "folder:${idFactory()}",
                    name = normalized,
                    createdAtMs = timestamp,
                    updatedAtMs = timestamp,
                ),
            )
        }
        return requireNotNull(dao.contentFolders().firstOrNull { it.name == normalized }) { "文件夹创建失败" }.toDomain()
    }

    suspend fun renameContentFolder(folderId: String, name: String) {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank()) { "文件夹名称不能为空" }
        require(normalized.length <= 80) { "文件夹名称过长" }
        database.withWriteTransaction {
            val folder = requireNotNull(dao.contentFolder(folderId)) { "文件夹不存在" }
            val duplicate = dao.contentFolderByName(normalized)
            require(duplicate == null || duplicate.folderId == folderId) { "文件夹已存在" }
            dao.renameContentFolder(folderId, normalized, nowMs())
        }
    }

    /** Deleting a folder removes only memberships, returning all members to root. */
    suspend fun deleteContentFolder(folderId: String) {
        database.withWriteTransaction {
            requireNotNull(dao.contentFolder(folderId)) { "文件夹不存在" }
            dao.clearContentFolderMemberships(folderId)
            dao.deleteContentFolder(folderId)
        }
    }

    suspend fun moveContentToFolder(contentId: String, folderId: String?) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            requireContent(canonical)
            if (folderId == null) {
                dao.clearContentFolderMembership(canonical)
            } else {
                requireNotNull(dao.contentFolder(folderId)) { "文件夹不存在" }
                dao.upsertContentFolderMembership(
                    ContentFolderMembershipEntity(canonical, folderId, nowMs()),
                )
            }
        }
    }

    suspend fun folderForContent(contentId: String): String? =
        dao.contentFolderMembership(resolveRedirect(contentId))?.folderId

    fun observeSummary(contentId: String): Flow<ContentSummary?> =
        dao.observeSummary(contentId).map { it?.toDomain() }

    suspend fun summary(contentId: String): ContentSummary? =
        dao.summary(resolveRedirect(contentId))?.toDomain()

    suspend fun canonicalContentId(contentId: String): String = resolveRedirect(contentId)

    fun observeDetails(contentId: String): Flow<ContentDetails?> {
        val canonicalId = contentId
        val base =
            combine(
                dao.observeContent(canonicalId),
                dao.observeLocators(canonicalId),
                dao.observeActiveLocator(canonicalId),
            ) { content, locators, active -> DetailBase(content, locators, active) }
        return combine(
            base,
            dao.observeTextTracks(canonicalId),
            dao.observeTextRoles(canonicalId),
            dao.observeTextSelections(canonicalId),
            dao.observeDisposition(canonicalId),
        ) { current, tracks, roles, selections, disposition ->
            current.content ?: return@combine null
            val activeId = current.active?.locatorId ?: return@combine null
            if (current.locators.none { it.locatorId == activeId }) return@combine null
            val domainTracks = tracks.map { it.toDomain(roles) }
            ContentDetails(
                contentId = current.content.contentId,
                mediaLocators = current.locators.map { it.toDomain() },
                activeMediaLocatorId = activeId,
                timedTextTracks = domainTracks,
                activeText = selections.toActiveText(tracks),
                subtitleOffsetMs = current.content.subtitleOffsetMs,
                disposition =
                    disposition?.let {
                        if (it.isCollected) ContentDisposition.Collected else ContentDisposition.Ignored
                    },
            )
        }
    }

    suspend fun details(contentId: String): ContentDetails? =
        detailsInside(resolveRedirect(contentId))

    suspend fun contentIdFor(media: MediaSourceRef): String? =
        dao.contentIdForIdentity(mediaIdentityKey(media.uri.toString()))?.let { resolveRedirect(it) }

    suspend fun resolveOrCreate(
        media: MediaSourceRef,
        availability: ContentAvailability = ContentAvailability.Available,
    ): String =
        database.withWriteTransaction {
            upsertMediaInside(media, availability, seenGeneration = sourceGeneration(media.sourceId))
        }

    suspend fun relink(
        contentId: String,
        replacement: MediaSourceRef,
    ): String =
        database.withWriteTransaction {
            val targetId = resolveRedirect(contentId)
            requireContent(targetId)
            val identityKey = mediaIdentityKey(replacement.uri.toString())
            val collisionId = dao.contentIdForIdentity(identityKey)?.let { resolveRedirect(it) }
            if (collisionId != null && collisionId != targetId) {
                mergeContentInside(targetId, collisionId)
            }
            dao.upsertIdentity(ContentIdentityEntity(identityKey, targetId))
            ensureSourceInside(replacement)
            val locator =
                replacement.toEntity(
                    contentId = targetId,
                    availability = ContentAvailability.Available,
                    seenGeneration = sourceGeneration(replacement.sourceId),
                    updatedAtMs = nowMs(),
                )
            dao.upsertLocator(locator)
            dao.upsertActiveLocator(ActiveMediaLocatorEntity(targetId, locator.locatorId))
            targetId
        }

    internal suspend fun mergeIdentityCollision(
        primaryContentId: String,
        duplicateContentId: String,
    ): String =
        database.withWriteTransaction {
            val targetId = resolveRedirect(primaryContentId)
            val absorbedId = resolveRedirect(duplicateContentId)
            requireContent(targetId)
            requireContent(absorbedId)
            mergeContentInside(targetId, absorbedId)
            targetId
        }

    suspend fun collect(contentId: String) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            requireContent(canonical)
            dao.upsertDisposition(ContentDispositionEntity(canonical, true, nowMs()))
        }
    }

    suspend fun ignore(contentId: String) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            requireContent(canonical)
            dao.upsertDisposition(ContentDispositionEntity(canonical, false, nowMs()))
            dao.clearContentFolderMembership(canonical)
        }
    }

    suspend fun undecidedContentIdsForSource(sourceId: String): List<String> =
        dao.undecidedContentIdsForSource(sourceId)

    suspend fun importContent(
        contentIds: Collection<String>,
        folderId: String?,
        ignoredContentIds: Collection<String> = emptyList(),
    ) {
        database.withWriteTransaction {
            if (folderId != null) requireNotNull(dao.contentFolder(folderId)) { "文件夹不存在" }
            contentIds.distinct().forEach { contentId ->
                val canonical = resolveRedirect(contentId)
                requireContent(canonical)
                dao.upsertDisposition(ContentDispositionEntity(canonical, true, nowMs()))
                if (folderId == null) {
                    dao.clearContentFolderMembership(canonical)
                } else {
                    dao.upsertContentFolderMembership(
                        ContentFolderMembershipEntity(canonical, folderId, nowMs()),
                    )
                }
            }
            (ignoredContentIds - contentIds.toSet()).distinct().forEach { contentId ->
                val canonical = resolveRedirect(contentId)
                requireContent(canonical)
                dao.upsertDisposition(ContentDispositionEntity(canonical, false, nowMs()))
                dao.clearContentFolderMembership(canonical)
            }
        }
    }

    suspend fun clearDisposition(contentId: String) {
        database.withWriteTransaction {
            dao.deleteDisposition(resolveRedirect(contentId))
            garbageCollectInside()
        }
    }

    suspend fun recordOpened(contentId: String) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            requireContent(canonical)
            dao.upsertRecent(RecentEntryEntity(canonical, nowMs()))
            dao.trimRecent(RecentLimit)
        }
    }

    suspend fun setDraft(contentId: String?) {
        database.withWriteTransaction {
            if (contentId == null) {
                dao.clearDraft()
            } else {
                dao.upsertDraft(CurrentDraftEntity(contentId = resolveRedirect(contentId), updatedAtMs = nowMs()))
            }
        }
    }

    fun observeDraft(): Flow<String?> = dao.observeDraft().map { it?.contentId }

    suspend fun sourceDescriptors(): List<SourceScanDescriptor> =
        dao.sources().map {
            SourceScanDescriptor(
                sourceId = it.sourceId,
                kind = it.kind.enumValueOr(LibrarySourceKind.LooseFiles),
                rootUri = it.rootUri,
                displayName = it.displayName,
            )
        }

    suspend fun sourceDescriptorForRoot(rootUri: String): SourceScanDescriptor? =
        dao.sourceByRootUri(rootUri)?.let {
            SourceScanDescriptor(
                sourceId = it.sourceId,
                kind = it.kind.enumValueOr(LibrarySourceKind.Folder),
                rootUri = rootUri,
                displayName = it.displayName,
            )
        }

    suspend fun applyIncrementalScan(
        descriptor: SourceScanDescriptor,
        media: List<MediaSourceRef>,
        textCandidates: List<TimedTextTrackCandidate> = emptyList(),
        hasAccess: Boolean = true,
        warning: String? = null,
    ) {
        database.withWriteTransaction {
            val previous = dao.source(descriptor.sourceId)
            val timestamp = nowMs()
            val generation = previous?.generation ?: 0L
            dao.upsertSource(
                SourceEntity(
                    sourceId = descriptor.sourceId,
                    kind = descriptor.kind.name,
                    rootUri = descriptor.rootUri,
                    displayName = descriptor.displayName,
                    hasAccess = hasAccess,
                    generation = generation,
                    createdAtMs = previous?.createdAtMs ?: timestamp,
                    lastIndexedAtMs = previous?.lastIndexedAtMs,
                    lastError = warning,
                    removedAtMs = null,
                ),
            )
            val contentByUri = mutableMapOf<String, String>()
            media.distinctBy { it.uri.toString() }.forEach { item ->
                val normalized = item.copy(sourceId = descriptor.sourceId)
                contentByUri[item.uri.toString()] =
                    upsertMediaInside(
                        normalized,
                        ContentAvailability.Available,
                        generation,
                        sourceAlreadyEnsured = true,
                    )
            }
            textCandidates.groupBy { it.mediaUriValue }.forEach { (uriValue, candidates) ->
                val contentId = contentByUri[uriValue] ?: return@forEach
                persistTextCatalogInside(
                    contentId,
                    textCatalogInside(contentId).mergeTracks(candidates.map { it.track.rekey(contentId) }),
                )
            }
        }
    }

    suspend fun applySuccessfulScan(
        descriptor: SourceScanDescriptor,
        media: List<MediaSourceRef>,
        textCandidates: List<TimedTextTrackCandidate>,
        hasAccess: Boolean = true,
    ) {
        database.withWriteTransaction {
            val previous = dao.source(descriptor.sourceId)
            val generation = (previous?.generation ?: 0L) + 1L
            val timestamp = nowMs()
            dao.upsertSource(
                SourceEntity(
                    sourceId = descriptor.sourceId,
                    kind = descriptor.kind.name,
                    rootUri = descriptor.rootUri,
                    displayName = descriptor.displayName,
                    hasAccess = hasAccess,
                    generation = generation,
                    createdAtMs = previous?.createdAtMs ?: timestamp,
                    lastIndexedAtMs = timestamp,
                    lastError = null,
                    removedAtMs = null,
                ),
            )
            val contentByUri = mutableMapOf<String, String>()
            media.distinctBy { it.uri.toString() }.forEach { item ->
                val normalized = if (item.sourceId == descriptor.sourceId) item else item.copy(sourceId = descriptor.sourceId)
                contentByUri[item.uri.toString()] =
                    upsertMediaInside(
                        normalized,
                        ContentAvailability.Available,
                        generation,
                        sourceAlreadyEnsured = true,
                    )
            }
            textCandidates.groupBy { it.mediaUriValue }.forEach { (uriValue, candidates) ->
                val contentId = contentByUri[uriValue] ?: return@forEach
                val current = textCatalogInside(contentId)
                val normalized = candidates.map { it.track.rekey(contentId) }
                persistTextCatalogInside(contentId, current.mergeTracks(normalized))
            }
            dao.markOlderSourceLocatorsUnavailable(descriptor.sourceId, generation, timestamp)
            for (contentId in dao.contentIdsForSource(descriptor.sourceId)) {
                selectAvailableLocatorInside(contentId)
            }
            garbageCollectInside()
        }
    }

    suspend fun recordSourceScanFailure(
        descriptor: SourceScanDescriptor,
        message: String,
        hasAccess: Boolean,
    ) {
        database.withWriteTransaction {
            val previous = dao.source(descriptor.sourceId)
            val timestamp = nowMs()
            dao.upsertSource(
                SourceEntity(
                    sourceId = descriptor.sourceId,
                    kind = descriptor.kind.name,
                    rootUri = descriptor.rootUri,
                    displayName = descriptor.displayName,
                    hasAccess = hasAccess,
                    generation = previous?.generation ?: 0L,
                    createdAtMs = previous?.createdAtMs ?: timestamp,
                    lastIndexedAtMs = previous?.lastIndexedAtMs,
                    lastError = message,
                    removedAtMs = null,
                ),
            )
        }
    }

    suspend fun removeSource(sourceId: String) {
        database.withWriteTransaction {
            val source = dao.source(sourceId) ?: return@withWriteTransaction
            val timestamp = nowMs()
            dao.upsertSource(
                source.copy(hasAccess = false, lastError = null, removedAtMs = timestamp),
            )
            dao.markSourceLocatorsUnavailable(sourceId, timestamp)
            for (contentId in dao.contentIdsForSource(sourceId)) {
                selectAvailableLocatorInside(contentId)
            }
            garbageCollectInside()
        }
    }

    suspend fun updateAvailability(
        contentId: String,
        locatorId: String,
        availability: ContentAvailability,
    ) {
        database.withWriteTransaction {
            val locator = dao.locator(locatorId) ?: return@withWriteTransaction
            if (locator.contentId != resolveRedirect(contentId)) return@withWriteTransaction
            dao.upsertLocator(locator.copy(availability = availability.name, updatedAtMs = nowMs()))
            selectAvailableLocatorInside(locator.contentId)
        }
    }

    suspend fun updateSubtitleOffset(contentId: String, offsetMs: Long) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            val content = requireContent(canonical)
            dao.upsertContent(content.copy(subtitleOffsetMs = offsetMs))
        }
    }

    suspend fun mergeTextTracks(contentId: String, tracks: List<TimedTextTrack>) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            val current = textCatalogInside(canonical)
            persistTextCatalogInside(canonical, current.mergeTracks(tracks.map { it.rekey(canonical) }))
        }
    }

    suspend fun addManualTextTrack(
        contentId: String,
        slot: TextTrackSlot,
        source: SubtitleSourceRef,
    ): TimedTextTrack =
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            val track = manualTimedTextTrack(canonical, source)
            val updated = textCatalogInside(canonical).selectManualTrack(slot, track)
            persistTextCatalogInside(canonical, updated)
            requireNotNull(updated.selectedTrack(slot))
        }

    suspend fun selectManualTextTrack(
        contentId: String,
        slot: TextTrackSlot,
        trackId: String?,
    ): TimedTextTrack? =
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            val current = textCatalogInside(canonical)
            val selected = current.tracks.firstOrNull { it.stableId == trackId }
            val updated = current.selectManualTrack(slot, selected)
            persistTextCatalogInside(canonical, updated)
            updated.selectedTrack(slot)
        }

    suspend fun restoreAutomaticTextTrack(
        contentId: String,
        slot: TextTrackSlot,
    ): TimedTextTrack? =
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            val updated = textCatalogInside(canonical).restoreAutomaticTrack(slot)
            persistTextCatalogInside(canonical, updated)
            updated.selectedTrack(slot)
        }

    suspend fun updateTextTrackReadiness(
        contentId: String,
        trackId: String,
        readiness: TimedTextReadiness,
        failureMessage: String? = null,
        resolvedSource: SubtitleSourceRef? = null,
    ) {
        database.withWriteTransaction {
            val canonical = resolveRedirect(contentId)
            val updated =
                textCatalogInside(canonical).withTrackReadiness(
                    trackId = trackId,
                    readiness = readiness,
                    failureMessage = failureMessage,
                    resolvedSource = resolvedSource,
                )
            persistTextCatalogInside(canonical, updated)
        }
    }

    suspend fun garbageCollect() {
        database.withWriteTransaction { garbageCollectInside() }
    }

    private fun pager(source: () -> androidx.paging.PagingSource<Int, io.github.zvensmoluya.vortexcue.data.db.ContentSummaryRow>): Flow<PagingData<ContentSummary>> =
        Pager(PagingConfig(pageSize = PageSize, initialLoadSize = PageSize, enablePlaceholders = false), pagingSourceFactory = source)
            .flow
            .map { page -> page.map { it.toDomain() } }

    private suspend fun upsertMediaInside(
        media: MediaSourceRef,
        availability: ContentAvailability,
        seenGeneration: Long,
        sourceAlreadyEnsured: Boolean = false,
    ): String {
        if (!sourceAlreadyEnsured) ensureSourceInside(media)
        val identityKey = mediaIdentityKey(media.uri.toString())
        val mapped = dao.contentIdForIdentity(identityKey)
        val contentId = mapped?.let { resolveRedirect(it) } ?: stableContentId(identityKey)
        val timestamp = nowMs()
        if (dao.content(contentId) == null) {
            dao.upsertContent(ContentEntity(contentId, timestamp))
        }
        dao.upsertIdentity(ContentIdentityEntity(identityKey, contentId))
        val locator = media.toEntity(contentId, availability, seenGeneration, timestamp)
        dao.upsertLocator(locator)
        dao.upsertActiveLocator(ActiveMediaLocatorEntity(contentId, locator.locatorId))
        return contentId
    }

    private suspend fun ensureSourceInside(media: MediaSourceRef) {
        val sourceId = media.sourceId ?: return
        val existing = dao.source(sourceId)
        if (existing != null) {
            if (existing.removedAtMs != null || !existing.hasAccess || existing.lastError != null) {
                dao.upsertSource(
                    existing.copy(
                        hasAccess = true,
                        lastError = null,
                        removedAtMs = null,
                    ),
                )
            }
            return
        }
        val timestamp = nowMs()
        val kind =
            when {
                sourceId == LibrarySourceIds.SystemLibrary -> LibrarySourceKind.SystemLibrary
                sourceId == LibrarySourceIds.LooseFiles -> LibrarySourceKind.LooseFiles
                media.origin == MediaOrigin.DocumentTree -> LibrarySourceKind.Folder
                else -> LibrarySourceKind.LooseFiles
            }
        dao.upsertSource(
            SourceEntity(
                sourceId = sourceId,
                kind = kind.name,
                rootUri = null,
                displayName =
                    when (kind) {
                        LibrarySourceKind.SystemLibrary -> "系统媒体库"
                        LibrarySourceKind.Folder -> media.folder ?: "文件夹"
                        LibrarySourceKind.LooseFiles -> "单独打开"
                    },
                createdAtMs = timestamp,
            ),
        )
    }

    private suspend fun sourceGeneration(sourceId: String?): Long =
        sourceId?.let { dao.source(it)?.generation } ?: 0L

    private suspend fun resolveRedirect(contentId: String): String {
        var current = contentId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            current = dao.redirectTarget(current) ?: return current
        }
        return current
    }

    private suspend fun detailsInside(contentId: String): ContentDetails? {
        val content = dao.content(contentId) ?: return null
        val locators = dao.locators(contentId)
        val active = dao.activeLocator(contentId)?.locatorId ?: return null
        if (locators.none { it.locatorId == active }) return null
        val trackEntities = dao.textTracks(contentId)
        val roles = dao.textRoles(contentId)
        val selections = dao.textSelections(contentId)
        return ContentDetails(
            contentId = contentId,
            mediaLocators = locators.map { it.toDomain() },
            activeMediaLocatorId = active,
            timedTextTracks = trackEntities.map { it.toDomain(roles) },
            activeText = selections.toActiveText(trackEntities),
            subtitleOffsetMs = content.subtitleOffsetMs,
            disposition = dao.disposition(contentId)?.let {
                if (it.isCollected) ContentDisposition.Collected else ContentDisposition.Ignored
            },
        )
    }

    private suspend fun textCatalogInside(contentId: String): TextTrackCatalogState {
        val trackEntities = dao.textTracks(contentId)
        val roles = dao.textRoles(contentId)
        return TextTrackCatalogState(
            tracks = trackEntities.map { it.toDomain(roles) },
            activeText = dao.textSelections(contentId).toActiveText(trackEntities),
        )
    }

    private suspend fun persistTextCatalogInside(contentId: String, catalog: TextTrackCatalogState) {
        val existingIds = dao.textTracks(contentId).mapTo(mutableSetOf()) { it.trackId }
        val retainedIds = catalog.tracks.mapTo(mutableSetOf()) { it.stableId }
        for (trackId in existingIds.subtract(retainedIds)) {
            dao.clearTextSelectionsForTrack(trackId)
            dao.deleteTextTrack(trackId)
        }
        catalog.tracks.forEach { track ->
            dao.upsertTextTrack(
                track.toEntity(contentId, track.stableId in catalog.activeText.rejectedTrackIds),
            )
            dao.clearTextRoles(track.stableId)
            dao.insertTextRoles(track.roleEntities())
        }
        dao.upsertTextSelection(
            TextSelectionEntity(
                contentId = contentId,
                slot = TextTrackSlot.Primary.name,
                selectedTrackId = catalog.activeText.primaryTrackId,
                mode = catalog.activeText.primaryMode.name,
            ),
        )
        dao.upsertTextSelection(
            TextSelectionEntity(
                contentId = contentId,
                slot = TextTrackSlot.Secondary.name,
                selectedTrackId = catalog.activeText.secondaryTrackId,
                mode = catalog.activeText.secondaryMode.name,
            ),
        )
    }

    private suspend fun selectAvailableLocatorInside(contentId: String) {
        val locators = dao.locators(contentId)
        val current = dao.activeLocator(contentId)?.locatorId
        val selected =
            locators.firstOrNull {
                it.locatorId == current && it.availability == ContentAvailability.Available.name
            } ?: locators.firstOrNull { it.availability == ContentAvailability.Available.name }
                ?: locators.firstOrNull()
        if (selected == null) {
            dao.clearActiveLocator(contentId)
        } else {
            dao.upsertActiveLocator(ActiveMediaLocatorEntity(contentId, selected.locatorId))
        }
    }

    private suspend fun mergeContentInside(targetId: String, absorbedId: String) {
        if (targetId == absorbedId) return
        val target = requireContent(targetId)
        requireContent(absorbedId)
        val timestamp = nowMs()
        val targetCatalog = textCatalogInside(targetId)
        val absorbedCatalog = textCatalogInside(absorbedId)
        val mergedTracks = targetCatalog.mergeTracks(absorbedCatalog.tracks.map { it.rekey(targetId) })

        dao.clearActiveLocator(absorbedId)
        for (locator in dao.locators(absorbedId)) {
            dao.upsertLocator(locator.copy(contentId = targetId, updatedAtMs = timestamp))
        }
        for (identity in dao.identities(absorbedId)) {
            dao.upsertIdentity(identity.copy(contentId = targetId))
        }
        for (redirect in dao.redirectsTo(absorbedId)) {
            dao.upsertRedirect(redirect.copy(canonicalContentId = targetId))
        }

        val targetDisposition = dao.disposition(targetId)
        val absorbedDisposition = dao.disposition(absorbedId)
        val mergedCollected =
            when {
                targetDisposition?.isCollected == true || absorbedDisposition?.isCollected == true -> true
                targetDisposition != null || absorbedDisposition != null -> false
                else -> null
            }
        if (mergedCollected != null) {
            dao.upsertDisposition(
                ContentDispositionEntity(
                    contentId = targetId,
                    isCollected = mergedCollected,
                    decidedAtMs = maxOf(
                        targetDisposition?.decidedAtMs ?: 0L,
                        absorbedDisposition?.decidedAtMs ?: 0L,
                    ),
                ),
            )
        }

        val recentAt = maxOf(dao.recent(targetId)?.lastOpenedAtMs ?: 0L, dao.recent(absorbedId)?.lastOpenedAtMs ?: 0L)
        if (recentAt > 0L) dao.upsertRecent(RecentEntryEntity(targetId, recentAt))

        val targetFolder = dao.contentFolderMembership(targetId)
        val absorbedFolder = dao.contentFolderMembership(absorbedId)
        if (targetFolder == null && absorbedFolder != null) {
            dao.upsertContentFolderMembership(absorbedFolder.copy(contentId = targetId, movedAtMs = timestamp))
        }
        dao.clearContentFolderMembership(absorbedId)

        val playbackDao = database.playbackDao()
        val session = playbackDao.session()
        val queue = playbackDao.queueEntries()
        if (queue.any { it.contentId == absorbedId }) {
            val targetIndices =
                queue.mapIndexedNotNull { index, entry ->
                    index.takeIf { entry.contentId == targetId || entry.contentId == absorbedId }
                }
            val preferredIndex =
                when (session?.currentContentId) {
                    absorbedId -> queue.indexOfFirst { it.contentId == absorbedId }
                    targetId -> queue.indexOfFirst { it.contentId == targetId }
                    else -> targetIndices.first()
                }
            val mergedQueue =
                queue.mapIndexedNotNull { index, entry ->
                    when {
                        entry.contentId != targetId && entry.contentId != absorbedId -> entry
                        index == preferredIndex -> entry.copy(contentId = targetId)
                        else -> null
                    }
                }
            playbackDao.clearQueueEntries()
            playbackDao.upsertQueueEntries(
                mergedQueue.mapIndexed { index, entry -> entry.copy(position = index) },
            )
            val prior = session ?: CurrentPlaybackSessionEntity(updatedAtMs = timestamp)
            playbackDao.upsertSession(
                prior.copy(
                    currentContentId =
                        if (prior.currentContentId == absorbedId) targetId else prior.currentContentId,
                    queueRevision = prior.queueRevision + 1L,
                    updatedAtMs = timestamp,
                ),
            )
        } else if (session?.currentContentId == absorbedId) {
            playbackDao.upsertSession(
                session.copy(
                    currentContentId = targetId,
                    queueRevision = session.queueRevision + 1L,
                    updatedAtMs = timestamp,
                ),
            )
        }

        if (dao.draft()?.contentId == absorbedId) {
            dao.upsertDraft(CurrentDraftEntity(contentId = targetId, updatedAtMs = timestamp))
        }

        persistTextCatalogInside(
            targetId,
            mergedTracks.copy(activeText = targetCatalog.activeText),
        )
        dao.upsertContent(target.copy(subtitleOffsetMs = target.subtitleOffsetMs))
        selectAvailableLocatorInside(targetId)
        dao.deleteContent(absorbedId)
        dao.upsertRedirect(ContentRedirectEntity(absorbedId, targetId, timestamp))
    }

    private suspend fun garbageCollectInside() {
        for (contentId in dao.garbageCollectableContentIds()) {
            dao.deleteContent(contentId)
        }
    }

    private suspend fun requireContent(contentId: String): ContentEntity =
        requireNotNull(dao.content(contentId)) { "Unknown contentId: $contentId" }

    private data class DetailBase(
        val content: ContentEntity?,
        val locators: List<io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity>,
        val active: ActiveMediaLocatorEntity?,
    )

    private companion object {
        const val PageSize = 50
        const val RecentLimit = 50
        const val IgnoredPreviewLimit = 100
    }
}

private fun List<TextSelectionEntity>.toActiveText(
    tracks: List<io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity>,
): ActiveTextSelection {
    val primary = firstOrNull { it.slot() == TextTrackSlot.Primary }
    val secondary = firstOrNull { it.slot() == TextTrackSlot.Secondary }
    return ActiveTextSelection(
        primaryTrackId = primary?.selectedTrackId,
        secondaryTrackId = secondary?.selectedTrackId,
        primaryMode = primary?.mode() ?: TextSelectionMode.Automatic,
        secondaryMode = secondary?.mode() ?: TextSelectionMode.Automatic,
        rejectedTrackIds = tracks.filter { it.isRejected }.map { it.trackId },
    )
}

private fun TimedTextTrack.rekey(contentId: String): TimedTextTrack =
    copy(stableId = stableTimedTextTrackId(contentId, origin, locator.stableIdentity))

private fun TextTrackCatalogState.selectedTrack(slot: TextTrackSlot): TimedTextTrack? {
    val trackId =
        when (slot) {
            TextTrackSlot.Primary -> activeText.primaryTrackId
            TextTrackSlot.Secondary -> activeText.secondaryTrackId
        }
    return tracks.firstOrNull { it.stableId == trackId }
}
