package com.andsi.airlyrics.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {
    @Test
    fun unknownPosition_isNotPretendedToBeZero() {
        val reading = PlaybackClockSnapshot.Unknown.positionAt(10_000L)
        assertFalse(reading.known)
        assertNull(reading.positionMs)
    }

    @Test
    fun playing_extrapolatesWithSpeedFromElapsedRealtimeAnchor() {
        val snapshot = PlaybackClockSnapshot.playing(
            positionBaseMs = 10_000L,
            anchorElapsedRealtimeMs = 2_000L,
            playbackSpeed = 1.5f,
            durationMs = 60_000L
        )

        val reading = snapshot.positionAt(6_000L)
        assertTrue(reading.known)
        assertEquals(16_000L, reading.positionMs)
    }

    @Test
    fun paused_freezesAtBasePosition() {
        val snapshot = PlaybackClockSnapshot.frozen(positionBaseMs = 62_000L, durationMs = 180_000L)
        assertEquals(62_000L, snapshot.positionAt(99_000L).positionMs)
    }

    @Test
    fun seek_usesNewAnchorImmediately() {
        val before = PlaybackClockSnapshot.playing(
            positionBaseMs = 20_000L,
            anchorElapsedRealtimeMs = 5_000L
        )
        assertEquals(21_000L, before.positionAt(6_000L).positionMs)

        val afterSeek = PlaybackClockSnapshot.playing(
            positionBaseMs = 8_000L,
            anchorElapsedRealtimeMs = 6_000L
        )
        assertEquals(8_000L, afterSeek.positionAt(6_000L).positionMs)
        assertEquals(8_400L, afterSeek.positionAt(6_400L).positionMs)
    }

    @Test
    fun knownDuration_capsUpperBound() {
        val snapshot = PlaybackClockSnapshot.playing(
            positionBaseMs = 9_500L,
            anchorElapsedRealtimeMs = 1_000L,
            durationMs = 10_000L
        )
        assertEquals(10_000L, snapshot.positionAt(3_000L).positionMs)
    }
}
