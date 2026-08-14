package io.github.zvensmoluya.vortexcue.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.zvensmoluya.vortexcue.core.model.DisplayProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var settings: DataStore<Preferences>
    private lateinit var legacySession: DataStore<Preferences>
    private lateinit var legacyLibrary: DataStore<Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        settings = dataStore("settings.preferences_pb")
        legacySession = dataStore("session.preferences_pb")
        legacyLibrary = dataStore("library.preferences_pb")
        store = SettingsStore(settings, legacySession, legacyLibrary)
    }

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
    }

    @Test
    fun importsOnlyDisplaySettingsThenClearsLegacyDomainStores() = runBlocking {
        legacySession.edit { preferences ->
            preferences[stringPreferencesKey("display_profile")] = DisplayProfile.CustomName
            preferences[floatPreferencesKey("display_window_brightness")] = 0.12f
            preferences[intPreferencesKey("display_subtitle_level")] = 28
            preferences[intPreferencesKey("display_control_level")] = 90
            preferences[floatPreferencesKey("display_subtitle_size_sp")] = 26f
            preferences[floatPreferencesKey("display_subtitle_vertical_bias")] = -0.2f
            preferences[booleanPreferencesKey("glow_calibration_completed")] = true
            preferences[booleanPreferencesKey("glow_gesture_hint_seen")] = true
            preferences[stringPreferencesKey("last_queue_json")] = "domain queue"
            preferences[stringPreferencesKey("draft_media_uri")] = "content://legacy/draft"
        }
        legacyLibrary.edit { preferences ->
            preferences[stringPreferencesKey("state_json")] = "domain library"
            preferences[intPreferencesKey("encoding_version")] = 2
        }

        store.initializeFromLegacyAndCleanDomainStores()

        val loaded = store.load()
        assertEquals(DisplayProfile.CustomName, loaded.displayProfile.name)
        assertEquals(0.12f, loaded.displayProfile.glowWindowBrightness)
        assertEquals(28, loaded.displayProfile.glowSubtitleLevel)
        assertEquals(90, loaded.displayProfile.glowControlLevel)
        assertEquals(26f, loaded.displayProfile.glowSubtitleSizeSp)
        assertEquals(-0.2f, loaded.displayProfile.glowSubtitleVerticalBias)
        assertTrue(loaded.glowCalibrationCompleted)
        assertTrue(loaded.glowGestureHintSeen)
        assertTrue(legacySession.data.first().asMap().isEmpty())
        assertTrue(legacyLibrary.data.first().asMap().isEmpty())

        val storedNames = settings.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(
            setOf(
                "schema_version",
                "display_profile",
                "display_window_brightness",
                "display_subtitle_level",
                "display_control_level",
                "display_subtitle_size_sp",
                "display_subtitle_vertical_bias",
                "glow_calibration_completed",
                "glow_gesture_hint_seen",
            ),
            storedNames,
        )
    }

    @Test
    fun completedImportIsNotOverwrittenButLegacyStoresAreStillCleared() = runBlocking {
        store.saveDisplayProfile(DisplayProfile.Soft)
        legacySession.edit { preferences ->
            preferences[stringPreferencesKey("display_profile")] = DisplayProfile.HighReadability.name
            preferences[stringPreferencesKey("last_queue_json")] = "stale queue"
        }
        legacyLibrary.edit { preferences ->
            preferences[stringPreferencesKey("state_json")] = "stale library"
        }

        store.initializeFromLegacyAndCleanDomainStores()

        assertEquals(DisplayProfile.Soft, store.load().displayProfile)
        assertTrue(legacySession.data.first().asMap().isEmpty())
        assertTrue(legacyLibrary.data.first().asMap().isEmpty())
    }

    private fun dataStore(fileName: String): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(temporaryFolder.root, fileName) },
        )
    }
}
