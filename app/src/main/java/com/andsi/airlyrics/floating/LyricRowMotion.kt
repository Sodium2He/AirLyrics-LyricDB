package com.andsi.airlyrics.floating

import com.andsi.airlyrics.lyrics.WordByWordLine
import kotlin.math.exp

/** Non-visual span: playback changes never split text shaping runs. */
internal data class LyricRowMotion(
    val startMs: Long,
    val endMs: Long,
    val isCurrent: Boolean,
    val words: WordByWordLine? = null,
    val highlightColor: Int
)

internal fun lyricScrollOffset(
    textWidth: Float,
    viewportWidth: Float,
    positionMs: Long,
    motion: LyricRowMotion?,
    highlightX: Float?
): Float {
    val overflow = (textWidth - viewportWidth).coerceAtLeast(0f)
    if (overflow == 0f || motion == null || !motion.isCurrent) return 0f
    if (highlightX != null) {
        // Keep the singing position inside the viewport, allowing context on both sides.
        return (highlightX - viewportWidth * 0.45f).coerceIn(0f, overflow)
    }
    if (motion.endMs <= motion.startMs) return 0f
    val progress = ((positionMs - motion.startMs).toFloat() / (motion.endMs - motion.startMs))
        .coerceIn(0f, 1f)
    // Brief readable holds at both ends; this is derived from playback, not wall time.
    val moving = ((progress - 0.08f) / 0.84f).coerceIn(0f, 1f)
    val eased = moving * moving * (3f - 2f * moving)
    return overflow * eased
}

/**
 * Follows a continuously changing scroll target without exposing the 40 ms lyric tick as steps.
 * The exponential response is frame-rate independent and cannot overshoot the highlighted word.
 */
internal fun smoothLyricScrollOffset(
    current: Float,
    target: Float,
    elapsedMs: Float,
    responseMs: Float = 90f
): Float {
    if (!current.isFinite() || !target.isFinite()) return target
    if (elapsedMs <= 0f || responseMs <= 0f) return current
    val remaining = target - current
    if (kotlin.math.abs(remaining) < 0.1f) return target
    val fraction = 1f - exp((-elapsedMs / responseMs).toDouble()).toFloat()
    return current + remaining * fraction.coerceIn(0f, 1f)
}

internal fun shouldSnapLyricScroll(
    previousPositionMs: Long,
    positionMs: Long,
    elapsedRealtimeMs: Long
): Boolean {
    val actualAdvance = positionMs - previousPositionMs
    val correction = actualAdvance - elapsedRealtimeMs.coerceAtLeast(0L)
    return actualAdvance < -120L || kotlin.math.abs(correction) > 350L
}
