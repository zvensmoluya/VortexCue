package io.github.zvensmoluya.vortexcue.library

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.vortexcue.core.model.*
import io.github.zvensmoluya.vortexcue.core.storage.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.libraryDataStore by preferencesDataStore(name = "library_state")

class LibraryStore(context: Context) {
    private val dataStore = context.applicationContext.libraryDataStore

    suspend fun load(): LibraryConfig {
        val encoded = dataStore.data.first()[StateKey] ?: return LibraryConfig()
        val json = runCatching { JSONObject(encoded) }.getOrNull() ?: return LibraryConfig()
        val config = runCatching { decodeConfig(json) }.getOrNull() ?: return LibraryConfig()
        if (json.optInt("version") < CurrentVersion) save(config)
        return config
    }

    suspend fun save(config: LibraryConfig) {
        dataStore.edit { preferences ->
            preferences[StateKey] = encodeConfig(config).toString()
        }
    }

    private fun encodeConfig(config: LibraryConfig): JSONObject =
        JSONObject()
            .put("version", CurrentVersion)
            .put("contentUnits", ContentUnitJson.encodeList(config.contentUnits))
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
            .put(
                "subtitleAssociations",
                JSONArray().apply { config.subtitleAssociations.forEach { put(it.toJson()) } },
            )

    private fun decodeConfig(json: JSONObject): LibraryConfig {
        val catalogItems = MediaSourceJson.decodeList(json.optJSONArray("catalogItems"))
        val recentItems = MediaSourceJson.decodeList(json.optJSONArray("recentItems"))
        val legacyManualItems = MediaSourceJson.decodeList(json.optJSONArray("manualItems"))
        val playlists = json.optJSONArray("playlists").mapObjects(::playlistFromJson)
        val subtitleAssociations =
            json.optJSONArray("subtitleAssociations").mapObjects(::subtitleAssociationFromJson)
        val migration =
            resolveLibraryMigration(
                catalogUriValues = catalogItems.map { it.uri.toString() },
                recentUriValues = recentItems.map { it.uri.toString() },
                includedUriValues = json.optJSONArray("includedUris").stringValues(),
                legacyManualUriValues = legacyManualItems.map { it.uri.toString() },
            )
        val sourcesByUri =
            (legacyManualItems + catalogItems + recentItems)
                .associateBy { it.uri.toString() }
        val migratedCatalogItems = migration.catalogUriValues.mapNotNull(sourcesByUri::get)
        val migratedRecentItems = migration.recentUriValues.mapNotNull(sourcesByUri::get)
        val contentUnits =
            migratePlayableContentUnits(
                storedUnits = ContentUnitJson.decodeList(json.optJSONArray("contentUnits")),
                mediaSnapshots =
                    migratedCatalogItems +
                        migratedRecentItems +
                        playlists.flatMap(LibraryPlaylist::items),
                legacyAssociations = subtitleAssociations,
            )
        return LibraryConfig(
            contentUnits = contentUnits,
            catalogItems = migratedCatalogItems,
            recentItems = migratedRecentItems,
            folders = json.optJSONArray("folders").mapObjects(::folderFromJson),
            playlists = playlists,
            subtitleAssociations = subtitleAssociations,
            includedUris = migration.includedUriValues,
            ignoredUris = json.optJSONArray("ignoredUris").stringValues().distinct(),
            homeShortcuts =
                json.optJSONArray("homeShortcuts").mapObjects(::shortcutFromJson).distinct(),
        )
    }

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
        const val CurrentVersion = 5
        val StateKey = stringPreferencesKey("state_json")
    }
}

internal data class LibraryMigration(
    val catalogUriValues: List<String>,
    val recentUriValues: List<String>,
    val includedUriValues: List<String>,
)

internal fun resolveLibraryMigration(
    catalogUriValues: List<String>,
    recentUriValues: List<String>,
    includedUriValues: List<String>,
    legacyManualUriValues: List<String>,
): LibraryMigration =
    LibraryMigration(
        catalogUriValues = (catalogUriValues + legacyManualUriValues).nonBlankDistinct(),
        recentUriValues = (recentUriValues + legacyManualUriValues).nonBlankDistinct(),
        includedUriValues = (includedUriValues + legacyManualUriValues).nonBlankDistinct(),
    )

private fun List<String>.nonBlankDistinct(): List<String> =
    filter(String::isNotBlank).distinct()

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
