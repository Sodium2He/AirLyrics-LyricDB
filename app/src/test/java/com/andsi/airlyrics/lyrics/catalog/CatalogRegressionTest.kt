package com.andsi.airlyrics.lyrics.catalog

import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import org.junit.Assert.*
import org.junit.Test

class CatalogRegressionTest {
    private fun ref(id: Long, artist: String = "Artist", hash: String? = "same") = CatalogTrackRef(
        "s", "r", id, "unused", "Song", artist, listOf(artist), "Album", artist,
        180000L, 1, 1, null, "present", hash
    )
    private val observation = TrackObservation("Song", "Artist", "Album", durationMs = 180000L, durationKnown = true)

    @Test fun equalPayloadCannotAcceptConflictingArtists() {
        val result = TrackMatcher.decide(observation, listOf(ref(1, "Other"), ref(2, "Other")))
        assertFalse(result is MatchDecision.UniqueLyrics || result is MatchDecision.Accepted)
    }

    @Test fun unknownPayloadPreventsUniqueLyrics() {
        assertTrue(TrackMatcher.decide(observation, listOf(ref(1), ref(2), ref(3, hash = null))) is MatchDecision.Ambiguous)
    }

    @Test fun numberedTitleIsNotStripped() {
        assertFalse(TrackMatcher.decide(observation.copy(title = "01 Song"), listOf(ref(1))) is MatchDecision.Accepted)
    }

    @Test fun primaryTitleWinsOverPunctuationCandidate() {
        val result = TrackMatcher.decide(observation, listOf(ref(1), ref(2).copy(title = "Song!")))
        assertEquals(1L, (result as MatchDecision.Accepted).candidate.trackId)
    }

    @Test fun weakTitleNeedsAlbumAndDurationSupport() {
        val result = TrackMatcher.decide(observation.copy(title = "Song!", album = null), listOf(ref(1)))
        assertFalse(result is MatchDecision.Accepted)
    }

    @Test fun wordTimingUsesOriginalAirLyricsBehavior() {
        val line = WordByWordLrcParser.parse("[00:01.00]<00:01.00>hello<00:03.125>").single()
        assertEquals(1400L, line.segments.last().endMs)
        assertEquals(1400L, line.endMs)
    }

    @Test fun catalogUsesOriginalBilingualImportPayload() {
        val samples = listOf(
            "[00:01.00]<00:01.00>歌<00:02.00> / 译文",
            "[00:01.00]<00:01.00>歌<00:02.00>\n[00:01.00]译文"
        )
        samples.forEach { raw ->
            val original = WordByWordLrcParser.parseImport(raw)
            val adapted = ShardLyricsAdapter.adapt(raw, "elrc")
            assertEquals(original.plainLrc, adapted.plainLrc)
            assertEquals(original.wordByWordLines.map { it.text }, adapted.wordByWordLines.map { it.text })
            assertNull(adapted.translatedLrc)
        }
    }

    @Test fun prefetchKeyIncludesDiscTrackAndAlbumArtist() {
        val cache = LyricsPrefetchCache()
        assertNotEquals(cache.key(observation.copy(trackNumber = 1)), cache.key(observation.copy(trackNumber = 2)))
        assertNotEquals(cache.key(observation.copy(albumArtist = "A")), cache.key(observation.copy(albumArtist = "B")))
        assertNotEquals(cache.key(observation.copy(discNumber = 1)), cache.key(observation.copy(discNumber = 2)))
    }
}
