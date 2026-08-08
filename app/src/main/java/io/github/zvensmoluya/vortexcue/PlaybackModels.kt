package io.github.zvensmoluya.vortexcue

import android.net.Uri

data class MediaSourceRef(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
)

data class SubtitleSourceRef(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
)

data class LoadedSubtitle(
    val source: SubtitleSourceRef,
    val timeline: SubtitleTimeline,
)

sealed interface DraftSubtitle {
    data object None : DraftSubtitle

    data class Loading(
        val source: SubtitleSourceRef,
    ) : DraftSubtitle

    data class Ready(
        val subtitle: LoadedSubtitle,
    ) : DraftSubtitle

    data class Failed(
        val source: SubtitleSourceRef,
        val message: String,
    ) : DraftSubtitle
}

data class SessionDraft(
    val media: MediaSourceRef? = null,
    val subtitle: DraftSubtitle = DraftSubtitle.None,
)

data class ActiveSession(
    val media: MediaSourceRef,
    val subtitle: LoadedSubtitle?,
    val subtitleOffsetMs: Long = 0L,
)

data class DisplayProfile(
    val name: String,
    val glowWindowBrightness: Float,
    val glowSubtitleLevel: Int,
    val glowControlLevel: Int,
) {
    companion object {
        val Soft =
            DisplayProfile(
                name = "柔和",
                glowWindowBrightness = 0.05f,
                glowSubtitleLevel = 10,
                glowControlLevel = 64,
            )
        val Balanced =
            DisplayProfile(
                name = "平衡",
                glowWindowBrightness = 0.08f,
                glowSubtitleLevel = 16,
                glowControlLevel = 80,
            )
        val HighReadability =
            DisplayProfile(
                name = "高可读",
                glowWindowBrightness = 0.14f,
                glowSubtitleLevel = 30,
                glowControlLevel = 112,
            )

        val Presets = listOf(Soft, Balanced, HighReadability)
    }
}

enum class PlayerSurface {
    Normal,
    Glow,
}
