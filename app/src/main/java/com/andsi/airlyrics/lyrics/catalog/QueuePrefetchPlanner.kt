package com.andsi.airlyrics.lyrics.catalog

data class PrefetchQueueItem(
    val queueId: Long,
    val title: String?,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val durationKnown: Boolean = false,
    val trackNumber: Int? = null,
    val discNumber: Int? = null
) {
    fun toObservation(token: String = ""): TrackObservation {
        return TrackObservation(
            title = title?.takeIf { it.isNotBlank() },
            artist = artist?.takeIf { it.isNotBlank() },
            album = album?.takeIf { it.isNotBlank() },
            albumArtist = albumArtist?.takeIf { it.isNotBlank() },
            durationMs = durationMs?.takeIf { durationKnown && it > 0L },
            durationKnown = durationKnown && (durationMs ?: 0L) > 0L,
            trackNumber = trackNumber,
            discNumber = discNumber,
            observationToken = token
        )
    }

    fun isIdentifiable(): Boolean = !title.isNullOrBlank()
}

object QueuePrefetchPlanner {
    const val LIMIT = 2

    fun nextFromQueue(
        items: List<PrefetchQueueItem>,
        activeQueueItemId: Long?
    ): List<PrefetchQueueItem> {
        if (activeQueueItemId == null) return emptyList()
        val index = items.indexOfFirst { it.queueId == activeQueueItemId }
        if (index < 0) return emptyList()
        return items.drop(index + 1).filter { it.isIdentifiable() }.take(LIMIT)
    }

    fun fingerprint(
        sessionEpoch: Long,
        items: List<PrefetchQueueItem>,
        activeQueueItemId: Long?
    ): String {
        return buildString {
            append(sessionEpoch)
            append('|')
            append(activeQueueItemId ?: -1L)
            items.forEach { item ->
                append('|')
                append(item.queueId)
                append(':')
                append(item.title.orEmpty())
            }
        }
    }
}
