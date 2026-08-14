package io.github.zvensmoluya.vortexcue.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings_state")
private val Context.legacySessionDataStore by preferencesDataStore(name = "session_state")
private val Context.legacyLibraryDataStore by preferencesDataStore(name = "library_state")

data class SettingsState(
    val displayProfile: DisplayProfile = DisplayProfile.Balanced,
    val glowCalibrationCompleted: Boolean = false,
    val glowGestureHintSeen: Boolean = false,
)

class SettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val legacySessionDataStore: DataStore<Preferences>,
    private val legacyLibraryDataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.settingsDataStore,
        legacySessionDataStore = context.applicationContext.legacySessionDataStore,
        legacyLibraryDataStore = context.applicationContext.legacyLibraryDataStore,
    )

    val state: Flow<SettingsState> = dataStore.data.map(::decode)

    suspend fun initializeFromLegacyAndCleanDomainStores() {
        val imported = decodeLegacy(legacySessionDataStore.data.first())
        dataStore.edit { preferences ->
            if (preferences[Keys.SchemaVersion] != CurrentSchemaVersion) {
                preferences.clear()
                preferences[Keys.SchemaVersion] = CurrentSchemaVersion
                preferences.write(imported)
            }
        }
        legacyLibraryDataStore.edit { it.clear() }
        legacySessionDataStore.edit { it.clear() }
    }

    suspend fun load(): SettingsState = decode(dataStore.data.first())

    suspend fun saveDisplayProfile(profile: DisplayProfile) {
        dataStore.edit { preferences ->
            preferences[Keys.SchemaVersion] = CurrentSchemaVersion
            preferences[Keys.displayProfile] = profile.name
            preferences[Keys.displayWindowBrightness] = profile.glowWindowBrightness.coerceIn(0.01f, 0.30f)
            preferences[Keys.displaySubtitleLevel] = profile.glowSubtitleLevel.coerceIn(4, 96)
            preferences[Keys.displayControlLevel] = profile.glowControlLevel.coerceIn(24, 160)
            preferences[Keys.displaySubtitleSize] = profile.glowSubtitleSizeSp.coerceIn(18f, 36f)
            preferences[Keys.displaySubtitleVerticalBias] = profile.glowSubtitleVerticalBias.coerceIn(-0.60f, 0.60f)
        }
    }

    suspend fun saveGlowOnboarding(
        calibrationCompleted: Boolean,
        gestureHintSeen: Boolean,
    ) {
        dataStore.edit { preferences ->
            preferences[Keys.SchemaVersion] = CurrentSchemaVersion
            preferences[Keys.glowCalibrationCompleted] = calibrationCompleted
            preferences[Keys.glowGestureHintSeen] = gestureHintSeen
        }
    }

    private fun decode(preferences: Preferences): SettingsState =
        if (preferences[Keys.SchemaVersion] == CurrentSchemaVersion) {
            preferences.toSettings(Keys)
        } else {
            SettingsState()
        }

    private fun decodeLegacy(preferences: Preferences): SettingsState =
        preferences.toSettings(LegacyKeys)

    private fun Preferences.toSettings(keys: SettingsKeys): SettingsState =
        SettingsState(
            displayProfile =
                DisplayProfile.restore(
                    name = this[keys.displayProfile],
                    windowBrightness = this[keys.displayWindowBrightness],
                    subtitleLevel = this[keys.displaySubtitleLevel],
                    controlLevel = this[keys.displayControlLevel],
                    subtitleSizeSp = this[keys.displaySubtitleSize],
                    subtitleVerticalBias = this[keys.displaySubtitleVerticalBias],
                ),
            glowCalibrationCompleted = this[keys.glowCalibrationCompleted] ?: false,
            glowGestureHintSeen = this[keys.glowGestureHintSeen] ?: false,
        )

    private fun androidx.datastore.preferences.core.MutablePreferences.write(state: SettingsState) {
        val profile = state.displayProfile
        this[Keys.displayProfile] = profile.name
        this[Keys.displayWindowBrightness] = profile.glowWindowBrightness
        this[Keys.displaySubtitleLevel] = profile.glowSubtitleLevel
        this[Keys.displayControlLevel] = profile.glowControlLevel
        this[Keys.displaySubtitleSize] = profile.glowSubtitleSizeSp
        this[Keys.displaySubtitleVerticalBias] = profile.glowSubtitleVerticalBias
        this[Keys.glowCalibrationCompleted] = state.glowCalibrationCompleted
        this[Keys.glowGestureHintSeen] = state.glowGestureHintSeen
    }

    private interface SettingsKeys {
        val displayProfile: Preferences.Key<String>
        val displayWindowBrightness: Preferences.Key<Float>
        val displaySubtitleLevel: Preferences.Key<Int>
        val displayControlLevel: Preferences.Key<Int>
        val displaySubtitleSize: Preferences.Key<Float>
        val displaySubtitleVerticalBias: Preferences.Key<Float>
        val glowCalibrationCompleted: Preferences.Key<Boolean>
        val glowGestureHintSeen: Preferences.Key<Boolean>
    }

    private object Keys : SettingsKeys {
        val SchemaVersion = intPreferencesKey("schema_version")
        override val displayProfile = stringPreferencesKey("display_profile")
        override val displayWindowBrightness = floatPreferencesKey("display_window_brightness")
        override val displaySubtitleLevel = intPreferencesKey("display_subtitle_level")
        override val displayControlLevel = intPreferencesKey("display_control_level")
        override val displaySubtitleSize = floatPreferencesKey("display_subtitle_size_sp")
        override val displaySubtitleVerticalBias = floatPreferencesKey("display_subtitle_vertical_bias")
        override val glowCalibrationCompleted = booleanPreferencesKey("glow_calibration_completed")
        override val glowGestureHintSeen = booleanPreferencesKey("glow_gesture_hint_seen")
    }

    private object LegacyKeys : SettingsKeys {
        override val displayProfile = stringPreferencesKey("display_profile")
        override val displayWindowBrightness = floatPreferencesKey("display_window_brightness")
        override val displaySubtitleLevel = intPreferencesKey("display_subtitle_level")
        override val displayControlLevel = intPreferencesKey("display_control_level")
        override val displaySubtitleSize = floatPreferencesKey("display_subtitle_size_sp")
        override val displaySubtitleVerticalBias = floatPreferencesKey("display_subtitle_vertical_bias")
        override val glowCalibrationCompleted = booleanPreferencesKey("glow_calibration_completed")
        override val glowGestureHintSeen = booleanPreferencesKey("glow_gesture_hint_seen")
    }

    private companion object {
        const val CurrentSchemaVersion = 1
    }
}
