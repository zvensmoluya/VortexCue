package io.github.zvensmoluya.vortexcue.core.model

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

enum class ContentAvailability {
    Unchecked,
    Available,
    Unavailable,
}

enum class TimedTextOrigin {
    Embedded,
    Sidecar,
    Lyrics,
    Generated,
}

enum class TimedTextRole {
    Default,
    Forced,
    Sdh,
    ClosedCaptions,
}

enum class TimedTextMatchBasis {
    UserSelected,
    ExactStem,
    LanguageSuffix,
    FilenameCandidate,
    EmbeddedMetadata,
}

enum class TimedTextReadiness {
    Unknown,
    Ready,
    PrivateCopy,
    Unreadable,
    ParseFailed,
}

enum class TextSelectionMode {
    Automatic,
    Manual,
}

enum class TextTrackSlot {
    Primary,
    Secondary,
}

data class TimedTextLocator(
    val uriValue: String? = null,
    val embeddedTrackId: String? = null,
    val displayName: String,
    val mimeType: String? = null,
    val cachedFileName: String? = null,
) {
    init {
        require(!uriValue.isNullOrBlank() || !embeddedTrackId.isNullOrBlank())
    }

    val stableIdentity: String
        get() = uriValue?.let { "uri:$it" } ?: "embedded:$embeddedTrackId"
}

data class TimedTextTrack(
    val stableId: String,
    val origin: TimedTextOrigin,
    val locator: TimedTextLocator,
    val format: String? = null,
    val language: String? = null,
    val label: String? = null,
    val roles: List<TimedTextRole> = emptyList(),
    val matchBasis: TimedTextMatchBasis,
    val readiness: TimedTextReadiness = TimedTextReadiness.Unknown,
    val failureMessage: String? = null,
)

data class MediaLocator(
    val stableId: String,
    val identityKey: String,
    val media: MediaSourceRef,
    val availability: ContentAvailability = ContentAvailability.Unchecked,
) {
    init {
        require(stableId.isNotBlank())
        require(identityKey.isNotBlank())
        require(stableId == stableMediaLocatorId(media.uri.toString(), media.sourceId))
        require(identityKey == mediaIdentityKey(media.uri.toString()))
    }
}

data class ActiveTextSelection(
    val primaryTrackId: String? = null,
    val secondaryTrackId: String? = null,
    val primaryMode: TextSelectionMode = TextSelectionMode.Automatic,
    val secondaryMode: TextSelectionMode = TextSelectionMode.Automatic,
    val rejectedTrackIds: List<String> = emptyList(),
)

data class PlayableContentUnit(
    val stableId: String,
    val mediaLocators: List<MediaLocator>,
    val activeMediaLocatorId: String,
    val timedTextTracks: List<TimedTextTrack> = emptyList(),
    val activeText: ActiveTextSelection = ActiveTextSelection(),
    val subtitleOffsetMs: Long = 0L,
) {
    init {
        require(stableId.isNotBlank())
        require(mediaLocators.isNotEmpty())
        require(mediaLocators.map(MediaLocator::stableId).distinct().size == mediaLocators.size)
        require(mediaLocators.any { it.stableId == activeMediaLocatorId })
    }

    val activeMediaLocator: MediaLocator
        get() = mediaLocators.first { it.stableId == activeMediaLocatorId }

    val media: MediaSourceRef
        get() = activeMediaLocator.media

    val sourceId: String?
        get() = activeMediaLocator.media.sourceId

    val availability: ContentAvailability
        get() = activeMediaLocator.availability
}

internal fun mediaIdentityKey(uriValue: String): String {
    require(uriValue.isNotBlank())
    val uri = runCatching { URI(uriValue) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val authority = uri?.rawAuthority
    val rawPath = uri?.rawPath
    val documentMarker = "/document/"
    val documentMarkerIndex = rawPath?.lastIndexOf(documentMarker) ?: -1
    val documentId =
        if (documentMarkerIndex >= 0) {
            rawPath
                ?.substring(documentMarkerIndex + documentMarker.length)
                ?.normalizeDocumentId()
                ?.takeIf(String::isNotBlank)
        } else {
            null
        }
    return if (scheme == "content" && !authority.isNullOrBlank() && documentId != null) {
        "document:$authority:$documentId"
    } else {
        "uri:$uriValue"
    }
}

internal fun stableContentId(identityKey: String): String =
    stableIdentifier("content", identityKey)

internal fun stableMediaLocatorId(
    uriValue: String,
    sourceId: String?,
): String =
    stableIdentifier("locator", "$uriValue|source:${sourceId.orEmpty()}")

internal fun stableTimedTextTrackId(
    contentId: String,
    origin: TimedTextOrigin,
    locatorIdentity: String,
): String =
    stableIdentifier("text", "$contentId|${origin.name}|$locatorIdentity")

private fun stableIdentifier(
    prefix: String,
    value: String,
): String {
    require(value.isNotBlank())
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hexDigits = "0123456789abcdef"
    val encoded =
        buildString(32) {
            digest.take(16).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(hexDigits[unsigned ushr 4])
                append(hexDigits[unsigned and 0x0f])
            }
        }
    return "$prefix-$encoded"
}

private fun String.normalizeDocumentId(): String =
    runCatching {
        URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrElse {
        PercentEscape.replace(this) { match -> match.value.uppercase() }
    }

private val PercentEscape = Regex("%[0-9a-fA-F]{2}")
