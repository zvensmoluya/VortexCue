package io.github.zvensmoluya.vortexcue.core.storage

import io.github.zvensmoluya.vortexcue.core.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object ContentUnitJson {
    fun encodeList(
        units: List<PlayableContentUnit>,
        catalogItems: List<MediaSourceRef> = emptyList(),
    ): JSONArray {
        val catalogIndexByLocatorId =
            buildMap {
                catalogItems.forEachIndexed { index, media ->
                    putIfAbsent(stableMediaLocatorId(media.uri.toString(), media.sourceId), index)
                }
            }
        return JSONArray().apply {
            units.forEach { unit ->
                val catalogIndex =
                    unit.compactCatalogIndex(
                        catalogItems = catalogItems,
                        catalogIndexByLocatorId = catalogIndexByLocatorId,
                    )
                put(
                    if (catalogIndex == null) {
                        encode(unit)
                    } else {
                        JSONObject().put(CatalogItemIndexKey, catalogIndex)
                    },
                )
            }
        }
    }

    fun decodeList(
        array: JSONArray?,
        catalogItems: List<MediaSourceRef> = emptyList(),
    ): List<PlayableContentUnit> =
        array.mapObjects { json -> decode(json, catalogItems) }

    private fun encode(unit: PlayableContentUnit): JSONObject =
        JSONObject()
            .put("stableId", unit.stableId)
            .put(
                "mediaLocators",
                JSONArray().apply { unit.mediaLocators.forEach { put(encodeLocator(it)) } },
            )
            .put("activeMediaLocatorId", unit.activeMediaLocatorId)
            .put(
                "timedTextTracks",
                JSONArray().apply { unit.timedTextTracks.forEach { put(encodeTrack(it)) } },
            )
            .put("activeText", encodeActiveText(unit.activeText))
            .put("subtitleOffsetMs", unit.subtitleOffsetMs)

    private fun decode(
        json: JSONObject,
        catalogItems: List<MediaSourceRef>,
    ): PlayableContentUnit {
        if (json.has(CatalogItemIndexKey)) {
            val catalogIndex = json.getInt(CatalogItemIndexKey)
            val media = catalogItems.getOrNull(catalogIndex)
                ?: throw IllegalArgumentException("Catalog item index is out of bounds")
            val identityKey = mediaIdentityKey(media.uri.toString())
            val locator =
                MediaLocator(
                    stableId = stableMediaLocatorId(media.uri.toString(), media.sourceId),
                    identityKey = identityKey,
                    media = media,
                    availability = ContentAvailability.Available,
                )
            return PlayableContentUnit(
                stableId = stableContentId(identityKey),
                mediaLocators = listOf(locator),
                activeMediaLocatorId = locator.stableId,
            )
        }
        val stableId = json.getString("stableId").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Content ID is empty")
        val mediaLocators =
            json.getJSONArray("mediaLocators")
                .mapObjects(::decodeLocator)
                .distinctBy(MediaLocator::stableId)
        val activeMediaLocatorId =
            json.optString("activeMediaLocatorId")
                .takeIf { candidate -> mediaLocators.any { it.stableId == candidate } }
                ?: mediaLocators.firstOrNull()?.stableId
                ?: throw IllegalArgumentException("Content has no media locator")
        return PlayableContentUnit(
            stableId = stableId,
            mediaLocators = mediaLocators,
            activeMediaLocatorId = activeMediaLocatorId,
            timedTextTracks =
                json.optJSONArray("timedTextTracks")
                    .mapObjects { decodeTrack(it, stableId) }
                    .distinctBy(TimedTextTrack::stableId),
            activeText =
                json.optJSONObject("activeText")?.let(::decodeActiveText)
                    ?: ActiveTextSelection(),
            subtitleOffsetMs =
                json.optLong("subtitleOffsetMs").coerceIn(-60_000L, 60_000L),
        )
    }

    private fun encodeLocator(locator: MediaLocator): JSONObject =
        JSONObject()
            .put("stableId", locator.stableId)
            .put("identityKey", locator.identityKey)
            .put("media", MediaSourceJson.encode(locator.media))
            .put("availability", locator.availability.name)

    private fun decodeLocator(json: JSONObject): MediaLocator {
        val media = MediaSourceJson.decode(json.getJSONObject("media"))
        return MediaLocator(
            stableId = json.getString("stableId"),
            identityKey = json.getString("identityKey"),
            media = media,
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

    private fun PlayableContentUnit.compactCatalogIndex(
        catalogItems: List<MediaSourceRef>,
        catalogIndexByLocatorId: Map<String, Int>,
    ): Int? {
        val locator = mediaLocators.singleOrNull() ?: return null
        val catalogIndex = catalogIndexByLocatorId[locator.stableId] ?: return null
        val catalogMedia = catalogItems[catalogIndex]
        val expectedIdentityKey = mediaIdentityKey(catalogMedia.uri.toString())
        val isDefaultCatalogUnit =
            locator.media == catalogMedia &&
                locator.stableId ==
                    stableMediaLocatorId(catalogMedia.uri.toString(), catalogMedia.sourceId) &&
                locator.identityKey == expectedIdentityKey &&
                locator.availability == ContentAvailability.Available &&
                stableId == stableContentId(expectedIdentityKey) &&
                activeMediaLocatorId == locator.stableId &&
                timedTextTracks.isEmpty() &&
                activeText == ActiveTextSelection() &&
                subtitleOffsetMs == 0L
        return catalogIndex.takeIf { isDefaultCatalogUnit }
    }

    private const val CatalogItemIndexKey = "catalogItemIndex"
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
