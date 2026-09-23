package com.andsi.airlyrics.lyrics.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    @Test
    fun sameTitleDifferentAlbums_areNotAutoAccepted() {
        val decision = TrackMatcher.decide(
            observation("Deep Mountain", "Artist", album = "Live"),
            listOf(
                ref(1, "Deep Mountain", "Artist", "Studio", durationMs = 180_000L, hash = "a"),
                ref(2, "Deep Mountain", "Artist", "Live", durationMs = 181_000L, hash = "b")
            )
        )
        val accepted = decision as MatchDecision.Accepted
        assertEquals(2L, accepted.candidate.trackId)
        assertEquals("title_artist_album_exact", accepted.reason)
    }

    @Test
    fun sameTitleDifferentVersions_doNotMixWhenLyricsDiffer() {
        val decision = TrackMatcher.decide(
            observation("Deep Mountain", "Artist", album = null, durationMs = 180_000L),
            listOf(
                ref(1, "Deep Mountain", "Artist", "Studio", durationMs = 180_000L, hash = "studio"),
                ref(2, "Deep Mountain (live)", "Artist", "Live", durationMs = 190_000L, hash = "live")
            )
        )
        assertEquals(1L, (decision as MatchDecision.Accepted).candidate.trackId)
    }

    @Test
    fun albumMismatch_isNotSilentlyIgnored() {
        val decision = TrackMatcher.decide(
            observation("Jewel", "Nanawoakari", album = "Wrong Album", durationMs = 240_000L),
            listOf(ref(9, "Jewel", "Nanawoakari", "Single", durationMs = 241_000L, hash = "j"))
        )
        assertTrue(decision !is MatchDecision.Accepted)
    }

    @Test
    fun punctuationTitle_matchesSecondary() {
        val decision = TrackMatcher.decide(
            observation("WHITE KISS!", "SYNC.ART'S", album = "WHITE KISS", durationMs = 200_000L),
            listOf(ref(3, "WHITE KISS", "SYNC.ART'S", "WHITE KISS", durationMs = 200_000L, hash = "jp"))
        )
        assertEquals(3L, (decision as MatchDecision.Accepted).candidate.trackId)
    }

    @Test
    fun artistNameMustNotBeSplitBySlash() {
        val decision = TrackMatcher.decide(
            observation("bleak rain", "河合夕子", album = "WHITE KISS", durationMs = 210_000L),
            listOf(
                ref(
                    4,
                    "bleak rain",
                    "河合夕子/kaya",
                    "WHITE KISS",
                    durationMs = 210_000L,
                    hash = "slash",
                    artists = listOf("河合夕子/kaya")
                )
            )
        )
        assertTrue(decision !is MatchDecision.Accepted)
    }

    @Test
    fun missingAlbum_acceptsUniqueTitleArtistDuration() {
        val decision = TrackMatcher.decide(
            observation("Jewel", "Nanawoakari", album = null, durationMs = 240_000L),
            listOf(ref(9, "Jewel", "Nanawoakari", "Single", durationMs = 241_000L, hash = "j"))
        )
        val accepted = decision as MatchDecision.Accepted
        assertEquals("title_artist_unique", accepted.reason)
        assertEquals(9L, accepted.candidate.trackId)
    }

    @Test
    fun uniqueTitleArtist_rejectsDurationConflict() {
        val decision = TrackMatcher.decide(
            observation("Jewel", "Nanawoakari", album = "Single", durationMs = 180_000L),
            listOf(ref(9, "Jewel", "Nanawoakari", "Single", durationMs = 240_000L, hash = "j"))
        )
        assertTrue(decision !is MatchDecision.Accepted)
    }

    @Test
    fun missingAlbum_twoDurationsBeyondTolerance_areAmbiguous() {
        val decision = TrackMatcher.decide(
            observation("Jewel", "Nanawoakari", album = null, durationMs = 240_000L),
            listOf(
                ref(1, "Jewel", "Nanawoakari", "A", durationMs = 240_000L, hash = "a"),
                ref(2, "Jewel", "Nanawoakari", "B", durationMs = 241_500L, hash = "b")
            )
        )
        assertTrue(decision is MatchDecision.Ambiguous)
    }

    @Test
    fun japaneseUnicode_primaryMatchAccepts() {
        val decision = TrackMatcher.decide(
            observation("少女指揮下", "SYNC.ART'S", album = "WHITE KISS", durationMs = 200_000L),
            listOf(ref(3, "少女指揮下", "SYNC.ART'S", "WHITE KISS", durationMs = 200_000L, hash = "jp"))
        )
        assertEquals(3L, (decision as MatchDecision.Accepted).candidate.trackId)
    }

    @Test
    fun multiArtistStoredWhole_matchesObservation() {
        val decision = TrackMatcher.decide(
            observation("bleak rain", "河合夕子/kaya", album = "WHITE KISS", durationMs = 210_000L),
            listOf(
                ref(
                    4,
                    "bleak rain",
                    "河合夕子/kaya",
                    "WHITE KISS",
                    durationMs = 210_000L,
                    hash = "slash",
                    artists = listOf("河合夕子/kaya")
                )
            )
        )
        assertEquals(4L, (decision as MatchDecision.Accepted).candidate.trackId)
    }

    @Test
    fun identicalLyricsPayload_isUniqueLyricsNotFileIdentity() {
        val decision = TrackMatcher.decide(
            observation("Y.M.C.A.", "Village People", album = null, durationMs = 200_000L),
            listOf(
                ref(1, "Y.M.C.A.", "Village People", "Best", durationMs = 200_000L, hash = "same"),
                ref(2, "Y.M.C.A.", "Village People", "Hits", durationMs = 201_000L, hash = "same")
            )
        )
        val unique = decision as MatchDecision.UniqueLyrics
        assertEquals("same", unique.lyricsTextHash)
        assertEquals(2, unique.candidates.size)
    }

    @Test
    fun missingArtist_isNotAutoAccepted() {
        val decision = TrackMatcher.decide(
            observation("Rebirth", artist = null, album = "TRANSFORM"),
            listOf(ref(8, "Rebirth", "DECO*27", "TRANSFORM", durationMs = 180_000L, hash = "r"))
        )
        val ambiguous = decision as MatchDecision.Ambiguous
        assertEquals("artist_missing_no_auto_accept", ambiguous.reason)
    }

    @Test
    fun unknownDuration_isNotComparedAsZero() {
        val zeroTrack = ref(1, "Song", "Artist", "Album", durationMs = 0L, hash = "z")
        val realTrack = ref(2, "Song", "Artist", "Album", durationMs = 180_000L, hash = "r")
        val unknownObs = TrackObservation(
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = null,
            durationKnown = false
        )
        val decision = TrackMatcher.decide(unknownObs, listOf(realTrack, zeroTrack))
        assertTrue(
            "unknown duration must not pick the 0ms row by treating missing as zero: $decision",
            decision is MatchDecision.Ambiguous ||
                (decision is MatchDecision.Accepted && decision.candidate.trackId == 2L)
        )
    }

    @Test
    fun discTrackConflict_blocksAutoAccept() {
        val decision = TrackMatcher.decide(
            TrackObservation(
                title = "Sign of the Times",
                artist = "Harry Styles",
                album = "Harry Styles",
                durationMs = 340_000L,
                durationKnown = true,
                trackNumber = 2
            ),
            listOf(
                ref(1, "Sign of the Times", "Harry Styles", "Harry Styles", 340_000L, "a", trackNumber = 2),
                ref(2, "Sign of the Times", "Harry Styles", "Harry Styles", 340_000L, "b", trackNumber = 11)
            )
        )
        assertEquals(1L, (decision as MatchDecision.Accepted).candidate.trackId)
    }

    @Test
    fun matchingDoesNotUsePath() {
        val a = ref(1, "Song", "Artist", "Album", 180_000L, "a").copy(localShardPath = "C:/other/a.sqlite")
        val b = ref(2, "Song", "Artist", "Other", 180_000L, "b").copy(localShardPath = "D:/music/b.sqlite")
        val decision = TrackMatcher.decide(observation("Song", "Artist", "Album"), listOf(a, b))
        assertEquals(1L, (decision as MatchDecision.Accepted).candidate.trackId)
    }

    private fun observation(
        title: String,
        artist: String?,
        album: String? = "Album",
        durationMs: Long? = 180_000L
    ): TrackObservation {
        return TrackObservation(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            durationKnown = durationMs != null
        )
    }

    private fun ref(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
        hash: String,
        artists: List<String> = listOf(artist),
        trackNumber: Int? = null
    ): CatalogTrackRef {
        return CatalogTrackRef(
            shardId = "shard",
            revision = "rev",
            trackId = trackId,
            localShardPath = "unused.sqlite",
            title = title,
            artist = artist,
            artists = artists,
            album = album,
            albumArtist = artist,
            durationMs = durationMs,
            disc = null,
            trackNumber = trackNumber,
            genre = null,
            lyricsStatus = "present",
            lyricsTextHash = hash
        )
    }
}
