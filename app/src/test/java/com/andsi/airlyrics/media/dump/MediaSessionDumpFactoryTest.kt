package com.andsi.airlyrics.media.dump

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaSessionDumpFactoryTest {
    @Test
    fun dump_keepsMissingDurationAndArtistDistinctFromAlbumArtist() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "Album Artist")
            .putString(MediaMetadata.METADATA_KEY_GENRE, "J-POP")
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, 3L)
            .putLong(MediaMetadata.METADATA_KEY_DISC_NUMBER, 2L)
            .build()
        val state = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 12_000L, 1f, 5_000L)
            .setActiveQueueItemId(9L)
            .build()
        val queue = listOf(
            MediaSession.QueueItem(
                MediaDescription.Builder()
                    .setMediaId("session-only-id")
                    .setTitle("Next Song")
                    .setSubtitle("Next Artist")
                    .build(),
                9L
            )
        )

        val dump = MediaSessionDumpFactory.fromParts(
            packageName = "app.symfonium",
            metadata = metadata,
            playbackState = state,
            queue = queue,
            sessionEpoch = 4L,
            elapsedRealtimeMs = 5_000L
        )

        assertEquals("app.symfonium", dump.packageName)
        assertEquals(4L, dump.sessionEpoch)
        assertFalse(dump.metadata.containsKey(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Album Artist", dump.metadata.getValue(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).stringValue)
        assertFalse(dump.metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION))
        assertFalse(dump.playback.positionUnknown)
        assertEquals(12_000L, dump.playback.positionMs)
        assertEquals(9L, dump.playback.activeQueueItemId)
        assertEquals(1, dump.queue.size)
        assertEquals("session-only-id", dump.queue.single().mediaId)
        assertEquals("Next Song", dump.queue.single().title)
        assertEquals(12_000L, dump.clock.positionBaseMs)
        assertEquals(5_000L, dump.clock.elapsedRealtimeAnchorMs)
        assertTrue(dump.toJson().has("queue"))
    }

    @Test
    fun dump_marksUnknownPositionInsteadOfZero() {
        val state = PlaybackState.Builder()
            .setState(
                PlaybackState.STATE_PLAYING,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1f,
                1_000L
            )
            .build()

        val dump = MediaSessionDumpFactory.fromParts(
            packageName = "app.symfonium",
            metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
                .build(),
            playbackState = state,
            queue = emptyList(),
            sessionEpoch = 1L,
            elapsedRealtimeMs = 2_000L
        )

        assertTrue(dump.playback.positionUnknown)
        assertNull(dump.playback.positionMs)
        assertNull(dump.clock.estimatedPositionMs)
    }
}
