package io.github.zvensmoluya.vortexcue

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sessionDataStore by preferencesDataStore(name = "session_state")

data class StoredAppState(
    val draft: StoredDraft,
    val lastSession: StoredLastSession?,
    val displayProfile: DisplayProfile,
)

data class StoredDraft(
    val media: MediaSourceRef?,
    val subtitle: SubtitleSourceRef?,
)

data class StoredLastSession(
    val media: MediaSourceRef,
    val subtitle: SubtitleSourceRef?,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val subtitleOffsetMs: Long,
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
                    positionMs = preferences[Keys.LastPosition] ?: 0L,
                    durationMs = preferences[Keys.LastDuration] ?: 0L,
                    playbackSpeed =
                        (preferences[Keys.LastSpeed] ?: 1f)
                            .takeIf { it.isFinite() && it in 0.25f..4f }
                            ?: 1f,
                    subtitleOffsetMs =
                        (preferences[Keys.LastOffset] ?: 0L).coerceIn(-60_000L, 60_000L),
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
            preferences[Keys.LastPosition] = session.positionMs.coerceAtLeast(0L)
            preferences[Keys.LastDuration] = session.durationMs.coerceAtLeast(0L)
            preferences[Keys.LastSpeed] =
                session.playbackSpeed
                    .takeIf { it.isFinite() && it in 0.25f..4f }
                    ?: 1f
            preferences[Keys.LastOffset] =
                session.session.subtitleOffsetMs.coerceIn(-60_000L, 60_000L)
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

    private fun Preferences.readMediaSource(keys: SourceKeys): MediaSourceRef? {
        val uri = this[keys.uri]?.let(Uri::parse) ?: return null
        return MediaSourceRef(
            uri = uri,
            displayName = this[keys.name] ?: uri.lastPathSegment ?: "未命名媒体",
            mimeType = this[keys.mime],
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

    private data class SourceKeys(
        val uri: Preferences.Key<String>,
        val name: Preferences.Key<String>,
        val mime: Preferences.Key<String>,
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
        val LastPosition = longPreferencesKey("last_position_ms")
        val LastDuration = longPreferencesKey("last_duration_ms")
        val LastSpeed = floatPreferencesKey("last_speed")
        val LastOffset = longPreferencesKey("last_subtitle_offset_ms")
        val DisplayProfile = stringPreferencesKey("display_profile")
        val DisplayWindowBrightness = floatPreferencesKey("display_window_brightness")
        val DisplaySubtitleLevel = intPreferencesKey("display_subtitle_level")
        val DisplayControlLevel = intPreferencesKey("display_control_level")
        val DisplaySubtitleSize = floatPreferencesKey("display_subtitle_size_sp")
        val DisplaySubtitleVerticalBias = floatPreferencesKey("display_subtitle_vertical_bias")

        private fun sourceKeys(prefix: String) =
            SourceKeys(
                uri = stringPreferencesKey("${prefix}_uri"),
                name = stringPreferencesKey("${prefix}_name"),
                mime = stringPreferencesKey("${prefix}_mime"),
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
