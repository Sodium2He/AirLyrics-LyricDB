package com.andsi.airlyrics.media.dump

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage A probe dump of a MediaController observation.
 *
 * Missing values stay absent. This dump is not a song identity.
 * mediaId and queueId are session-scoped only.
 */
data class MediaSessionDump(
    val capturedElapsedRealtimeMs: Long,
    val packageName: String,
    val sessionEpoch: Long,
    val metadata: Map<String, MetadataValue>,
    val playback: PlaybackDump,
    val queue: List<QueueItemDump>,
    val clock: ClockDump
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("capturedElapsedRealtimeMs", capturedElapsedRealtimeMs)
            put("packageName", packageName)
            put("sessionEpoch", sessionEpoch)
            put("metadata", metadata.toJson())
            put("playback", playback.toJson())
            put("queue", JSONArray().apply { queue.forEach { put(it.toJson()) } })
            put("clock", clock.toJson())
        }
    }
}

data class MetadataValue(
    val present: Boolean,
    val kind: String,
    val stringValue: String? = null,
    val longValue: Long? = null
)

data class PlaybackDump(
    val state: Int?,
    val positionMs: Long?,
    val positionUnknown: Boolean,
    val speed: Float?,
    val lastPositionUpdateTime: Long?,
    val activeQueueItemId: Long?,
    val extrasKeys: List<String>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            putOpt("state", state)
            if (positionMs != null) put("positionMs", positionMs) else put("positionMs", JSONObject.NULL)
            put("positionUnknown", positionUnknown)
            if (speed != null) put("speed", speed.toDouble()) else put("speed", JSONObject.NULL)
            putOpt("lastPositionUpdateTime", lastPositionUpdateTime)
            if (activeQueueItemId != null) {
                put("activeQueueItemId", activeQueueItemId)
            } else {
                put("activeQueueItemId", JSONObject.NULL)
            }
            put("extrasKeys", JSONArray(extrasKeys))
        }
    }
}

data class QueueItemDump(
    val queueId: Long,
    val mediaId: String?,
    val title: String?,
    val subtitle: String?,
    val description: String?,
    val extrasKeys: List<String>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("queueId", queueId)
            putOpt("mediaId", mediaId)
            putOpt("title", title)
            putOpt("subtitle", subtitle)
            putOpt("description", description)
            put("extrasKeys", JSONArray(extrasKeys))
        }
    }
}

data class ClockDump(
    val positionBaseMs: Long?,
    val elapsedRealtimeAnchorMs: Long?,
    val playbackSpeed: Float?,
    val estimatedPositionMs: Long?,
    val playing: Boolean
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            if (positionBaseMs != null) put("positionBaseMs", positionBaseMs) else put("positionBaseMs", JSONObject.NULL)
            putOpt("elapsedRealtimeAnchorMs", elapsedRealtimeAnchorMs)
            if (playbackSpeed != null) {
                put("playbackSpeed", playbackSpeed.toDouble())
            } else {
                put("playbackSpeed", JSONObject.NULL)
            }
            if (estimatedPositionMs != null) {
                put("estimatedPositionMs", estimatedPositionMs)
            } else {
                put("estimatedPositionMs", JSONObject.NULL)
            }
            put("playing", playing)
        }
    }
}

private fun Map<String, MetadataValue>.toJson(): JSONObject {
    val json = JSONObject()
    entries.sortedBy { it.key }.forEach { (key, value) ->
        json.put(
            key,
            JSONObject().apply {
                put("present", value.present)
                put("kind", value.kind)
                putOpt("string", value.stringValue)
                if (value.longValue != null) put("long", value.longValue) else put("long", JSONObject.NULL)
            }
        )
    }
    return json
}
