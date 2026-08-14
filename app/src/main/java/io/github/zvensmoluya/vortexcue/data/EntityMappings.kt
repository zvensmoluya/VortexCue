package io.github.zvensmoluya.vortexcue.data

import android.net.Uri
import io.github.zvensmoluya.vortexcue.core.model.ContentAvailability
import io.github.zvensmoluya.vortexcue.core.model.MediaKind
import io.github.zvensmoluya.vortexcue.core.model.MediaLocator
import io.github.zvensmoluya.vortexcue.core.model.MediaOrigin
import io.github.zvensmoluya.vortexcue.core.model.MediaSourceRef
import io.github.zvensmoluya.vortexcue.core.model.TextSelectionMode
import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot
import io.github.zvensmoluya.vortexcue.core.model.TimedTextLocator
import io.github.zvensmoluya.vortexcue.core.model.TimedTextMatchBasis
import io.github.zvensmoluya.vortexcue.core.model.TimedTextOrigin
import io.github.zvensmoluya.vortexcue.core.model.TimedTextReadiness
import io.github.zvensmoluya.vortexcue.core.model.TimedTextRole
import io.github.zvensmoluya.vortexcue.core.model.TimedTextTrack
import io.github.zvensmoluya.vortexcue.core.model.mediaIdentityKey
import io.github.zvensmoluya.vortexcue.core.model.stableMediaLocatorId
import io.github.zvensmoluya.vortexcue.data.db.ContentSummaryRow
import io.github.zvensmoluya.vortexcue.data.db.MediaLocatorEntity
import io.github.zvensmoluya.vortexcue.data.db.QueueItemRow
import io.github.zvensmoluya.vortexcue.data.db.SourceSummaryRow
import io.github.zvensmoluya.vortexcue.data.db.TextSelectionEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackEntity
import io.github.zvensmoluya.vortexcue.data.db.TimedTextTrackRoleEntity
import io.github.zvensmoluya.vortexcue.library.LibrarySourceKind

internal fun MediaSourceRef.toEntity(
    contentId: String,
    availability: ContentAvailability,
    seenGeneration: Long,
    updatedAtMs: Long,
): MediaLocatorEntity =
    MediaLocatorEntity(
        locatorId = stableMediaLocatorId(uri.toString(), sourceId),
        contentId = contentId,
        identityKey = mediaIdentityKey(uri.toString()),
        sourceId = sourceId,
        uriValue = uri.toString(),
        displayName = displayName,
        mimeType = mimeType,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        folder = folder,
        dateAddedSeconds = dateAddedSeconds,
        origin = origin.name,
        kind = kind.name,
        sizeBytes = sizeBytes,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        availability = availability.name,
        seenGeneration = seenGeneration,
        updatedAtMs = updatedAtMs,
    )

internal fun MediaLocatorEntity.toDomain(): MediaLocator =
    MediaLocator(
        stableId = locatorId,
        identityKey = identityKey,
        media = toMediaSourceRef(),
        availability = availability.enumValueOr(ContentAvailability.Unchecked),
    )

internal fun MediaLocatorEntity.toMediaSourceRef(): MediaSourceRef =
    mediaSourceRef(
        uriValue = uriValue,
        displayName = displayName,
        mimeType = mimeType,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        folder = folder,
        dateAddedSeconds = dateAddedSeconds,
        origin = origin,
        sourceId = sourceId,
        kind = kind,
        sizeBytes = sizeBytes,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
    )

internal fun ContentSummaryRow.toDomain(): ContentSummary =
    ContentSummary(
        contentId = contentId,
        locatorId = locatorId,
        media =
            mediaSourceRef(
                uriValue = uriValue,
                displayName = displayName,
                mimeType = mimeType,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                folder = folder,
                dateAddedSeconds = dateAddedSeconds,
                origin = origin,
                sourceId = sourceId,
                kind = kind,
                sizeBytes = sizeBytes,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
            ),
        availability = availability.enumValueOr(ContentAvailability.Unchecked),
        disposition =
            isCollected?.let {
                if (it) ContentDisposition.Collected else ContentDisposition.Ignored
            },
        lastOpenedAtMs = lastOpenedAtMs,
    )

internal fun QueueItemRow.toContentSummary(): ContentSummary =
    ContentSummary(
        contentId = contentId,
        locatorId = locatorId,
        media =
            mediaSourceRef(
                uriValue = uriValue,
                displayName = displayName,
                mimeType = mimeType,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                folder = folder,
                dateAddedSeconds = dateAddedSeconds,
                origin = origin,
                sourceId = sourceId,
                kind = kind,
                sizeBytes = sizeBytes,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
            ),
        availability = availability.enumValueOr(ContentAvailability.Unchecked),
        disposition = null,
        lastOpenedAtMs = null,
    )

internal fun SourceSummaryRow.toDomain(): SourceSummary =
    SourceSummary(
        sourceId = sourceId,
        kind = kind.enumValueOr(LibrarySourceKind.LooseFiles),
        rootUri = rootUri,
        displayName = displayName,
        hasAccess = hasAccess,
        generation = generation,
        lastIndexedAtMs = lastIndexedAtMs,
        lastError = lastError,
        itemCount = itemCount,
    )

internal fun TimedTextTrackEntity.toDomain(
    roles: List<TimedTextTrackRoleEntity>,
): TimedTextTrack =
    TimedTextTrack(
        stableId = trackId,
        origin = origin.enumValueOr(TimedTextOrigin.Sidecar),
        locator =
            TimedTextLocator(
                uriValue = uriValue,
                embeddedTrackId = embeddedTrackId,
                displayName = displayName,
                mimeType = mimeType,
                cachedFileName = cachedFileName,
            ),
        format = format,
        language = language,
        label = label,
        roles = roles.filter { it.trackId == trackId }.mapNotNull { it.role.enumValueOrNull() },
        matchBasis = matchBasis.enumValueOr(TimedTextMatchBasis.FilenameCandidate),
        readiness = readiness.enumValueOr(TimedTextReadiness.Unknown),
        failureMessage = failureMessage,
    )

internal fun TimedTextTrack.toEntity(
    contentId: String,
    isRejected: Boolean,
): TimedTextTrackEntity =
    TimedTextTrackEntity(
        trackId = stableId,
        contentId = contentId,
        origin = origin.name,
        locatorIdentity = locator.stableIdentity,
        uriValue = locator.uriValue,
        embeddedTrackId = locator.embeddedTrackId,
        displayName = locator.displayName,
        mimeType = locator.mimeType,
        cachedFileName = locator.cachedFileName,
        format = format,
        language = language,
        label = label,
        matchBasis = matchBasis.name,
        readiness = readiness.name,
        failureMessage = failureMessage,
        isRejected = isRejected,
    )

internal fun TimedTextTrack.roleEntities(): List<TimedTextTrackRoleEntity> =
    roles.distinct().map { TimedTextTrackRoleEntity(stableId, it.name) }

internal fun TextSelectionEntity.slot(): TextTrackSlot =
    slot.enumValueOr(TextTrackSlot.Primary)

internal fun TextSelectionEntity.mode(): TextSelectionMode =
    mode.enumValueOr(TextSelectionMode.Automatic)

private fun mediaSourceRef(
    uriValue: String,
    displayName: String,
    mimeType: String?,
    title: String?,
    artist: String?,
    album: String?,
    durationMs: Long,
    folder: String?,
    dateAddedSeconds: Long,
    origin: String,
    sourceId: String?,
    kind: String,
    sizeBytes: Long,
    videoWidth: Int,
    videoHeight: Int,
): MediaSourceRef =
    MediaSourceRef(
        uri = Uri.parse(uriValue),
        displayName = displayName,
        mimeType = mimeType,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        folder = folder,
        dateAddedSeconds = dateAddedSeconds,
        origin = origin.enumValueOr(MediaOrigin.OpenDocument),
        sourceId = sourceId,
        kind = kind.enumValueOr(MediaKind.infer(mimeType, displayName)),
        sizeBytes = sizeBytes,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
    )

internal inline fun <reified T : Enum<T>> String.enumValueOr(fallback: T): T =
    enumValueOrNull<T>() ?: fallback

internal inline fun <reified T : Enum<T>> String.enumValueOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }
