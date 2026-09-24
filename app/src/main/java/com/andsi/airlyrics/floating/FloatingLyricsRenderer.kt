package com.andsi.airlyrics.floating

import android.graphics.Color
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.text.styledLyricText
import android.os.SystemClock
import com.andsi.airlyrics.core.time.PlaybackClockSnapshot
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.display.PlainLyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.design.tokens.AirUiTokens

enum class ParsedLyricsAvailability {
    AVAILABLE,
    EMPTY
}

/**
 * Maintains parsed lyric lines and renders the line matching the current playback position.
 */
class FloatingLyricsRenderer(
    private val textViewProvider: () -> TextView?,
    private val contentModeProvider: () -> LyricsContentDisplayMode = { LyricsContentDisplayMode.default },
    private val lineModeProvider: () -> LyricsLineDisplayMode = { LyricsLineDisplayMode.default },
    private val switchAnimationModeProvider: () -> LyricsSwitchAnimationMode = { LyricsSwitchAnimationMode.default },
    private val wordByWordLyricsEnabledProvider: () -> Boolean = { false },
    private val wordByWordHighlightColorProvider: () -> Int = { Color.rgb(120, 220, 255) },
    private val noTranslationTextProvider: () -> String = { "No translation for this lyric" },
    private val monotonicNowMsProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val styleProvider: () -> FloatingLyricsStyle? = { null }
) {
    private var currentPlainLines: List<LrcLine> = emptyList()
    private var currentWordByWordLines: List<WordByWordLine> = emptyList()
    private var currentTranslationWordByWordLines: List<WordByWordLine> = emptyList()
    private var clock: PlaybackClockSnapshot = PlaybackClockSnapshot.Unknown
    private var lyricsOffsetMs: Long = 0L
    private var lastRenderedText: String? = null

    fun updatePlayback(positionMs: Long, isPlaying: Boolean) {
        updateClock(
            PlaybackClockSnapshot(
                positionBaseMs = positionMs,
                positionKnown = true,
                anchorElapsedRealtimeMs = monotonicNowMsProvider(),
                playbackSpeed = if (isPlaying) 1f else 0f,
                isPlaying = isPlaying,
                durationMs = clock.durationMs,
                durationKnown = clock.durationKnown
            )
        )
    }

    fun updateClock(snapshot: PlaybackClockSnapshot) {
        clock = snapshot
    }

    fun freezePlayback() {
        val position = clock.positionAt(monotonicNowMsProvider()).positionMs ?: return
        clock = PlaybackClockSnapshot.frozen(
            positionBaseMs = position,
            durationMs = clock.durationMs,
            durationKnown = clock.durationKnown
        )
    }

    fun clear() {
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        currentTranslationWordByWordLines = emptyList()
        clock = PlaybackClockSnapshot.Unknown
        lyricsOffsetMs = 0L
        lastRenderedText = null
        resetTextAnimationState()
    }

    fun show(text: String) {
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty() && lastRenderedText == text) return
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        currentTranslationWordByWordLines = emptyList()
        setTextImmediately(text)
    }

    /**
     * Updates the timing offset. Rendering is caller-controlled so parse/clear flows do
     * not repaint stale lyrics before replacing the renderer state.
     */
    fun setLyricsOffset(offsetMs: Long): Boolean {
        if (lyricsOffsetMs == offsetMs) return false
        lyricsOffsetMs = offsetMs
        return true
    }

    fun parseAndShow(
        plainLrc: String,
        translatedLrc: String? = null,
        wordByWordLines: List<WordByWordLine> = emptyList(),
        emptyText: String,
        translationWordByWordLines: List<WordByWordLine> = emptyList()
    ): ParsedLyricsAvailability {
        currentPlainLines = LrcParser.parseWithTranslation(plainLrc, translatedLrc)
        currentWordByWordLines = wordByWordLines
        currentTranslationWordByWordLines = translationWordByWordLines

        val availability = if (hasRenderableLyrics()) {
            ParsedLyricsAvailability.AVAILABLE
        } else {
            ParsedLyricsAvailability.EMPTY
        }
        val text = if (availability == ParsedLyricsAvailability.AVAILABLE) {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: emptyText
        } else {
            // Do not let a subsequent tick/refresh resurrect metadata-only content.
            currentPlainLines = emptyList()
            currentWordByWordLines = emptyList()
            currentTranslationWordByWordLines = emptyList()
            emptyText
        }

        setTextImmediately(text)
        return availability
    }

    private fun hasRenderableLyrics(): Boolean {
        val hasPlainLyrics = currentPlainLines.any { line ->
            !line.isMetadata && (line.text.isNotBlank() || line.hasTranslation())
        }
        val hasWordByWordLyrics = currentWordByWordLines.any { line ->
            line.text.isNotBlank() || line.segments.any { it.text.isNotBlank() }
        }
        val hasTranslationWordByWordLyrics = currentTranslationWordByWordLines.any { line ->
            line.text.isNotBlank() || line.segments.any { it.text.isNotBlank() }
        }
        return hasPlainLyrics || hasWordByWordLyrics || hasTranslationWordByWordLyrics
    }

    fun tick() {
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) return

        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() } ?: return
        setTextWithOptionalAnimation(text)
    }

    fun isWordByWordActive(): Boolean {
        return wordByWordLyricsEnabledProvider() && (currentWordByWordLines.isNotEmpty() || currentTranslationWordByWordLines.isNotEmpty())
    }

    fun refresh() {
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) return
        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() }
            ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
            ?: return
        setTextImmediately(text)
    }

    private fun renderAtCurrentPosition(): CharSequence {
        val positionMs = getEstimatedPositionMs()
        val currentIndex = LrcParser.findCurrentIndex(currentPlainLines, positionMs)

        if (currentIndex != null) return renderTextAtIndex(currentIndex, positionMs)

        // Safety fallback for unusual payloads. Word-by-word lyrics are independent segment
        // timing data, so they can still render when the accompanying plain LRC has no usable line.
        if (wordByWordLyricsEnabledProvider() && currentWordByWordLines.isNotEmpty()) {
            val wordByWordIndex = findCurrentWordByWordIndex(positionMs)
            if (wordByWordIndex != null) {
                renderWordByWordOnlyAtIndex(wordByWordIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
        }

        return ""
    }

    private fun renderPlainTextAtIndex(index: Int): CharSequence {
        return renderTextAtIndex(index, getEstimatedPositionMs())
    }

    /** One presentation path for plain and timed bilingual text. */
    private fun renderTextAtIndex(currentIndex: Int, positionMs: Long): CharSequence {
        if (currentPlainLines.isEmpty() || currentIndex !in currentPlainLines.indices) return ""

        val indexes = visiblePlainLineIndexes(currentIndex)
        if (indexes.isEmpty()) return ""

        val renderedLines = mutableListOf<CharSequence>()
        val contentMode = contentModeProvider()
        val style = styleProvider()
        val baseColor = textViewProvider()?.currentTextColor ?: Color.WHITE
        val translationAlpha = style?.translationAlpha ?: 153

        fun styled(text: CharSequence, translation: Boolean, current: Boolean) = styledLyricText(
            text, translation, current, baseColor,
            style?.let { it.translationTextSizeSp / it.textSizeSp } ?: 0.76f, translationAlpha
        )

        indexes.forEach { index ->
            val line = currentPlainLines[index]
            val original = line.text.trim()
            val translation = line.translation.orEmpty().trim()
            if (line.isMetadata) {
                val metadata = PlainLyricsDisplayFormatter.formatMetadata(original)
                if (metadata.isNotBlank()) renderedLines += metadata
                return@forEach
            }

            val isCurrent = index == currentIndex
            val wordByWordLine = if (isCurrent && wordByWordLyricsEnabledProvider()) findWordByWordLineForPlainLine(line, positionMs) else null

            val lineEnd = currentPlainLines.drop(index + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs
                ?: clock.durationMs?.takeIf { clock.durationKnown && it > line.timeMs }
                ?: wordByWordLine?.endMs ?: line.timeMs

            fun presented(text: String, translated: Boolean, timing: WordByWordLine?): CharSequence {
                val displayed = if (timing != null) wordByWordLineSpan(timing, text, positionMs,
                    if (translated) translationAlpha else null) else text
                return SpannableStringBuilder(styled(displayed, translated, isCurrent)).apply {
                    if (isNotEmpty()) setSpan(LyricRowMotion(line.timeMs, lineEnd, isCurrent, timing,
                        wordByWordHighlightColorProvider()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            fun originalText(): CharSequence = presented(original, false, wordByWordLine)
            fun translatedText(): CharSequence {
                val text = SpannableStringBuilder()
                translation.lines().forEachIndexed { i, part ->
                    val timed = if (isCurrent && wordByWordLyricsEnabledProvider()) {
                        currentTranslationWordByWordLines.firstOrNull {
                            kotlin.math.abs(it.startMs - line.timeMs) <= 10L && it.text.trim() == part.trim()
                        } ?: lineEnd.takeIf { it > line.timeMs }?.let {
                            // Without translated word tags, animate over the known sentence duration.
                            WordByWordLine(line.timeMs, it, part)
                        }
                    } else null
                    if (i > 0) text.append('\n')
                    text.append(presented(part, true, timed))
                }
                return text
            }

            when (contentMode) {
                LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> {
                    val block = SpannableStringBuilder()
                    if (original.isNotBlank()) {
                        block.append(originalText())
                    }
                    if (line.hasTranslation()) {
                        if (block.isNotEmpty()) block.append('\n')
                        block.append(translatedText())
                    }
                    if (block.isNotBlankText()) renderedLines += block
                }

                LyricsContentDisplayMode.ORIGINAL_ONLY -> {
                    if (original.isNotBlank()) {
                        renderedLines += originalText()
                    }
                }

                LyricsContentDisplayMode.TRANSLATION_ONLY -> {
                    if (line.hasTranslation()) renderedLines += translatedText()
                }
            }
        }

        if (renderedLines.isEmpty()) {
            return if (contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY) {
                noTranslationTextProvider()
            } else {
                ""
            }
        }

        return SpannableStringBuilder().apply {
            renderedLines.forEachIndexed { renderedIndex, renderedLine ->
                if (renderedIndex > 0) append('\n')
                append(renderedLine)
            }
        }
    }

    private fun visiblePlainLineIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentPlainLines.indices }
    }

    private fun findWordByWordLineForPlainLine(plainLine: LrcLine, positionMs: Long): WordByWordLine? {
        if (plainLine.isMetadata) return null
        if (currentWordByWordLines.isEmpty()) return null

        fun List<WordByWordLine>.bestCompatible(maxDistanceMs: Long): WordByWordLine? {
            return sortedBy { kotlin.math.abs(it.startMs - plainLine.timeMs) }
                .firstOrNull { candidate ->
                    kotlin.math.abs(candidate.startMs - plainLine.timeMs) <= maxDistanceMs &&
                        isTextCompatible(plainLine.text, wordByWordOriginalText(candidate))
                }
        }

        val aroundPosition = currentWordByWordLines
            .filter { positionMs in (it.startMs - 350L)..(it.endMs + 700L) }
            .bestCompatible(maxDistanceMs = 2_500L)
        if (aroundPosition != null) return aroundPosition

        return currentWordByWordLines.bestCompatible(maxDistanceMs = 1_500L)
    }

    private fun renderWordByWordOnlyAtIndex(index: Int, positionMs: Long): CharSequence {
        if (contentModeProvider() == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            return noTranslationTextProvider()
        }

        val renderedLines = visibleWordByWordIndexes(index).mapNotNull { visibleIndex ->
            val wordByWordLine = currentWordByWordLines.getOrNull(visibleIndex) ?: return@mapNotNull null
            val original = wordByWordOriginalText(wordByWordLine)
            if (original.isBlank()) {
                null
            } else if (visibleIndex == index) {
                SpannableStringBuilder(wordByWordLineSpan(wordByWordLine, original, positionMs)).apply {
                    setSpan(LyricRowMotion(wordByWordLine.startMs, wordByWordLine.endMs, true,
                        wordByWordLine, wordByWordHighlightColorProvider()), 0, length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                original
            }
        }

        if (renderedLines.isEmpty()) return ""

        return SpannableStringBuilder().apply {
            renderedLines.forEachIndexed { renderedIndex, renderedLine ->
                if (renderedIndex > 0) append('\n')
                append(renderedLine)
            }
        }
    }

    private fun visibleWordByWordIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentWordByWordLines.indices }
    }

    private fun wordByWordLineSpan(
        wordByWordLine: WordByWordLine,
        displayText: String,
        positionMs: Long,
        alphaOverride: Int? = null
    ): CharSequence {
        val text = displayText.trim()
        if (text.isBlank()) return ""
        // The actual overlay clips a second draw of the same layout; no moving shaping boundaries.
        if (textViewProvider() is FloatingLyricsTextView) return text

        val progress = wordByWordHighlightProgress(wordByWordLine, text, positionMs)
        val span = SpannableString(text)
        if (progress.completedEnd > 0 || progress.hasActiveCharacter) {
            val highlightColor = wordByWordHighlightColorProvider().let {
                if (alphaOverride != null) ColorUtils.setAlphaComponent(it, alphaOverride) else it
            }
            if (progress.completedEnd > 0) {
                span.setSpan(
                    ForegroundColorSpan(highlightColor),
                    0,
                    progress.completedEnd.coerceIn(0, text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (progress.hasActiveCharacter) {
                val baseColor = (textViewProvider()?.currentTextColor ?: Color.WHITE).let {
                    if (alphaOverride != null) ColorUtils.setAlphaComponent(it, alphaOverride) else it
                }
                val transitioningColor = ColorUtils.blendARGB(
                    baseColor,
                    highlightColor,
                    progress.activeFraction
                )
                span.setSpan(
                    ForegroundColorSpan(transitioningColor),
                    progress.activeStart.coerceIn(0, text.length),
                    progress.activeEnd.coerceIn(0, text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return span
    }

    private fun wordByWordOriginalText(wordByWordLine: WordByWordLine): String {
        return wordByWordLine.text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    private fun normalizeWordByWordMatchText(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
            .trim()
    }

    private fun isTextCompatible(lrcText: String, wordByWordText: String): Boolean {
        val lrc = normalizeWordByWordMatchText(lrcText)
        val wordByWordLyrics = normalizeWordByWordMatchText(wordByWordText)
        if (lrc.isBlank() || wordByWordLyrics.isBlank()) return false
        if (lrc == wordByWordLyrics) return true
        if (lrc.length >= 2 && wordByWordLyrics.length >= 2 && (lrc.contains(wordByWordLyrics) || wordByWordLyrics.contains(lrc))) {
            return true
        }

        val shorter = if (lrc.length <= wordByWordLyrics.length) lrc else wordByWordLyrics
        val longer = if (lrc.length <= wordByWordLyrics.length) wordByWordLyrics else lrc
        val minCommonLength = when {
            shorter.length >= 12 -> 8
            shorter.length >= 6 -> 4
            else -> return false
        }

        return (0..(shorter.length - minCommonLength)).any { start ->
            longer.contains(shorter.substring(start, start + minCommonLength))
        }
    }

    private fun findCurrentWordByWordIndex(positionMs: Long): Int? {
        if (currentWordByWordLines.isEmpty()) return null

        var left = 0
        var right = currentWordByWordLines.lastIndex
        var result: Int? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val line = currentWordByWordLines[mid]

            if (line.startMs <= positionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }

    private fun setTextImmediately(text: CharSequence) {
        val view = textViewProvider() ?: return
        view.visibility = if (text.isBlank()) android.view.View.INVISIBLE else android.view.View.VISIBLE
        (view as? FloatingLyricsTextView)?.resetTextAnimation()
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = AirUiTokens.Motion.RestScale
        view.scaleY = AirUiTokens.Motion.RestScale
        if (view is FloatingLyricsTextView) view.renderLyrics(text, getEstimatedPositionMs())
        else view.text = text
        lastRenderedText = text.toString()
    }

    private fun setTextWithOptionalAnimation(text: CharSequence) {
        val textKey = text.toString()
        val actualView = textViewProvider()
        if (actualView is FloatingLyricsTextView && textKey == lastRenderedText) {
            actualView.renderLyrics(text, getEstimatedPositionMs())
            return
        }
        val isWordByWordTick = isWordByWordActive()
        if (!isWordByWordTick && textKey == lastRenderedText) return

        val mode = switchAnimationModeProvider()
        if ((isWordByWordTick && actualView !is FloatingLyricsTextView) || lastRenderedText == null) {
            setTextImmediately(text)
            return
        }

        if (actualView is FloatingLyricsTextView) {
            setTextImmediately(text)
            actualView.animateText(mode)
            return
        }

        when (mode) {
            LyricsSwitchAnimationMode.NONE -> setTextImmediately(text)
            LyricsSwitchAnimationMode.FADE -> {
                val view = prepareTextSwitchAnimation(text, textKey) ?: return
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = AirUiTokens.Motion.RestScale
                view.scaleY = AirUiTokens.Motion.RestScale
                view.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .setDuration(AirUiTokens.Layout.LyricsFadeMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            LyricsSwitchAnimationMode.SLIDE_UP -> {
                val view = prepareTextSwitchAnimation(text, textKey) ?: return
                view.alpha = 0f
                view.translationY = AirUiTokens.Layout.LyricsSlideDistanceDp * view.resources.displayMetrics.density
                view.scaleX = AirUiTokens.Motion.RestScale
                view.scaleY = AirUiTokens.Motion.RestScale
                view.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .translationY(0f)
                    .setDuration(AirUiTokens.Layout.LyricsSlideMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            LyricsSwitchAnimationMode.SCALE_FADE -> {
                val view = prepareTextSwitchAnimation(text, textKey) ?: return
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = AirUiTokens.Layout.LyricsScaleStart
                view.scaleY = AirUiTokens.Layout.LyricsScaleStart
                view.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .scaleX(AirUiTokens.Motion.RestScale)
                    .scaleY(AirUiTokens.Motion.RestScale)
                    .setDuration(AirUiTokens.Layout.LyricsScaleFadeMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun prepareTextSwitchAnimation(text: CharSequence, textKey: String): TextView? {
        val view = textViewProvider() ?: return null
        view.animate().cancel()
        view.text = text
        view.visibility = if (text.isBlank()) android.view.View.INVISIBLE else android.view.View.VISIBLE
        lastRenderedText = textKey
        return view
    }

    private fun resetTextAnimationState() {
        textViewProvider()?.let { view ->
            (view as? FloatingLyricsTextView)?.resetTextAnimation()
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = AirUiTokens.Motion.RestScale
            view.scaleY = AirUiTokens.Motion.RestScale
        }
    }

    private fun CharSequence.isNotBlankText(): Boolean = toString().isNotBlank()

    fun getEstimatedPositionMs(): Long {
        return (getEstimatedPlaybackPositionMs() + lyricsOffsetMs).coerceAtLeast(0L)
    }

    private fun getEstimatedPlaybackPositionMs(nowMs: Long = monotonicNowMsProvider()): Long {
        return clock.positionAt(nowMs).positionMs ?: 0L
    }
}
