package io.github.zvensmoluya.vortexcue.app

import io.github.zvensmoluya.vortexcue.core.model.TextTrackSlot

internal data class DraftSubtitleRequestIds(
    val primary: Long = 0L,
    val secondary: Long = 0L,
) {
    fun current(slot: TextTrackSlot): Long =
        when (slot) {
            TextTrackSlot.Primary -> primary
            TextTrackSlot.Secondary -> secondary
        }

    fun invalidate(slot: TextTrackSlot): DraftSubtitleRequestIds =
        when (slot) {
            TextTrackSlot.Primary -> copy(primary = primary + 1L)
            TextTrackSlot.Secondary -> copy(secondary = secondary + 1L)
        }
}

internal fun isCurrentDraftSubtitleRequest(
    slot: TextTrackSlot,
    requestId: Long,
    currentRequestIds: DraftSubtitleRequestIds,
    mediaUriValue: String,
    currentMediaUriValue: String?,
): Boolean =
    requestId == currentRequestIds.current(slot) && mediaUriValue == currentMediaUriValue
