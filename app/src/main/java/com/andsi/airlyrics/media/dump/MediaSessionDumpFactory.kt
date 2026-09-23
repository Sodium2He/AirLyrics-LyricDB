package com.andsi.airlyrics.media.dump

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import com.andsi.airlyrics.media.CurrentMediaReader

object MediaSessionDumpFactory {
    fun fromController(
        controller: MediaController,
        sessionEpoch: Long,
        elapsedRealtimeMs: Long
    ): MediaSessionDump {
        return fromParts(
            packageName = controller.packageName.orEmpty(),
            metadata = controller.metadata,
            playbackState = controller.playbackState,
            queue = controller.queue,
            sessionEpoch = sessionEpoch,
            elapsedRealtimeMs = elapsedRealtimeMs
        )
    }

    fun fromParts(
        packageName: String,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        queue: List<MediaSession.QueueItem>?,
        sessionEpoch: Long,
        elapsedRealtimeMs: Long
    ): MediaSessionDump {
        val positionUnknown = playbackState == null ||
            playbackState.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val rawPosition = playbackState?.position
            ?.takeUnless { it == PlaybackState.PLAYBACK_POSITION_UNKNOWN }
        val playing = playbackState?.state == PlaybackState.STATE_PLAYING
        val estimated = if (positionUnknown) {
            null
        } else {
            CurrentMediaReader.estimatedPositionMs(playbackState, elapsedRealtimeMs)
        }

        return MediaSessionDump(
            capturedElapsedRealtimeMs = elapsedRealtimeMs,
            packageName = packageName,
            sessionEpoch = sessionEpoch,
            metadata = dumpMetadata(metadata),
            playback = PlaybackDump(
                state = playbackState?.state,
                positionMs = rawPosition,
                positionUnknown = positionUnknown,
                speed = playbackState?.playbackSpeed,
                lastPositionUpdateTime = playbackState?.lastPositionUpdateTime?.takeIf { it > 0L },
                activeQueueItemId = playbackState?.activeQueueItemId
                    ?.takeUnless { it == MediaSession.QueueItem.UNKNOWN_ID.toLong() },
                extrasKeys = extrasKeys(playbackState?.extras)
            ),
            queue = queue.orEmpty().map { item ->
                val description = item.description
                QueueItemDump(
                    queueId = item.queueId,
                    mediaId = description.mediaId,
                    title = description.title?.toString(),
                    subtitle = description.subtitle?.toString(),
                    description = description.description?.toString(),
                    extrasKeys = extrasKeys(description.extras)
                )
            },
            clock = ClockDump(
                positionBaseMs = rawPosition,
                elapsedRealtimeAnchorMs = playbackState?.lastPositionUpdateTime?.takeIf { it > 0L },
                playbackSpeed = playbackState?.playbackSpeed,
                estimatedPositionMs = estimated,
                playing = playing
            )
        )
    }

    private fun dumpMetadata(metadata: MediaMetadata?): Map<String, MetadataValue> {
        if (metadata == null) return emptyMap()
        return metadata.keySet().associateWith { key ->
            when {
                BITMAP_KEYS.contains(key) -> MetadataValue(
                    present = metadata.containsKey(key),
                    kind = "bitmap"
                )
                STRING_KEYS.contains(key) -> {
                    val value = metadata.getString(key)
                    MetadataValue(
                        present = metadata.containsKey(key),
                        kind = "string",
                        stringValue = value
                    )
                }
                LONG_KEYS.contains(key) -> MetadataValue(
                    present = metadata.containsKey(key),
                    kind = "long",
                    longValue = if (metadata.containsKey(key)) metadata.getLong(key) else null
                )
                else -> {
                    val stringValue = metadata.getString(key)
                    if (stringValue != null) {
                        MetadataValue(present = true, kind = "string", stringValue = stringValue)
                    } else if (metadata.containsKey(key)) {
                        MetadataValue(
                            present = true,
                            kind = "long",
                            longValue = metadata.getLong(key)
                        )
                    } else {
                        MetadataValue(present = false, kind = "absent")
                    }
                }
            }
        }
    }

    private fun extrasKeys(extras: Bundle?): List<String> {
        if (extras == null) return emptyList()
        return extras.keySet().sorted()
    }

    private val STRING_KEYS = setOf(
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_ALBUM,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        MediaMetadata.METADATA_KEY_GENRE,
        MediaMetadata.METADATA_KEY_WRITER,
        MediaMetadata.METADATA_KEY_COMPOSER,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        MediaMetadata.METADATA_KEY_MEDIA_ID,
        MediaMetadata.METADATA_KEY_DATE,
        MediaMetadata.METADATA_KEY_COMPILATION
    )

    private val LONG_KEYS = setOf(
        MediaMetadata.METADATA_KEY_DURATION,
        MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_DISC_NUMBER,
        MediaMetadata.METADATA_KEY_YEAR,
        MediaMetadata.METADATA_KEY_NUM_TRACKS
    )

    private val BITMAP_KEYS = setOf(
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON
    )
}
