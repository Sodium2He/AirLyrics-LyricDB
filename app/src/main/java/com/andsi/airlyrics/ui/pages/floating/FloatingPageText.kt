package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.widget.TextView
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlin.math.roundToInt

internal data class FloatingPreviewLyricLine(
    val original: String,
    val translation: String,
    val isCurrent: Boolean
)

internal fun MainUiHost.floatingSectionTitle(title: CharSequence): TextView {
    return TextView(this).apply {
        text = title
        textSize = FloatingPageTokens.SECTION_TITLE_TEXT_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextStrong)
        setPadding(
            0,
            dp(FloatingPageTokens.SECTION_TITLE_PADDING_TOP_DP),
            0,
            dp(FloatingPageTokens.SECTION_TITLE_PADDING_BOTTOM_DP)
        )
    }
}

internal fun previewMaxLines(mode: LyricsLineDisplayMode): Int {
    return when (mode) {
        LyricsLineDisplayMode.CURRENT_ONLY -> 2
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> 4
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 6
    }
}

/**
 * Maps the real floating-window size to a stable thumbnail size. More visible
 * lyric blocks use a smaller range so changing the setting never takes over the page.
 */
internal fun previewTextSizeSp(
    textSizeSp: Float,
    lineMode: LyricsLineDisplayMode
): Float {
    val progress = ((textSizeSp.coerceIn(14f, 56f) - 14f) / (56f - 14f))
    val (previewMin, previewMax) = when (lineMode) {
        LyricsLineDisplayMode.CURRENT_ONLY -> 16f to 30f
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> 14f to 24f
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 12f to 20f
    }
    return previewMin + (previewMax - previewMin) * progress
}

internal fun alphaToOpacityPercent(alpha: Int): Int {
    return (alpha.coerceIn(0, 255) * 100f / 255f).roundToInt()
}

internal fun opacityPercentToAlpha(percent: Int): Int {
    return (percent.coerceIn(0, 100) * 255f / 100f).roundToInt()
}

internal fun formattedPreviewLyrics(
    mode: LyricsContentDisplayMode,
    lines: List<FloatingPreviewLyricLine>,
    textColor: Int,
    translationScale: Float = 0.76f,
    translationAlpha: Int = 153
): CharSequence {
    val result = SpannableStringBuilder()
    fun append(text: String, translation: Boolean, current: Boolean) {
        if (text.isBlank()) return
        if (result.isNotEmpty()) result.append('\n')
        result.append(com.andsi.airlyrics.core.text.styledLyricText(
            text, translation, current, textColor, translationScale, translationAlpha
        ))
    }
    lines.forEach { line ->
        if (mode != LyricsContentDisplayMode.TRANSLATION_ONLY) append(line.original, false, line.isCurrent)
        if (mode != LyricsContentDisplayMode.ORIGINAL_ONLY) append(line.translation, true, line.isCurrent)
    }
    return result
}
