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
            .put("version", 1)
            .put("manualItems", JSONArray().apply { config.manualItems.forEach { put(it.toJson()) } })
            .put("folders", JSONArray().apply { config.folders.forEach { put(it.toJson()) } })
            .put("playlists", JSONArray().apply { config.playlists.forEach { put(it.toJson()) } })

    private fun decodeConfig(json: JSONObject): LibraryConfig =
        LibraryConfig(
            manualItems = json.optJSONArray("manualItems").mapObjects(::mediaSourceFromJson),
            folders = json.optJSONArray("folders").mapObjects(::folderFromJson),
            playlists = json.optJSONArray("playlists").mapObjects(::playlistFromJson),
        )

    private fun MediaSourceRef.toJson(): JSONObject =
        JSONObject()
            .put("uri", uri.toString())
            .put("displayName", displayName)
            .putNullable("mimeType", mimeType)
            .putNullable("title", title)
            .putNullable("artist", artist)
            .putNullable("album", album)
            .put("durationMs", durationMs)
            .putNullable("folder", folder)
            .put("dateAddedSeconds", dateAddedSeconds)
            .put("origin", origin.name)

    private fun LibraryFolderRef.toJson(): JSONObject =
        JSONObject()
            .put("uri", uri.toString())
            .put("displayName", displayName)

    private fun LibraryPlaylist.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })

    private fun mediaSourceFromJson(json: JSONObject): MediaSourceRef =
        MediaSourceRef(
            uri = Uri.parse(json.getString("uri")),
            displayName = json.optString("displayName").ifBlank { "未命名媒体" },
            mimeType = json.nullableString("mimeType"),
            title = json.nullableString("title"),
            artist = json.nullableString("artist"),
            album = json.nullableString("album"),
            durationMs = json.optLong("durationMs").coerceAtLeast(0L),
            folder = json.nullableString("folder"),
            dateAddedSeconds = json.optLong("dateAddedSeconds").coerceAtLeast(0L),
            origin =
                runCatching { MediaOrigin.valueOf(json.optString("origin")) }
                    .getOrDefault(MediaOrigin.OpenDocument),
        )

    private fun folderFromJson(json: JSONObject): LibraryFolderRef =
        LibraryFolderRef(
            uri = Uri.parse(json.getString("uri")),
            displayName = json.optString("displayName").ifBlank { "已授权文件夹" },
        )

    private fun playlistFromJson(json: JSONObject): LibraryPlaylist =
        LibraryPlaylist(
            id = json.optString("id"),
            name = json.optString("name").ifBlank { "未命名播放列表" },
            items = json.optJSONArray("items").mapObjects(::mediaSourceFromJson),
        )

    private fun JSONObject.putNullable(
        key: String,
        value: String?,
    ): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { json ->
                    runCatching { transform(json) }.getOrNull()?.let(::add)
                }
            }
        }
    }

    private companion object {
        val StateKey = stringPreferencesKey("state_json")
    }
}
