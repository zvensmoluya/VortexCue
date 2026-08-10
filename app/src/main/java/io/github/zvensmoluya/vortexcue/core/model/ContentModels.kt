package io.github.zvensmoluya.vortexcue.core.model

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
    EmbeddedMetadata,
    Legacy,
}

enum class TimedTextReadiness {
    Unknown,
    Ready,
    Unreadable,
    ParseFailed,
}

enum class TextSelectionMode {
    Automatic,
    Manual,
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
)

data class ActiveTextSelection(
    val primaryTrackId: String? = null,
    val secondaryTrackId: String? = null,
    val primaryMode: TextSelectionMode = TextSelectionMode.Automatic,
    val secondaryMode: TextSelectionMode = TextSelectionMode.Automatic,
    val rejectedTrackIds: List<String> = emptyList(),
)

data class PlayableContentUnit(
    val stableId: String,
    val media: MediaSourceRef,
    val sourceId: String? = media.sourceId,
    val timedTextTracks: List<TimedTextTrack> = emptyList(),
    val activeText: ActiveTextSelection = ActiveTextSelection(),
    val availability: ContentAvailability = ContentAvailability.Unchecked,
)

internal fun stableContentId(uriValue: String): String =
    stableIdentifier("content", uriValue)

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
