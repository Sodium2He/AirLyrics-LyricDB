package com.andsi.airlyrics.core.time

/**
 * Shared playback clock. Extrapolates from a MediaSession position anchor using
 * elapsedRealtime: playing `p(t)=p0+v*(t-t0)`; paused/buffering/stopped freeze.
 * Missing positions stay unknown and are not treated as 0.
 */
data class PlaybackClockSnapshot(
    val positionBaseMs: Long? = null,
    val positionKnown: Boolean = false,
    val anchorElapsedRealtimeMs: Long? = null,
    val playbackSpeed: Float? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long? = null,
    val durationKnown: Boolean = false
) {
    fun positionAt(elapsedRealtimeMs: Long): ClockReading {
        if (!positionKnown || positionBaseMs == null) {
            return ClockReading(positionMs = null, known = false)
        }

        val frozen = ClockReading(positionMs = positionBaseMs.coerceAtLeast(0L), known = true)
        if (!isPlaying) {
            return frozen
        }

        val t0 = anchorElapsedRealtimeMs ?: return frozen
        if (t0 <= 0L) {
            return frozen
        }

        val speed = playbackSpeed?.takeIf { it > 0f } ?: 1f
        val elapsedMs = (elapsedRealtimeMs - t0).coerceAtLeast(0L)
        var position = positionBaseMs + (elapsedMs * speed).toLong()
        if (durationKnown) {
            durationMs?.takeIf { it > 0L }?.let { duration ->
                position = position.coerceAtMost(duration)
            }
        }
        return ClockReading(positionMs = position.coerceAtLeast(0L), known = true)
    }

    companion object {
        val Unknown = PlaybackClockSnapshot()

        fun playing(
            positionBaseMs: Long,
            anchorElapsedRealtimeMs: Long,
            playbackSpeed: Float = 1f,
            durationMs: Long? = null,
            durationKnown: Boolean = durationMs != null
        ): PlaybackClockSnapshot {
            return PlaybackClockSnapshot(
                positionBaseMs = positionBaseMs,
                positionKnown = true,
                anchorElapsedRealtimeMs = anchorElapsedRealtimeMs,
                playbackSpeed = playbackSpeed,
                isPlaying = true,
                durationMs = durationMs,
                durationKnown = durationKnown
            )
        }

        fun frozen(
            positionBaseMs: Long,
            durationMs: Long? = null,
            durationKnown: Boolean = durationMs != null
        ): PlaybackClockSnapshot {
            return PlaybackClockSnapshot(
                positionBaseMs = positionBaseMs,
                positionKnown = true,
                anchorElapsedRealtimeMs = null,
                playbackSpeed = 0f,
                isPlaying = false,
                durationMs = durationMs,
                durationKnown = durationKnown
            )
        }
    }
}

data class ClockReading(
    val positionMs: Long?,
    val known: Boolean
)
