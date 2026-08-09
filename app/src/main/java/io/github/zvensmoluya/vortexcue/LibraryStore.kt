package io.github.zvensmoluya.vortexcue

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.libraryDataStore by preferencesDataStore(name = "library_state")

class LibraryStore(context: Context) {
    private val dataStore = context.applicationContext.libraryDataStore

    suspend fun load(): LibraryConfig {
        val encoded = dataStore.data.first()[StateKey] ?: return LibraryConfig()
        return runCatching { decodeConfig(JSONObject(encoded)) }.getOrDefault(LibraryConfig())
    }

    suspend fun save(config: LibraryConfig) {
        dataStore.edit { preferences ->
            preferences[StateKey] = encodeConfig(config).toString()
        }
    }

    private fun encodeConfig(config: LibraryConfig): JSONObject =
        JSONObject()
            .put("version", 2)
            .put("manualItems", MediaSourceJson.encodeList(config.manualItems))
            .put("folders", JSONArray().apply { config.folders.forEach { put(it.toJson()) } })
            .put("playlists", JSONArray().apply { config.playlists.forEach { put(it.toJson()) } })
            .put("pins", JSONArray().apply { config.pins.forEach { put(it.toJson()) } })
            .put(
                "subtitleAssociations",
                JSONArray().apply { config.subtitleAssociations.forEach { put(it.toJson()) } },
            )

    private fun decodeConfig(json: JSONObject): LibraryConfig =
        LibraryConfig(
            manualItems = MediaSourceJson.decodeList(json.optJSONArray("manualItems")),
            folders = json.optJSONArray("folders").mapObjects(::folderFromJson),
            playlists = json.optJSONArray("playlists").mapObjects(::playlistFromJson),
            subtitleAssociations =
                json.optJSONArray("subtitleAssociations").mapObjects(::subtitleAssociationFromJson),
            pins = json.optJSONArray("pins").mapObjects(::pinFromJson).distinct(),
        )

    private fun LibraryFolderRef.toJson(): JSONObject =
        JSONObject()
            .put("uri", uri.toString())
            .put("displayName", displayName)
            .put("id", id)

    private fun LibraryPin.toJson(): JSONObject =
        JSONObject()
            .put("type", type.name)
            .put("key", key)

    private fun LibraryPlaylist.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("items", MediaSourceJson.encodeList(items))

    private fun SubtitleAssociation.toJson(): JSONObject =
        JSONObject()
            .put("mediaUri", mediaUri.toString())
            .put(
                "subtitle",
                JSONObject()
                    .put("uri", subtitle.uri.toString())
                    .put("displayName", subtitle.displayName)
                    .putNullable("mimeType", subtitle.mimeType)
                    .putNullable("cachedFileName", subtitle.cachedFileName),
            )

    private fun folderFromJson(json: JSONObject): LibraryFolderRef =
        LibraryFolderRef(
            uri = Uri.parse(json.getString("uri")),
            displayName = json.optString("displayName").ifBlank { "已授权文件夹" },
            id =
                json.optString("id").ifBlank {
                    "folder:${json.getString("uri")}"
                },
        )

    private fun pinFromJson(json: JSONObject): LibraryPin =
        LibraryPin(
            type = LibraryPinType.valueOf(json.getString("type")),
            key = json.getString("key").takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Pin key is empty"),
        )

    private fun playlistFromJson(json: JSONObject): LibraryPlaylist =
        LibraryPlaylist(
            id = json.optString("id"),
            name = json.optString("name").ifBlank { "未命名播放列表" },
            items = MediaSourceJson.decodeList(json.optJSONArray("items")),
        )

    private fun subtitleAssociationFromJson(json: JSONObject): SubtitleAssociation {
        val subtitle = json.getJSONObject("subtitle")
        return SubtitleAssociation(
            mediaUri = Uri.parse(json.getString("mediaUri")),
            subtitle =
                SubtitleSourceRef(
                    uri = Uri.parse(subtitle.getString("uri")),
                    displayName = subtitle.optString("displayName").ifBlank { "未命名字幕" },
                    mimeType = subtitle.nullableString("mimeType"),
                    cachedFileName = subtitle.nullableString("cachedFileName"),
                ),
        )
    }

    private companion object {
        val StateKey = stringPreferencesKey("state_json")
    }
}
