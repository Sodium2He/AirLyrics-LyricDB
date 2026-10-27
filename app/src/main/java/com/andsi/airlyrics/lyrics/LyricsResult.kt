package com.andsi.airlyrics.lyrics

/**
 * Timed lyrics and matching metadata used by catalog lookup, prefetch, and display.
 */
data class LyricsProviderResult(
    val plainProviderId: String,
    val plainProviderName: String,
    val plainLrc: String,
    val translatedLrc: String? = null,
    val wordByWordLines: List<WordByWordLine> = emptyList(),
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val matchedAlbum: String = "",
    val matchedDurationMs: Long = 0L,
    val catalogGeneration: Long? = null,
    val matchKind: String? = null,
    val translationWordByWordLines: List<WordByWordLine> = emptyList(),
    val catalogRawText: String? = null,
    val catalogFormat: String? = null
)
