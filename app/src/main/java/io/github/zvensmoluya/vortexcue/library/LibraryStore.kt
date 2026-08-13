package io.github.zvensmoluya.vortexcue.library

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.core.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.libraryDataStore by preferencesDataStore(name = "library_state")

internal data class LoadedLibraryConfig(
    val config: LibraryConfig,
    val needsEncodingUpgrade: Boolean,
)

class LibraryStore(context: Context) {
    private val dataStore = context.applicationContext.libraryDataStore

    suspend fun load(): LibraryConfig = loadConfig().config

    internal suspend fun loadConfig(): LoadedLibraryConfig {
        val preferences = dataStore.data.first()
        val encoded = preferences[StateKey]
            ?: return LoadedLibraryConfig(LibraryConfig(), needsEncodingUpgrade = false)
        val config =
            withContext(Dispatchers.Default) {
                val json = runCatching { JSONObject(encoded) }.getOrNull()
                    ?: return@withContext null
                if (!isCurrentPreReleaseSchema(json.optInt("version"), CurrentVersion)) {
                    return@withContext null
                }
                runCatching { decodeConfig(json) }.getOrNull()
            } ?: reset()
        return LoadedLibraryConfig(
            config = config,
            needsEncodingUpgrade =
                preferences[EncodingVersionKey] != CurrentEncodingVersion,
        )
    }

    suspend fun save(config: LibraryConfig) {
        val encoded =
            withContext(Dispatchers.Default) {
                encodeConfig(config).toString()
            }
        dataStore.edit { preferences ->
            preferences[StateKey] = encoded
            preferences[EncodingVersionKey] = CurrentEncodingVersion
        }
    }

    private fun encodeConfig(config: LibraryConfig): JSONObject =
        JSONObject()
            .put("version", CurrentVersion)
            .put(
                "contentUnits",
                ContentUnitJson.encodeList(config.contentUnits, config.catalogItems),
            )
            .put("catalogItems", MediaSourceJson.encodeList(config.catalogItems))
            .put("recentItems", MediaSourceJson.encodeList(config.recentItems))
            .put("folders", JSONArray().apply { config.folders.forEach { put(it.toJson()) } })
            .put("playlists", JSONArray().apply { config.playlists.forEach { put(it.toJson()) } })
            .put("includedUris", JSONArray(config.includedUris))
            .put("ignoredUris", JSONArray(config.ignoredUris))
            .put(
                "homeShortcuts",
                JSONArray().apply { config.homeShortcuts.forEach { put(it.toJson()) } },
            )

    private fun decodeConfig(json: JSONObject): LibraryConfig {
        val catalogItems = MediaSourceJson.decodeList(json.optJSONArray("catalogItems"))
        return LibraryConfig(
            contentUnits =
                ContentUnitJson.decodeList(
                    json.optJSONArray("contentUnits"),
                    catalogItems,
                ),
            catalogItems = catalogItems,
            recentItems = MediaSourceJson.decodeList(json.optJSONArray("recentItems")),
            folders = json.optJSONArray("folders").mapObjects(::folderFromJson),
            playlists = json.optJSONArray("playlists").mapObjects(::playlistFromJson),
            includedUris = json.optJSONArray("includedUris").stringValues().distinct(),
            ignoredUris = json.optJSONArray("ignoredUris").stringValues().distinct(),
            homeShortcuts =
                json.optJSONArray("homeShortcuts").mapObjects(::shortcutFromJson).distinct(),
        )
    }

    private suspend fun reset(): LibraryConfig =
        LibraryConfig().also { save(it) }

    private fun LibraryFolderRef.toJson(): JSONObject =
        JSONObject()
            .put("uri", uri.toString())
            .put("displayName", displayName)
            .put("id", id)

    private fun LibraryShortcut.toJson(): JSONObject =
        JSONObject()
            .put("type", type.name)
            .put("key", key)

    private fun LibraryPlaylist.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("items", MediaSourceJson.encodeList(items))

    private fun folderFromJson(json: JSONObject): LibraryFolderRef =
        LibraryFolderRef(
            uri = Uri.parse(json.getString("uri")),
            displayName = json.optString("displayName").ifBlank { "已授权文件夹" },
            id =
                json.optString("id").ifBlank {
                    "folder:${json.getString("uri")}"
                },
        )

    private fun shortcutFromJson(json: JSONObject): LibraryShortcut =
        LibraryShortcut(
            type = LibraryShortcutType.valueOf(json.getString("type")),
            key = json.getString("key").takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Shortcut key is empty"),
        )

    private fun playlistFromJson(json: JSONObject): LibraryPlaylist =
        LibraryPlaylist(
            id = json.optString("id"),
            name = json.optString("name").ifBlank { "未命名播放列表" },
            items = MediaSourceJson.decodeList(json.optJSONArray("items")),
        )

    private companion object {
        const val CurrentVersion = 7
        const val CurrentEncodingVersion = 2
        val StateKey = stringPreferencesKey("state_json")
        val EncodingVersionKey = intPreferencesKey("encoding_version")
    }
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
