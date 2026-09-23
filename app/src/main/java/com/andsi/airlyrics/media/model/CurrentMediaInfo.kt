package com.andsi.airlyrics.media.model

import java.util.concurrent.atomic.AtomicLong

/**
 * Current media state accepted by the app for lyrics operations.
 *
 * New observation fields are nullable. Missing values must not be treated as
 * reliable empty strings or zero. mediaId and queueItemId are session-scoped.
 */
data class CurrentMediaInfo(
    val sourcePackage: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isPlaying: Boolean,
    val positionMs: Long,
    val snapshotSequence: Long = UNSPECIFIED_SNAPSHOT_SEQUENCE,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationKnown: Boolean = true,
    val positionKnown: Boolean = true,
    val positionBaseMs: Long? = null,
    val positionAnchorElapsedRealtimeMs: Long? = null,
    val playbackSpeed: Float? = null,
    val playbackState: Int? = null,
    val sessionEpoch: Long = 0L,
    val queueItemId: Long? = null,
    val mediaId: String? = null,
    val queue: List<SessionQueueItem> = emptyList()
) {
    val isEmpty: Boolean
        get() = title.isBlank()

    companion object {
        const val UNSPECIFIED_SNAPSHOT_SEQUENCE = 0L

        val Empty = CurrentMediaInfo(
            sourcePackage = "",
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            isPlaying = false,
            positionMs = 0L,
            snapshotSequence = UNSPECIFIED_SNAPSHOT_SEQUENCE,
            durationKnown = false,
            positionKnown = false
        )
    }
}

internal object MediaSnapshotSequencer {
    private val nextSequence = AtomicLong(CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE)

    fun next(): Long = nextSequence.incrementAndGet()
}
