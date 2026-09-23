package com.andsi.airlyrics.lyrics.catalog

import com.andsi.airlyrics.core.text.MetadataNormalizer
import kotlin.math.abs

object TrackMatcher {
    internal fun serializedArtistParts(value: String): List<String> = value.split(Regex("[,;/、；，]"))
        .map(MetadataNormalizer::primary).filter { it.isNotBlank() }
    const val DURATION_TOLERANCE_MS = 2_000L
    const val RECALL_LIMIT = 64

    fun decide(
        observation: TrackObservation,
        candidates: List<CatalogTrackRef>
    ): MatchDecision {
        if (candidates.isEmpty()) {
            return MatchDecision.Unmatched("no_candidates")
        }
        if (candidates.size > RECALL_LIMIT) {
            return MatchDecision.UnresolvedTruncation(candidates.size, RECALL_LIMIT)
        }

        val titled = candidates.filter { candidate ->
            (titleExact(observation, candidate) || candidate.matchedViaAlias) &&
                !discTrackConflict(observation, candidate) &&
                !durationConflict(observation, candidate)
        }

        val primary = titled.filter { candidate ->
            titleExact(observation, candidate) && !candidate.matchedViaAlias
        }
        val strong = primary.ifEmpty {
            candidates.filter { candidate ->
                !discTrackConflict(observation, candidate) && !durationConflict(observation, candidate) &&
                    !observation.title.isNullOrBlank() && !candidate.title.isNullOrBlank() &&
                    (candidate.matchedViaAlias || MetadataNormalizer.secondary(observation.title) == MetadataNormalizer.secondary(candidate.title)) &&
                    albumExact(observation, candidate) &&
                    observation.durationKnown && observation.durationMs != null && candidate.durationMs != null
            }
        }

        val rule1 = strong.filter { candidate ->
            artistExact(observation, candidate) &&
                albumExact(observation, candidate) &&
                durationCompatible(observation, candidate)
        }
        if (rule1.size == 1 && observation.album != null && observation.artist != null) {
            return MatchDecision.Accepted(rule1.single(), "title_artist_album_exact")
        }

        val uniqueArtist = strong.filter { candidate ->
            artistExact(observation, candidate) &&
                (observation.album.isNullOrBlank() || candidate.album.isNullOrBlank() || albumExact(observation, candidate))
        }
        if (uniqueArtist.size == 1 && observation.artist != null) {
            return MatchDecision.Accepted(uniqueArtist.single(), "title_artist_unique")
        }

        if (uniqueArtist.size > 1 && observation.artist != null) {
            uniquePick(uniqueArtist) { albumExact(observation, it) }?.let { hit ->
                return MatchDecision.Accepted(hit, "title_artist_album_unique")
            }
            uniquePick(uniqueArtist) { durationCompatible(observation, it) }?.let { hit ->
                return MatchDecision.Accepted(hit, "title_artist_duration_unique")
            }
        }

        val hashed = uniqueArtist.mapNotNull { candidate ->
            candidate.lyricsTextHash?.takeIf { hash -> hash.isNotBlank() }?.let { hash ->
                candidate to hash
            }
        }
        if (hashed.size >= 2 && hashed.size == uniqueArtist.size && uniqueArtist.all { it.lyricsStatus == "present" }) {
            val distinct = hashed.map { it.second }.distinct()
            if (distinct.size == 1) {
                return MatchDecision.UniqueLyrics(
                    candidates = hashed.map { it.first },
                    lyricsTextHash = distinct.single(),
                    reason = "identical_lyrics_payload"
                )
            }
            return MatchDecision.Ambiguous(
                candidates = candidates,
                reason = "different_lyrics_payload"
            )
        }

        return MatchDecision.Ambiguous(
            candidates = candidates,
            reason = if (observation.artist == null) {
                "artist_missing_no_auto_accept"
            } else {
                "insufficient_evidence"
            }
        )
    }

    fun durationCompatible(observation: TrackObservation, candidate: CatalogTrackRef): Boolean {
        if (!observation.durationKnown || observation.durationMs == null || candidate.durationMs == null) {
            return true
        }
        return abs(observation.durationMs - candidate.durationMs) <= DURATION_TOLERANCE_MS
    }

    fun durationConflict(observation: TrackObservation, candidate: CatalogTrackRef): Boolean {
        if (!observation.durationKnown || observation.durationMs == null || candidate.durationMs == null) {
            return false
        }
        return abs(observation.durationMs - candidate.durationMs) > DURATION_TOLERANCE_MS
    }

    private fun titleExact(observation: TrackObservation, candidate: CatalogTrackRef): Boolean {
        return namesEqual(observation.title, candidate.title)
    }

    private fun artistExact(observation: TrackObservation, candidate: CatalogTrackRef): Boolean {
        val observed = observation.artist ?: return false
        val names = buildList {
            addAll(candidate.artists)
            candidate.artist?.let(::add)
        }
        if (names.any { name -> namesEqual(observed, name) }) return true
        // Serialized lists vary between tag readers and MediaSession. Compare the whole
        // list, never an intersection; album and measured duration support this fallback.
        if (!albumExact(observation, candidate) || !observation.durationKnown ||
            observation.durationMs == null || candidate.durationMs == null) return false
        val stored = candidate.artists.ifEmpty { listOfNotNull(candidate.artist) }
        if (stored.size > 1) {
            // Native values are atomic: an artist's own comma or slash is not a boundary.
            return listOf(", ", "; ", " / ", "/", ",", ";", "、", "；", "，").any { separator ->
                namesEqual(observed, stored.joinToString(separator))
            }
        }
        val observedNames = serializedArtistParts(observed)
        val storedNames = stored.singleOrNull()?.let(::serializedArtistParts).orEmpty()
        return observedNames.size > 1 && observedNames == storedNames
    }

    private fun albumExact(observation: TrackObservation, candidate: CatalogTrackRef): Boolean {
        return namesEqual(observation.album, candidate.album)
    }

    private fun discTrackConflict(observation: TrackObservation, candidate: CatalogTrackRef): Boolean {
        if (observation.discNumber != null && candidate.disc != null &&
            observation.discNumber != candidate.disc
        ) {
            return true
        }
        if (observation.trackNumber != null && candidate.trackNumber != null &&
            observation.trackNumber != candidate.trackNumber
        ) {
            return true
        }
        return false
    }

    private fun uniquePick(
        candidates: List<CatalogTrackRef>,
        matches: (CatalogTrackRef) -> Boolean
    ): CatalogTrackRef? {
        val hit = candidates.filter(matches)
        return hit.singleOrNull()
    }

    private fun namesEqual(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        if (MetadataNormalizer.primary(left) == MetadataNormalizer.primary(right)) return true
        return false
    }
}
