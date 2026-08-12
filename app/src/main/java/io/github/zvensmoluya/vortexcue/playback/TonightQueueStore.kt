package io.github.zvensmoluya.vortexcue.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zvensmoluya.vortexcue.core.storage.MediaSourceJson
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.tonightDataStore by preferencesDataStore(name = "tonight_state")

/** Persists only the current plan; playback position remains duplicated in each entry for recovery. */
class TonightQueueStore(context: Context) {
    private val dataStore = context.applicationContext.tonightDataStore

    suspend fun load(): TonightQueue {
        val encoded = dataStore.data.first()[StateKey] ?: return TonightQueue()
        val json = runCatching { JSONObject(encoded) }.getOrNull() ?: return reset()
        if (json.optInt("version") != CurrentVersion) return reset()
        return runCatching { decode(json).compactCompleted() }.getOrElse { reset() }
    }

    suspend fun save(queue: TonightQueue) {
        dataStore.edit { preferences ->
            preferences[StateKey] = encode(queue).toString()
        }
    }

    private suspend fun reset(): TonightQueue =
        TonightQueue().also { save(it) }

    private fun encode(queue: TonightQueue): JSONObject =
        JSONObject()
            .put("version", CurrentVersion)
            .put("currentIndex", queue.currentIndex.coerceAtLeast(0))
            .put("started", queue.started)
            .put(
                "entries",
                JSONArray().apply {
                    queue.entries.forEach { entry ->
                        put(
                            JSONObject()
                                .put("contentId", entry.contentId)
                                .put("media", MediaSourceJson.encode(entry.media))
                                .put("positionMs", entry.positionMs.coerceAtLeast(0L))
                                .put("durationMs", entry.durationMs.coerceAtLeast(0L))
                                .put("completed", entry.completed),
                        )
                    }
                },
            )

    private fun decode(json: JSONObject): TonightQueue {
        val entries =
            json.optJSONArray("entries")
                .mapObjects { item ->
                    TonightQueueEntry(
                        contentId = item.getString("contentId"),
                        media = MediaSourceJson.decode(item.getJSONObject("media")),
                        positionMs = item.optLong("positionMs").coerceAtLeast(0L),
                        durationMs = item.optLong("durationMs").coerceAtLeast(0L),
                        completed = item.optBoolean("completed", false),
                    )
                }.distinctBy(TonightQueueEntry::contentId)
        if (entries.isEmpty()) return TonightQueue()
        return TonightQueue(
            entries = entries,
            currentIndex = json.optInt("currentIndex").coerceIn(entries.indices),
            started = json.optBoolean("started", false),
        )
    }

    private companion object {
        const val CurrentVersion = 1
        val StateKey = stringPreferencesKey("state_json")
    }
}

private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { item ->
                runCatching { transform(item) }.getOrNull()?.let(::add)
            }
        }
    }
}
