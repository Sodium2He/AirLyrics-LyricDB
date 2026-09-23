package com.andsi.airlyrics.media

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.time.PlaybackClockSnapshot
import com.andsi.airlyrics.media.model.CurrentMediaInfo

fun CurrentMediaInfo.toSongIdentity(): SongIdentity {
    return SongIdentity(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )
}

fun CurrentMediaInfo.toPlaybackClockSnapshot(): PlaybackClockSnapshot {
    return PlaybackClockSnapshot(
        positionBaseMs = positionBaseMs ?: positionMs.takeIf { positionKnown },
        positionKnown = positionKnown,
        anchorElapsedRealtimeMs = positionAnchorElapsedRealtimeMs,
        playbackSpeed = playbackSpeed,
        isPlaying = isPlaying,
        durationMs = durationMs.takeIf { durationKnown && it > 0L },
        durationKnown = durationKnown && durationMs > 0L
    )
}
