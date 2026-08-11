package io.github.zvensmoluya.vortexcue.core.storage

import io.github.zvensmoluya.vortexcue.core.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object ContentUnitJson {
    fun encodeList(units: List<PlayableContentUnit>): JSONArray =
        JSONArray().apply { units.forEach { put(encode(it)) } }

    fun decodeList(array: JSONArray?): List<PlayableContentUnit> =
        array.mapObjects(::decode)

    private fun encode(unit: PlayableContentUnit): JSONObject =
        JSONObject()
            .put("stableId", unit.stableId)
            .put("media", MediaSourceJson.encode(unit.media))
            .putNullable("sourceId", unit.sourceId)
            .put("availability", unit.availability.name)
            .put(
                "timedTextTracks",
                JSONArray().apply { unit.timedTextTracks.forEach { put(encodeTrack(it)) } },
            )
            .put("activeText", encodeActiveText(unit.activeText))

    private fun decode(json: JSONObject): PlayableContentUnit {
        val media = MediaSourceJson.decode(json.getJSONObject("media"))
        val stableId =
            json.optString("stableId").ifBlank {
                stableContentId(media.uri.toString())
            }
        return PlayableContentUnit(
            stableId = stableId,
            media = media,
            sourceId = json.nullableString("sourceId") ?: media.sourceId,
            timedTextTracks =
                json.optJSONArray("timedTextTracks")
                    .mapObjects { decodeTrack(it, stableId) }
                    .distinctBy(TimedTextTrack::stableId),
            activeText =
                json.optJSONObject("activeText")?.let(::decodeActiveText)
                    ?: ActiveTextSelection(),
            availability =
                enumValueOrDefault(
                    json.optString("availability"),
                    ContentAvailability.Unchecked,
                ),
        )
    }

    private fun encodeTrack(track: TimedTextTrack): JSONObject =
        JSONObject()
            .put("stableId", track.stableId)
            .put("origin", track.origin.name)
            .put(
                "locator",
                JSONObject()
                    .putNullable("uriValue", track.locator.uriValue)
                    .putNullable("embeddedTrackId", track.locator.embeddedTrackId)
                    .put("displayName", track.locator.displayName)
                    .putNullable("mimeType", track.locator.mimeType)
                    .putNullable("cachedFileName", track.locator.cachedFileName),
            )
            .putNullable("format", track.format)
            .putNullable("language", track.language)
            .putNullable("label", track.label)
            .put("roles", JSONArray(track.roles.map(TimedTextRole::name)))
            .put("matchBasis", track.matchBasis.name)
            .put("readiness", track.readiness.name)
            .putNullable("failureMessage", track.failureMessage)

    private fun decodeTrack(
        json: JSONObject,
        contentId: String,
    ): TimedTextTrack {
        val origin =
            enumValueOrDefault(
                json.optString("origin"),
                TimedTextOrigin.Sidecar,
            )
        val locatorJson = json.getJSONObject("locator")
        val locator =
            TimedTextLocator(
                uriValue = locatorJson.nullableString("uriValue"),
                embeddedTrackId = locatorJson.nullableString("embeddedTrackId"),
                displayName = locatorJson.optString("displayName").ifBlank { "未命名字幕" },
                mimeType = locatorJson.nullableString("mimeType"),
                cachedFileName = locatorJson.nullableString("cachedFileName"),
            )
        return TimedTextTrack(
            stableId =
                json.optString("stableId").ifBlank {
                    stableTimedTextTrackId(contentId, origin, locator.stableIdentity)
                },
            origin = origin,
            locator = locator,
            format = json.nullableString("format"),
            language = json.nullableString("language"),
            label = json.nullableString("label"),
            roles =
                json.optJSONArray("roles")
                    .enumValues<TimedTextRole>()
                    .distinct(),
            matchBasis =
                enumValueOrDefault(
                    json.optString("matchBasis"),
                    TimedTextMatchBasis.UserSelected,
                ),
            readiness =
                enumValueOrDefault(
                    json.optString("readiness"),
                    TimedTextReadiness.Unknown,
                ),
            failureMessage = json.nullableString("failureMessage"),
        )
    }

    private fun encodeActiveText(selection: ActiveTextSelection): JSONObject =
        JSONObject()
            .putNullable("primaryTrackId", selection.primaryTrackId)
            .putNullable("secondaryTrackId", selection.secondaryTrackId)
            .put("primaryMode", selection.primaryMode.name)
            .put("secondaryMode", selection.secondaryMode.name)
            .put("rejectedTrackIds", JSONArray(selection.rejectedTrackIds))

    private fun decodeActiveText(json: JSONObject): ActiveTextSelection =
        ActiveTextSelection(
            primaryTrackId = json.nullableString("primaryTrackId"),
            secondaryTrackId = json.nullableString("secondaryTrackId"),
            primaryMode =
                enumValueOrDefault(
                    json.optString("primaryMode"),
                    TextSelectionMode.Automatic,
                ),
            secondaryMode =
                enumValueOrDefault(
                    json.optString("secondaryMode"),
                    TextSelectionMode.Automatic,
                ),
            rejectedTrackIds = json.optJSONArray("rejectedTrackIds").stringValues(),
        )
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String,
    default: T,
): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

private inline fun <reified T : Enum<T>> JSONArray?.enumValues(): List<T> =
    stringValues().mapNotNull { runCatching { enumValueOf<T>(it) }.getOrNull() }

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
