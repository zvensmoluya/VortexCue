package io.github.zvensmoluya.vortexcue.library

import io.github.zvensmoluya.vortexcue.core.model.*

internal data class EmbeddedTextTrackMetadata(
    val groupId: String?,
    val formatId: String?,
    val groupIndex: Int,
    val trackIndex: Int,
    val mimeType: String?,
    val language: String?,
    val label: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isSdh: Boolean,
    val isClosedCaptions: Boolean,
    val isSupported: Boolean,
)

internal fun buildEmbeddedTextTrackCandidate(
    mediaUriValue: String,
    metadata: EmbeddedTextTrackMetadata,
): TimedTextTrackCandidate? {
    if (isGraphicalEmbeddedTextMimeType(metadata.mimeType)) return null

    val contentId = stableContentId(mediaIdentityKey(mediaUriValue))
    val embeddedTrackId = embeddedTextTrackIdentity(metadata)
    val displayName =
        metadata.label?.takeIf(String::isNotBlank)
            ?: metadata.language?.takeIf(String::isNotBlank)?.let { "内嵌字幕 · $it" }
            ?: "内嵌字幕 ${metadata.trackIndex + 1}"
    val locator =
        TimedTextLocator(
            embeddedTrackId = embeddedTrackId,
            displayName = displayName,
            mimeType = metadata.mimeType,
        )
    val readiness =
        if (metadata.isSupported) {
            TimedTextReadiness.Ready
        } else {
            TimedTextReadiness.Unreadable
        }
    return TimedTextTrackCandidate(
        mediaUriValue = mediaUriValue,
        track =
            TimedTextTrack(
                stableId =
                    stableTimedTextTrackId(
                        contentId = contentId,
                        origin = TimedTextOrigin.Embedded,
                        locatorIdentity = locator.stableIdentity,
                    ),
                origin = TimedTextOrigin.Embedded,
                locator = locator,
                format = embeddedTextFormat(metadata.mimeType),
                language = metadata.language?.takeIf(String::isNotBlank),
                label = metadata.label?.takeIf(String::isNotBlank),
                roles =
                    listOfNotNull(
                        TimedTextRole.Default.takeIf { metadata.isDefault },
                        TimedTextRole.Forced.takeIf { metadata.isForced },
                        TimedTextRole.Sdh.takeIf { metadata.isSdh },
                        TimedTextRole.ClosedCaptions.takeIf { metadata.isClosedCaptions },
                    ),
                matchBasis = TimedTextMatchBasis.EmbeddedMetadata,
                readiness = readiness,
                failureMessage =
                    "当前设备不支持此内嵌文字轨"
                        .takeIf { readiness == TimedTextReadiness.Unreadable },
            ),
    )
}

internal fun embeddedTextTrackIdentity(metadata: EmbeddedTextTrackMetadata): String =
    "media3:${identityPart(metadata.groupId, metadata.groupIndex)}/" +
        identityPart(metadata.formatId, metadata.trackIndex)

internal fun isGraphicalEmbeddedTextMimeType(mimeType: String?): Boolean =
    mimeType?.lowercase() in
        setOf(
            "application/vobsub",
            "application/pgs",
            "application/dvbsubs",
        )

private fun identityPart(
    id: String?,
    fallbackIndex: Int,
): String =
    id
        ?.takeIf(String::isNotBlank)
        ?.let { "id:${it.length}:$it" }
        ?: "index:$fallbackIndex"

private fun embeddedTextFormat(mimeType: String?): String? =
    when (mimeType?.lowercase()) {
        "text/vtt" -> "vtt"
        "text/x-ssa" -> "ass/ssa"
        "application/x-subrip" -> "srt"
        "application/ttml+xml" -> "ttml"
        "application/x-quicktime-tx3g" -> "tx3g"
        "application/x-mp4-vtt" -> "vtt"
        "application/cea-608", "application/x-mp4-cea-608" -> "cea-608"
        "application/cea-708" -> "cea-708"
        "application/x-media3-cues" -> "media3-cues"
        null -> null
        else -> mimeType.substringAfter('/', mimeType).lowercase()
    }
