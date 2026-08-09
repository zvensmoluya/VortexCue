package io.github.zvensmoluya.vortexcue

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal object MediaSourceJson {
    fun encode(source: MediaSourceRef): JSONObject =
        JSONObject()
            .put("uri", source.uri.toString())
            .put("displayName", source.displayName)
            .putNullable("mimeType", source.mimeType)
            .putNullable("title", source.title)
            .putNullable("artist", source.artist)
            .putNullable("album", source.album)
            .put("durationMs", source.durationMs)
            .putNullable("folder", source.folder)
            .put("dateAddedSeconds", source.dateAddedSeconds)
            .put("origin", source.origin.name)
            .putNullable("sourceId", source.sourceId)
            .put("kind", source.kind.name)
            .put("sizeBytes", source.sizeBytes)
            .put("videoWidth", source.videoWidth)
            .put("videoHeight", source.videoHeight)

    fun decode(json: JSONObject): MediaSourceRef =
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
            sourceId = json.nullableString("sourceId"),
            kind =
                runCatching { MediaKind.valueOf(json.optString("kind")) }
                    .getOrDefault(
                        MediaKind.infer(
                            json.nullableString("mimeType"),
                            json.optString("displayName"),
                        ),
                    ),
            sizeBytes = json.optLong("sizeBytes").coerceAtLeast(0L),
            videoWidth = json.optInt("videoWidth").coerceAtLeast(0),
            videoHeight = json.optInt("videoHeight").coerceAtLeast(0),
        )

    fun encodeList(items: List<MediaSourceRef>): JSONArray =
        JSONArray().apply { items.forEach { put(encode(it)) } }

    fun decodeList(array: JSONArray?): List<MediaSourceRef> =
        array.mapObjects(::decode)
}

internal fun JSONObject.putNullable(
    key: String,
    value: String?,
): JSONObject = put(key, value ?: JSONObject.NULL)

internal fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

internal fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { json ->
                runCatching { transform(json) }.getOrNull()?.let(::add)
            }
        }
    }
}
