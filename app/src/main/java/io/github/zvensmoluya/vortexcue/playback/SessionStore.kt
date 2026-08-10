package io.github.zvensmoluya.vortexcue.playback

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.core.storage.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.sessionDataStore by preferencesDataStore(name = "session_state")

data class StoredAppState(
    val draft: StoredDraft,
    val lastSession: StoredLastSession?,
    val displayProfile: DisplayProfile,
    val glowCalibrationCompleted: Boolean = false,
    val glowGestureHintSeen: Boolean = false,
)

data class StoredDraft(
    val media: MediaSourceRef?,
    val subtitle: SubtitleSourceRef?,
)

data class StoredLastSession(
    val media: MediaSourceRef,
    val subtitle: SubtitleSourceRef?,
    val secondarySubtitle: SubtitleSourceRef?,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val subtitleOffsetMs: Long,
    val queue: List<MediaSourceRef>,
    val currentIndex: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val lastSurface: PlayerSurface,
)

class SessionStore(context: Context) {
    private val dataStore = context.applicationContext.sessionDataStore

    suspend fun load(): StoredAppState {
        val preferences = dataStore.data.first()
        val lastMedia = preferences.readMediaSource(Keys.LastMedia)
        val lastSession =
            lastMedia?.let { media ->
                StoredLastSession(
                    media = media,
                    subtitle = preferences.readSubtitleSource(Keys.LastSubtitle),
                    secondarySubtitle =
                        preferences.readSubtitleSource(Keys.LastSecondarySubtitle),
                    positionMs = preferences[Keys.LastPosition] ?: 0L,
                    durationMs = preferences[Keys.LastDuration] ?: 0L,
                    playbackSpeed =
                        (preferences[Keys.LastSpeed] ?: 1f)
                            .takeIf { it.isFinite() && it in 0.25f..4f }
                            ?: 1f,
                    subtitleOffsetMs =
                        (preferences[Keys.LastOffset] ?: 0L).coerceIn(-60_000L, 60_000L),
                    queue = decodeQueue(preferences[Keys.LastQueue], media),
                    currentIndex = (preferences[Keys.LastQueueIndex] ?: 0).coerceAtLeast(0),
                    shuffleEnabled = preferences[Keys.LastShuffle] ?: false,
                    repeatMode = (preferences[Keys.LastRepeatMode] ?: 0).coerceIn(0, 2),
                    lastSurface =
                        PlayerSurface.restoreLastSurface(preferences[Keys.LastSurface]),
                )
            }
        return StoredAppState(
            draft =
                StoredDraft(
                    media = preferences.readMediaSource(Keys.DraftMedia),
                    subtitle = preferences.readSubtitleSource(Keys.DraftSubtitle),
                ),
            lastSession = lastSession,
            displayProfile =
                DisplayProfile.restore(
                    name = preferences[Keys.DisplayProfile],
                    windowBrightness = preferences[Keys.DisplayWindowBrightness],
                    subtitleLevel = preferences[Keys.DisplaySubtitleLevel],
                    controlLevel = preferences[Keys.DisplayControlLevel],
                    subtitleSizeSp = preferences[Keys.DisplaySubtitleSize],
                    subtitleVerticalBias = preferences[Keys.DisplaySubtitleVerticalBias],
                ),
            glowCalibrationCompleted = preferences[Keys.GlowCalibrationCompleted] ?: false,
            glowGestureHintSeen = preferences[Keys.GlowGestureHintSeen] ?: false,
        )
    }

    suspend fun saveDraft(draft: SessionDraft) {
        val media = draft.media.source
        val subtitle =
            when (val value = draft.subtitle) {
                DraftSubtitle.None -> null
                is DraftSubtitle.Loading -> value.source
                is DraftSubtitle.Ready -> value.subtitle.source
                is DraftSubtitle.Failed -> value.source
            }
        dataStore.edit { preferences ->
            preferences.writeMediaSource(Keys.DraftMedia, media)
            preferences.writeSubtitleSource(Keys.DraftSubtitle, subtitle)
        }
    }

    suspend fun saveLastSession(session: RestorableSession) {
        dataStore.edit { preferences ->
            preferences.writeMediaSource(Keys.LastMedia, session.session.media)
            preferences.writeSubtitleSource(Keys.LastSubtitle, session.session.subtitle?.source)
            preferences.writeSubtitleSource(
                Keys.LastSecondarySubtitle,
                session.session.secondarySubtitle?.source,
            )
            preferences[Keys.LastPosition] = session.positionMs.coerceAtLeast(0L)
            preferences[Keys.LastDuration] = session.durationMs.coerceAtLeast(0L)
            preferences[Keys.LastSpeed] =
                session.playbackSpeed
                    .takeIf { it.isFinite() && it in 0.25f..4f }
                    ?: 1f
            preferences[Keys.LastOffset] =
                session.session.subtitleOffsetMs.coerceIn(-60_000L, 60_000L)
            preferences[Keys.LastQueue] =
                JSONObject()
                    .put("items", MediaSourceJson.encodeList(session.queue))
                    .toString()
            preferences[Keys.LastQueueIndex] = session.currentIndex.coerceAtLeast(0)
            preferences[Keys.LastShuffle] = session.shuffleEnabled
            preferences[Keys.LastRepeatMode] = session.repeatMode.coerceIn(0, 2)
            preferences[Keys.LastSurface] = session.lastSurface.asLastSurface().name
        }
    }

    suspend fun saveDisplayProfile(profile: DisplayProfile) {
        dataStore.edit { preferences ->
            preferences[Keys.DisplayProfile] = profile.name
            preferences[Keys.DisplayWindowBrightness] =
                profile.glowWindowBrightness.coerceIn(0.01f, 0.30f)
            preferences[Keys.DisplaySubtitleLevel] =
                profile.glowSubtitleLevel.coerceIn(4, 96)
            preferences[Keys.DisplayControlLevel] =
                profile.glowControlLevel.coerceIn(24, 160)
            preferences[Keys.DisplaySubtitleSize] =
                profile.glowSubtitleSizeSp.coerceIn(18f, 36f)
            preferences[Keys.DisplaySubtitleVerticalBias] =
                profile.glowSubtitleVerticalBias.coerceIn(-0.60f, 0.60f)
        }
    }

    suspend fun saveGlowOnboarding(
        calibrationCompleted: Boolean,
        gestureHintSeen: Boolean,
    ) {
        dataStore.edit { preferences ->
            preferences[Keys.GlowCalibrationCompleted] = calibrationCompleted
            preferences[Keys.GlowGestureHintSeen] = gestureHintSeen
        }
    }

    private fun Preferences.readMediaSource(keys: SourceKeys): MediaSourceRef? {
        val uri = this[keys.uri]?.let(Uri::parse) ?: return null
        return MediaSourceRef(
            uri = uri,
            displayName = this[keys.name] ?: uri.lastPathSegment ?: "未命名媒体",
            mimeType = this[keys.mime],
            title = this[keys.title],
            artist = this[keys.artist],
            album = this[keys.album],
            durationMs = (this[keys.duration] ?: 0L).coerceAtLeast(0L),
            folder = this[keys.folder],
            dateAddedSeconds = (this[keys.dateAdded] ?: 0L).coerceAtLeast(0L),
            origin =
                runCatching { MediaOrigin.valueOf(this[keys.origin].orEmpty()) }
                    .getOrDefault(MediaOrigin.OpenDocument),
            sourceId = this[keys.sourceId],
            kind =
                runCatching { MediaKind.valueOf(this[keys.kind].orEmpty()) }
                    .getOrDefault(
                        MediaKind.infer(
                            this[keys.mime],
                            this[keys.name] ?: uri.lastPathSegment.orEmpty(),
                        ),
                    ),
            sizeBytes = (this[keys.size] ?: 0L).coerceAtLeast(0L),
            videoWidth = (this[keys.videoWidth] ?: 0).coerceAtLeast(0),
            videoHeight = (this[keys.videoHeight] ?: 0).coerceAtLeast(0),
        )
    }

    private fun Preferences.readSubtitleSource(keys: SubtitleKeys): SubtitleSourceRef? {
        val uri = this[keys.uri]?.let(Uri::parse) ?: return null
        return SubtitleSourceRef(
            uri = uri,
            displayName = this[keys.name] ?: uri.lastPathSegment ?: "未命名字幕",
            mimeType = this[keys.mime],
            cachedFileName = this[keys.cache],
        )
    }

    private fun MutablePreferences.writeMediaSource(
        keys: SourceKeys,
        source: MediaSourceRef?,
    ) {
        writeOptional(keys.uri, source?.uri?.toString())
        writeOptional(keys.name, source?.displayName)
        writeOptional(keys.mime, source?.mimeType)
        writeOptional(keys.title, source?.title)
        writeOptional(keys.artist, source?.artist)
        writeOptional(keys.album, source?.album)
        writeOptional(keys.folder, source?.folder)
        writeOptional(keys.origin, source?.origin?.name)
        writeOptional(keys.sourceId, source?.sourceId)
        writeOptional(keys.duration, source?.durationMs)
        writeOptional(keys.dateAdded, source?.dateAddedSeconds)
        writeOptional(keys.kind, source?.kind?.name)
        writeOptional(keys.size, source?.sizeBytes)
        writeOptional(keys.videoWidth, source?.videoWidth)
        writeOptional(keys.videoHeight, source?.videoHeight)
    }

    private fun MutablePreferences.writeSubtitleSource(
        keys: SubtitleKeys,
        source: SubtitleSourceRef?,
    ) {
        writeOptional(keys.uri, source?.uri?.toString())
        writeOptional(keys.name, source?.displayName)
        writeOptional(keys.mime, source?.mimeType)
        writeOptional(keys.cache, source?.cachedFileName)
    }

    private fun MutablePreferences.writeOptional(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun MutablePreferences.writeOptional(
        key: Preferences.Key<Long>,
        value: Long?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun MutablePreferences.writeOptional(
        key: Preferences.Key<Int>,
        value: Int?,
    ) {
        if (value == null) remove(key) else this[key] = value
    }

    private data class SourceKeys(
        val uri: Preferences.Key<String>,
        val name: Preferences.Key<String>,
        val mime: Preferences.Key<String>,
        val title: Preferences.Key<String>,
        val artist: Preferences.Key<String>,
        val album: Preferences.Key<String>,
        val duration: Preferences.Key<Long>,
        val folder: Preferences.Key<String>,
        val dateAdded: Preferences.Key<Long>,
        val origin: Preferences.Key<String>,
        val sourceId: Preferences.Key<String>,
        val kind: Preferences.Key<String>,
        val size: Preferences.Key<Long>,
        val videoWidth: Preferences.Key<Int>,
        val videoHeight: Preferences.Key<Int>,
    )

    private data class SubtitleKeys(
        val uri: Preferences.Key<String>,
        val name: Preferences.Key<String>,
        val mime: Preferences.Key<String>,
        val cache: Preferences.Key<String>,
    )

    private object Keys {
        val DraftMedia = sourceKeys("draft_media")
        val DraftSubtitle = subtitleKeys("draft_subtitle")
        val LastMedia = sourceKeys("last_media")
        val LastSubtitle = subtitleKeys("last_subtitle")
        val LastSecondarySubtitle = subtitleKeys("last_secondary_subtitle")
        val LastPosition = longPreferencesKey("last_position_ms")
        val LastDuration = longPreferencesKey("last_duration_ms")
        val LastSpeed = floatPreferencesKey("last_speed")
        val LastOffset = longPreferencesKey("last_subtitle_offset_ms")
        val LastQueue = stringPreferencesKey("last_queue_json")
        val LastQueueIndex = intPreferencesKey("last_queue_index")
        val LastShuffle = booleanPreferencesKey("last_shuffle")
        val LastRepeatMode = intPreferencesKey("last_repeat_mode")
        val LastSurface = stringPreferencesKey("last_surface")
        val DisplayProfile = stringPreferencesKey("display_profile")
        val DisplayWindowBrightness = floatPreferencesKey("display_window_brightness")
        val DisplaySubtitleLevel = intPreferencesKey("display_subtitle_level")
        val DisplayControlLevel = intPreferencesKey("display_control_level")
        val DisplaySubtitleSize = floatPreferencesKey("display_subtitle_size_sp")
        val DisplaySubtitleVerticalBias = floatPreferencesKey("display_subtitle_vertical_bias")
        val GlowCalibrationCompleted = booleanPreferencesKey("glow_calibration_completed")
        val GlowGestureHintSeen = booleanPreferencesKey("glow_gesture_hint_seen")

        private fun sourceKeys(prefix: String) =
            SourceKeys(
                uri = stringPreferencesKey("${prefix}_uri"),
                name = stringPreferencesKey("${prefix}_name"),
                mime = stringPreferencesKey("${prefix}_mime"),
                title = stringPreferencesKey("${prefix}_title"),
                artist = stringPreferencesKey("${prefix}_artist"),
                album = stringPreferencesKey("${prefix}_album"),
                duration = longPreferencesKey("${prefix}_duration_ms"),
                folder = stringPreferencesKey("${prefix}_folder"),
                dateAdded = longPreferencesKey("${prefix}_date_added_seconds"),
                origin = stringPreferencesKey("${prefix}_origin"),
                sourceId = stringPreferencesKey("${prefix}_source_id"),
                kind = stringPreferencesKey("${prefix}_kind"),
                size = longPreferencesKey("${prefix}_size_bytes"),
                videoWidth = intPreferencesKey("${prefix}_video_width"),
                videoHeight = intPreferencesKey("${prefix}_video_height"),
            )

        private fun subtitleKeys(prefix: String) =
            SubtitleKeys(
                uri = stringPreferencesKey("${prefix}_uri"),
                name = stringPreferencesKey("${prefix}_name"),
                mime = stringPreferencesKey("${prefix}_mime"),
                cache = stringPreferencesKey("${prefix}_cache"),
            )
    }
}

private fun decodeQueue(
    encoded: String?,
    fallback: MediaSourceRef,
): List<MediaSourceRef> =
    runCatching {
        MediaSourceJson.decodeList(JSONObject(encoded.orEmpty()).optJSONArray("items"))
    }.getOrDefault(emptyList()).ifEmpty { listOf(fallback) }
