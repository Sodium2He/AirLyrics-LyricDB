package com.andsi.airlyrics.media

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.model.SessionQueueItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the app-local broadcast protocol for media snapshots.
 *
 * Keep media payload field names here so the notification listener, floating service,
 * and main screen cannot drift when CurrentMediaInfo changes.
 */
object CurrentMediaBroadcast {
    private const val ACTION_MEDIA_UPDATE = "com.andsi.airlyrics.MEDIA_UPDATE"
    private const val ACTION_MEDIA_SOURCE_LOST = "com.andsi.airlyrics.MEDIA_SOURCE_LOST"

    private const val EXTRA_TITLE = "title"
    private const val EXTRA_ARTIST = "artist"
    private const val EXTRA_ALBUM = "album"
    private const val EXTRA_DURATION_MS = "duration"
    private const val EXTRA_POSITION_MS = "position"
    private const val EXTRA_IS_PLAYING = "isPlaying"
    private const val EXTRA_SNAPSHOT_SEQUENCE = "mediaSnapshotSequence"
    private const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
    private const val EXTRA_ALBUM_ARTIST = "albumArtist"
    private const val EXTRA_GENRE = "genre"
    private const val EXTRA_TRACK_NUMBER = "trackNumber"
    private const val EXTRA_DISC_NUMBER = "discNumber"
    private const val EXTRA_DURATION_KNOWN = "durationKnown"
    private const val EXTRA_POSITION_KNOWN = "positionKnown"
    private const val EXTRA_POSITION_BASE_MS = "positionBaseMs"
    private const val EXTRA_POSITION_ANCHOR_ELAPSED = "positionAnchorElapsedRealtimeMs"
    private const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
    private const val EXTRA_PLAYBACK_STATE = "playbackState"
    private const val EXTRA_SESSION_EPOCH = "sessionEpoch"
    private const val EXTRA_QUEUE_ITEM_ID = "queueItemId"
    private const val EXTRA_MEDIA_ID = "mediaId"
    private const val EXTRA_QUEUE_JSON = "queueJson"

    fun mediaStatusFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(ACTION_MEDIA_UPDATE)
            addAction(ACTION_MEDIA_SOURCE_LOST)
        }
    }

    fun isMediaStatusIntent(intent: Intent): Boolean {
        return intent.action == ACTION_MEDIA_UPDATE ||
            intent.action == ACTION_MEDIA_SOURCE_LOST
    }

    fun mediaUpdateIntent(context: Context, media: CurrentMediaInfo): Intent {
        return Intent(ACTION_MEDIA_UPDATE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TITLE, media.title)
            putExtra(EXTRA_ARTIST, media.artist)
            putExtra(EXTRA_ALBUM, media.album)
            putExtra(EXTRA_DURATION_MS, media.durationMs)
            putExtra(EXTRA_POSITION_MS, media.positionMs)
            putExtra(EXTRA_IS_PLAYING, media.isPlaying)
            putExtra(EXTRA_SNAPSHOT_SEQUENCE, media.snapshotSequence)
            putExtra(EXTRA_SOURCE_PACKAGE, media.sourcePackage)
            putExtra(EXTRA_DURATION_KNOWN, media.durationKnown)
            putExtra(EXTRA_POSITION_KNOWN, media.positionKnown)
            putExtra(EXTRA_SESSION_EPOCH, media.sessionEpoch)
            media.albumArtist?.let { putExtra(EXTRA_ALBUM_ARTIST, it) }
            media.genre?.let { putExtra(EXTRA_GENRE, it) }
            media.trackNumber?.let { putExtra(EXTRA_TRACK_NUMBER, it) }
            media.discNumber?.let { putExtra(EXTRA_DISC_NUMBER, it) }
            media.positionBaseMs?.let { putExtra(EXTRA_POSITION_BASE_MS, it) }
            media.positionAnchorElapsedRealtimeMs?.let { putExtra(EXTRA_POSITION_ANCHOR_ELAPSED, it) }
            media.playbackSpeed?.let { putExtra(EXTRA_PLAYBACK_SPEED, it) }
            media.playbackState?.let { putExtra(EXTRA_PLAYBACK_STATE, it) }
            media.queueItemId?.let { putExtra(EXTRA_QUEUE_ITEM_ID, it) }
            media.mediaId?.let { putExtra(EXTRA_MEDIA_ID, it) }
            if (media.queue.isNotEmpty()) {
                putExtra(EXTRA_QUEUE_JSON, encodeQueue(media.queue))
            }
        }
    }

    fun mediaSourceLostIntent(context: Context, sourcePackage: String): Intent? {
        if (sourcePackage.isBlank()) return null

        return Intent(ACTION_MEDIA_SOURCE_LOST).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
    }

    fun readMediaUpdate(intent: Intent?): CurrentMediaInfo? {
        if (intent?.action != ACTION_MEDIA_UPDATE) return null

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isBlank()) return null

        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        if (sourcePackage.isBlank()) return null

        return CurrentMediaInfo(
            sourcePackage = sourcePackage,
            title = title,
            artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
            album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
            durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L),
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false),
            positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L),
            snapshotSequence = intent.getLongExtra(
                EXTRA_SNAPSHOT_SEQUENCE,
                CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE
            ),
            albumArtist = intent.getStringExtra(EXTRA_ALBUM_ARTIST),
            genre = intent.getStringExtra(EXTRA_GENRE),
            trackNumber = intent.optionalInt(EXTRA_TRACK_NUMBER),
            discNumber = intent.optionalInt(EXTRA_DISC_NUMBER),
            durationKnown = intent.getBooleanExtra(EXTRA_DURATION_KNOWN, true),
            positionKnown = intent.getBooleanExtra(EXTRA_POSITION_KNOWN, true),
            positionBaseMs = intent.optionalLong(EXTRA_POSITION_BASE_MS),
            positionAnchorElapsedRealtimeMs = intent.optionalLong(EXTRA_POSITION_ANCHOR_ELAPSED),
            playbackSpeed = if (intent.hasExtra(EXTRA_PLAYBACK_SPEED)) {
                intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, 1f)
            } else {
                null
            },
            playbackState = intent.optionalInt(EXTRA_PLAYBACK_STATE),
            sessionEpoch = intent.getLongExtra(EXTRA_SESSION_EPOCH, 0L),
            queueItemId = intent.optionalLong(EXTRA_QUEUE_ITEM_ID),
            mediaId = intent.getStringExtra(EXTRA_MEDIA_ID),
            queue = decodeQueue(intent.getStringExtra(EXTRA_QUEUE_JSON))
        )
    }

    private fun Intent.optionalInt(key: String): Int? {
        return if (hasExtra(key)) getIntExtra(key, 0) else null
    }

    private fun Intent.optionalLong(key: String): Long? {
        return if (hasExtra(key)) getLongExtra(key, 0L) else null
    }

    private fun encodeQueue(queue: List<SessionQueueItem>): String {
        val items = JSONArray()
        queue.forEach { item ->
            items.put(
                JSONObject().apply {
                    put("queueId", item.queueId)
                    putOpt("title", item.title)
                    putOpt("artist", item.artist)
                    putOpt("album", item.album)
                    putOpt("albumArtist", item.albumArtist)
                    if (item.durationMs != null) put("durationMs", item.durationMs) else put("durationMs", JSONObject.NULL)
                    put("durationKnown", item.durationKnown)
                    if (item.trackNumber != null) put("trackNumber", item.trackNumber) else put("trackNumber", JSONObject.NULL)
                    if (item.discNumber != null) put("discNumber", item.discNumber) else put("discNumber", JSONObject.NULL)
                    putOpt("mediaId", item.mediaId)
                }
            )
        }
        return items.toString()
    }

    private fun decodeQueue(raw: String?): List<SessionQueueItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        SessionQueueItem(
                            queueId = item.getLong("queueId"),
                            title = item.optNullableString("title"),
                            artist = item.optNullableString("artist"),
                            album = item.optNullableString("album"),
                            albumArtist = item.optNullableString("albumArtist"),
                            durationMs = item.optNullableLong("durationMs"),
                            durationKnown = item.optBoolean("durationKnown", false),
                            trackNumber = item.optNullableInt("trackNumber"),
                            discNumber = item.optNullableInt("discNumber"),
                            mediaId = item.optNullableString("mediaId")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return getString(key)
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return getLong(key)
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return getInt(key)
    }

    fun readMediaSourceLost(intent: Intent?): String? {
        if (intent?.action != ACTION_MEDIA_SOURCE_LOST) return null
        return intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() }
    }
}
