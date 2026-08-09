package io.github.zvensmoluya.vortexcue

import android.net.Uri

enum class MediaOrigin {
    MediaStore,
    OpenDocument,
    DocumentTree,
}

data class MediaSourceRef(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val folder: String? = null,
    val dateAddedSeconds: Long = 0L,
    val origin: MediaOrigin = MediaOrigin.OpenDocument,
)

val MediaSourceRef.displayTitle: String
    get() =
        title?.takeIf(String::isNotBlank)
            ?: displayName.substringBeforeLast('.', missingDelimiterValue = displayName)

val MediaSourceRef.displayArtist: String?
    get() = artist?.takeIf { it.isNotBlank() && it != "<unknown>" }

data class SubtitleSourceRef(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val cachedFileName: String? = null,
)

data class LoadedSubtitle(
    val source: SubtitleSourceRef,
    val timeline: SubtitleTimeline,
)

sealed interface DraftMedia {
    val source: MediaSourceRef?

    data object None : DraftMedia {
        override val source: MediaSourceRef? = null
    }

    data class Checking(
        override val source: MediaSourceRef,
    ) : DraftMedia

    data class Ready(
        override val source: MediaSourceRef,
    ) : DraftMedia

    data class Unavailable(
        override val source: MediaSourceRef,
        val message: String,
    ) : DraftMedia
}

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
    val media: DraftMedia = DraftMedia.None,
    val subtitle: DraftSubtitle = DraftSubtitle.None,
)

data class ActiveSession(
    val media: MediaSourceRef,
    val subtitle: LoadedSubtitle?,
    val subtitleOffsetMs: Long = 0L,
)

data class RestorableSession(
    val session: ActiveSession,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val mediaAvailability: MediaAvailability = MediaAvailability.Ready,
)

sealed interface MediaAvailability {
    data object Checking : MediaAvailability

    data object Ready : MediaAvailability

    data class Unavailable(
        val message: String,
    ) : MediaAvailability
}

data class DisplayProfile(
    val name: String,
    val glowWindowBrightness: Float,
    val glowSubtitleLevel: Int,
    val glowControlLevel: Int,
    val glowSubtitleSizeSp: Float,
    val glowSubtitleVerticalBias: Float,
) {
    companion object {
        const val CustomName = "自定义"

        val Soft =
            DisplayProfile(
                name = "柔和",
                glowWindowBrightness = 0.05f,
                glowSubtitleLevel = 10,
                glowControlLevel = 64,
                glowSubtitleSizeSp = 23f,
                glowSubtitleVerticalBias = 0.18f,
            )
        val Balanced =
            DisplayProfile(
                name = "平衡",
                glowWindowBrightness = 0.08f,
                glowSubtitleLevel = 16,
                glowControlLevel = 80,
                glowSubtitleSizeSp = 24f,
                glowSubtitleVerticalBias = 0.18f,
            )
        val HighReadability =
            DisplayProfile(
                name = "高可读",
                glowWindowBrightness = 0.14f,
                glowSubtitleLevel = 30,
                glowControlLevel = 112,
                glowSubtitleSizeSp = 27f,
                glowSubtitleVerticalBias = 0.08f,
            )

        val Presets = listOf(Soft, Balanced, HighReadability)

        fun fromName(name: String?): DisplayProfile =
            Presets.firstOrNull { it.name == name } ?: Balanced

        fun restore(
            name: String?,
            windowBrightness: Float?,
            subtitleLevel: Int?,
            controlLevel: Int?,
            subtitleSizeSp: Float?,
            subtitleVerticalBias: Float?,
        ): DisplayProfile {
            val preset = fromName(name)
            return preset.copy(
                name = if (name == CustomName) CustomName else preset.name,
                glowWindowBrightness =
                    windowBrightness
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.01f, 0.30f)
                        ?: preset.glowWindowBrightness,
                glowSubtitleLevel =
                    subtitleLevel?.coerceIn(4, 96) ?: preset.glowSubtitleLevel,
                glowControlLevel =
                    controlLevel?.coerceIn(24, 160) ?: preset.glowControlLevel,
                glowSubtitleSizeSp =
                    subtitleSizeSp
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(18f, 36f)
                        ?: preset.glowSubtitleSizeSp,
                glowSubtitleVerticalBias =
                    subtitleVerticalBias
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(-0.60f, 0.60f)
                        ?: preset.glowSubtitleVerticalBias,
            )
        }
    }
}

enum class PlayerSurface {
    Normal,
    Glow,
}
