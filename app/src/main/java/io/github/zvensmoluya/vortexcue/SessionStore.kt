package io.github.zvensmoluya.vortexcue

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
            displayProfile = DisplayProfile.fromName(preferences[Keys.DisplayProfile]),
        )
    }

    suspend fun saveDraft(draft: SessionDraft) {
        val subtitle =
            when (val value = draft.subtitle) {
                DraftSubtitle.None -> null
                is DraftSubtitle.Loading -> value.source
                is DraftSubtitle.Ready -> value.subtitle.source
                is DraftSubtitle.Failed -> value.source
            }
        dataStore.edit { preferences ->
            preferences.writeMediaSource(Keys.DraftMedia, draft.media)
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
        dataStore.edit { preferences -> preferences[Keys.DisplayProfile] = profile.name }
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
