package com.andsi.airlyrics.lyrics.catalog

import com.andsi.airlyrics.core.text.MetadataNormalizer
import com.andsi.airlyrics.lyrics.LyricsProviderResult

class LyricsPrefetchCache {
    private val lock = Any()
    private val items = LinkedHashMap<String, LyricsProviderResult>()

    fun get(observation: TrackObservation): LyricsProviderResult? {
        val key = key(observation) ?: return null
        synchronized(lock) {
            return items[key]
        }
    }

    fun put(observation: TrackObservation, result: LyricsProviderResult) {
        val key = key(observation) ?: return
        synchronized(lock) {
            items.remove(key)
            items[key] = result
            while (items.size > 8) {
                val oldest = items.keys.first()
                items.remove(oldest)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
        }
    }

    fun key(observation: TrackObservation): String? {
        val title = observation.title?.takeIf { it.isNotBlank() } ?: return null
        return listOf(
            MetadataNormalizer.primary(title),
            observation.artist?.let(MetadataNormalizer::primary).orEmpty(),
            observation.album?.let(MetadataNormalizer::primary).orEmpty(),
            (observation.durationMs?.takeIf { observation.durationKnown } ?: -1L).toString(),
            observation.albumArtist?.let(MetadataNormalizer::primary).orEmpty(),
            observation.discNumber?.toString().orEmpty(),
            observation.trackNumber?.toString().orEmpty()
        ).joinToString("") { "${it.length}:$it" }
    }
}
