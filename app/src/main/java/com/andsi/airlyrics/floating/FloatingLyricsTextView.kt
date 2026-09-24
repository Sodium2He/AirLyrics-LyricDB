package com.andsi.airlyrics.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import kotlin.math.ceil
import kotlin.math.roundToInt
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.design.tokens.AirUiTokens

/** Stable single-line layouts per lyric row. Highlight and scrolling only change the canvas. */
internal class FloatingLyricsTextView(context: Context) : AppCompatTextView(context) {
    private var textAnimator: ValueAnimator? = null
    private var textProgress = 1f
    private var textMode = LyricsSwitchAnimationMode.NONE

    private data class RowKey(val text: String, val size: Float, val color: Int,
        val typeface: android.graphics.Typeface?, val fakeBold: Boolean, val skew: Float,
        val letterSpacing: Float, val shadowRadius: Float, val shadowColor: Int)
    private data class ScrollRowIdentity(
        val rowKey: RowKey,
        val startMs: Long,
        val endMs: Long,
        val visibleIndex: Int
    )
    private data class Row(val key: RowKey, val layout: StaticLayout, val width: Float)
    private var rows = emptyList<Row>()
    private var motions = emptyList<LyricRowMotion?>()
    private var playbackPositionMs = 0L
    private var previousPlaybackPositionMs: Long? = null
    private var previousPlaybackUpdateUptimeMs: Long? = null
    private var resetScrollMotion = true
    private data class ScrollState(var offset: Float, var frameNanos: Long)
    private val scrollStates = mutableMapOf<ScrollRowIdentity, ScrollState>()
    private var sizeAnimator: ValueAnimator? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var animatedWidth = 0
    private var animatedHeight = 0
    internal var layoutBuildCount = 0
        private set

    fun renderLyrics(value: CharSequence, positionMs: Long) {
        val updateUptimeMs = SystemClock.uptimeMillis()
        val previousPosition = previousPlaybackPositionMs
        val previousUptime = previousPlaybackUpdateUptimeMs
        if (previousPosition != null && previousUptime != null) {
            val elapsed = (updateUptimeMs - previousUptime).coerceAtLeast(0L)
            if (shouldSnapLyricScroll(previousPosition, positionMs, elapsed)) {
                // A real seek should land immediately instead of visibly chasing the old location.
                resetScrollMotion = true
            }
        }
        previousPlaybackPositionMs = positionMs
        previousPlaybackUpdateUptimeMs = updateUptimeMs
        playbackPositionMs = positionMs
        val plain = value.toString()
        val spanned = value as? Spanned
        var start = 0
        val newMotions = mutableListOf<LyricRowMotion?>()
        val newRows = plain.split('\n').mapIndexed { index, rowText ->
            val end = start + rowText.length
            val rowPaint = TextPaint(paint).apply { color = currentTextColor }
            if (end > start && spanned != null) {
                spanned.getSpans(start, end, MetricAffectingSpan::class.java).forEach { it.updateMeasureState(rowPaint) }
                spanned.getSpans(start, end, CharacterStyle::class.java)
                    .filterNot { it is MetricAffectingSpan }.forEach { it.updateDrawState(rowPaint) }
            }
            newMotions += spanned?.getSpans(start, end, LyricRowMotion::class.java)?.firstOrNull()
            start = end + 1
            val key = RowKey(rowText, rowPaint.textSize, rowPaint.color, rowPaint.typeface,
                rowPaint.isFakeBoldText, rowPaint.textSkewX, rowPaint.letterSpacing, shadowRadius, shadowColor)
            rows.getOrNull(index)?.takeIf { it.key == key } ?: run {
                val desired = Layout.getDesiredWidth(rowText, rowPaint)
                val layout = StaticLayout.Builder.obtain(rowText, 0, rowText.length, rowPaint,
                    ceil(desired).toInt().coerceAtLeast(1) + 2)
                    .setIncludePad(false).setMaxLines(1).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                layoutBuildCount++
                Row(key, layout, desired)
            }
        }
        val geometryChanged = newRows != rows
        rows = newRows
        motions = newMotions
        // Accessibility retains complete text. Do not rebuild TextView layout on each karaoke tick.
        if (text.toString() != plain) text = plain
        if (geometryChanged) {
            resetScrollMotion = true
            requestLayout()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (rows.isEmpty()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val naturalWidth = maxOf(minWidth, ceil(rows.maxOf { it.width }).toInt() + paddingLeft + paddingRight)
            .coerceAtMost(maxWidth)
        val desiredWidth = resolveSize(naturalWidth, widthMeasureSpec)
        val desiredHeight = resolveSize(rows.sumOf { it.layout.height } + paddingTop + paddingBottom, heightMeasureSpec)
        if (desiredWidth != targetWidth || desiredHeight != targetHeight) {
            targetWidth = desiredWidth
            targetHeight = desiredHeight
            sizeAnimator?.cancel()
            if (isAttachedToWindow && animatedWidth > 0 && animatedHeight > 0 && ValueAnimator.areAnimatorsEnabled()) {
                val fromWidth = animatedWidth
                val fromHeight = animatedHeight
                sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val fraction = it.animatedValue as Float
                        animatedWidth = (fromWidth + (targetWidth - fromWidth) * fraction).roundToInt()
                        animatedHeight = (fromHeight + (targetHeight - fromHeight) * fraction).roundToInt()
                        requestLayout()
                    }
                    start()
                }
            } else {
                animatedWidth = targetWidth
                animatedHeight = targetHeight
            }
        }
        setMeasuredDimension(resolveSize(animatedWidth, widthMeasureSpec), resolveSize(animatedHeight, heightMeasureSpec))
    }

    private fun drawLyrics(canvas: Canvas) {
        val viewport = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val saved = canvas.save()
        canvas.clipRect(paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        var top = paddingTop.toFloat()
        var keepAnimatingScroll = false
        val frameNanos = System.nanoTime()
        if (resetScrollMotion) scrollStates.clear()
        val activeScrollRows = mutableSetOf<ScrollRowIdentity>()
        rows.forEachIndexed { index, row ->
            val motion = motions.getOrNull(index)
            val rtl = row.layout.getParagraphDirection(0) < 0
            val highlightX = motion?.words?.let { timed ->
                val progress = wordByWordHighlightProgress(timed, row.key.text, playbackPositionMs)
                val begin = row.layout.getPrimaryHorizontal(progress.activeStart)
                val end = row.layout.getPrimaryHorizontal(progress.activeEnd)
                if (progress.hasActiveCharacter) begin + (end - begin) * progress.activeFraction
                else row.layout.getPrimaryHorizontal(progress.completedEnd)
            }
            val logicalHighlight = highlightX?.let { if (rtl) row.layout.width - it else it }
            val targetOffset = lyricScrollOffset(
                row.width,
                viewport,
                playbackPositionMs,
                motion,
                logicalHighlight
            )
            val offset = if (motion?.isCurrent == true && row.width > viewport) {
                val rowIdentity = ScrollRowIdentity(
                    rowKey = row.key,
                    startMs = motion.startMs,
                    endMs = motion.endMs,
                    visibleIndex = index
                )
                activeScrollRows += rowIdentity
                val state = scrollStates.getOrPut(rowIdentity) { ScrollState(targetOffset, frameNanos) }
                if (state.frameNanos != frameNanos) {
                    val elapsedMs = ((frameNanos - state.frameNanos) / 1_000_000f)
                        .coerceIn(0f, 50f)
                    state.offset = smoothLyricScrollOffset(
                        state.offset,
                        targetOffset,
                        elapsedMs
                    )
                    state.frameNanos = frameNanos
                }
                state.offset = state.offset.coerceIn(0f, row.width - viewport)
                if (kotlin.math.abs(state.offset - targetOffset) >= 0.1f) {
                    keepAnimatingScroll = true
                }
                state.offset
            } else {
                targetOffset
            }
            val align = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL -> (viewport - row.width) / 2f
                Gravity.RIGHT -> viewport - row.width
                else -> 0f
            }
            val x = if (row.width <= viewport) align else if (rtl) viewport - row.width + offset else -offset
            val lineSave = canvas.save()
            canvas.translate(paddingLeft + x - row.layout.getLineLeft(0), top)
            val hasHighlight = highlightX != null && motion?.isCurrent == true
            val baseClip = canvas.save()
            if (hasHighlight) {
                if (rtl) canvas.clipRect(0f, 0f, highlightX!!, row.layout.height.toFloat())
                else canvas.clipRect(highlightX!!, 0f, row.layout.width.toFloat(), row.layout.height.toFloat())
            }
            row.layout.paint.color = row.key.color
            row.layout.draw(canvas)
            canvas.restoreToCount(baseClip)
            if (hasHighlight) {
                val clip = canvas.save()
                if (rtl) canvas.clipRect(highlightX!!, 0f, row.layout.width.toFloat(), row.layout.height.toFloat())
                else canvas.clipRect(0f, 0f, highlightX!!, row.layout.height.toFloat())
                row.layout.paint.color = (motion!!.highlightColor and 0x00ffffff) or (Color.alpha(row.key.color) shl 24)
                row.layout.draw(canvas)
                row.layout.paint.color = row.key.color
                canvas.restoreToCount(clip)
            }
            canvas.restoreToCount(lineSave)
            top += row.layout.height
        }
        resetScrollMotion = false
        scrollStates.keys.retainAll(activeScrollRows)
        canvas.restoreToCount(saved)
        if (keepAnimatingScroll) postInvalidateOnAnimation()
    }

    fun resetTextAnimation() {
        textAnimator?.cancel()
        textAnimator = null
        textProgress = 1f
        invalidate()
    }

    fun animateText(mode: LyricsSwitchAnimationMode) {
        resetTextAnimation()
        textMode = mode
        if (mode == LyricsSwitchAnimationMode.NONE || !ValueAnimator.areAnimatorsEnabled()) return
        textProgress = 0f
        textAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (mode) {
                LyricsSwitchAnimationMode.SLIDE_UP -> AirUiTokens.Layout.LyricsSlideMs
                LyricsSwitchAnimationMode.SCALE_FADE -> AirUiTokens.Layout.LyricsScaleFadeMs
                else -> AirUiTokens.Layout.LyricsFadeMs
            }
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                textProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        if (textProgress >= 1f) {
            drawLyrics(canvas)
            return
        }
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * textProgress).toInt())
        when (textMode) {
            LyricsSwitchAnimationMode.SLIDE_UP -> canvas.translate(0f,
                (1f - textProgress) * AirUiTokens.Layout.LyricsSlideDistanceDp * resources.displayMetrics.density)
            LyricsSwitchAnimationMode.SCALE_FADE -> {
                val scale = AirUiTokens.Layout.LyricsScaleStart + (1f - AirUiTokens.Layout.LyricsScaleStart) * textProgress
                canvas.scale(scale, scale, width / 2f, height / 2f)
            }
            else -> Unit
        }
        drawLyrics(canvas)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        resetTextAnimation()
        sizeAnimator?.cancel()
        sizeAnimator = null
        scrollStates.clear()
        previousPlaybackPositionMs = null
        previousPlaybackUpdateUptimeMs = null
        resetScrollMotion = true
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
