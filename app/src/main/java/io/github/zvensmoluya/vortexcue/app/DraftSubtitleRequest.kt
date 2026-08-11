package io.github.zvensmoluya.vortexcue.app

internal fun isCurrentDraftSubtitleRequest(
    requestId: Long,
    currentRequestId: Long,
    mediaUriValue: String,
    currentMediaUriValue: String?,
): Boolean =
    requestId == currentRequestId && mediaUriValue == currentMediaUriValue
