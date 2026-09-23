package com.andsi.airlyrics.media.model

data class SessionQueueItem(
    val queueId: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val durationKnown: Boolean = false,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val mediaId: String? = null
)
