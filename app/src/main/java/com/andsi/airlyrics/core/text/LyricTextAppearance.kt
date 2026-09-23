package com.andsi.airlyrics.core.text

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Shared by the actual overlay and its thumbnail; timing spans remain on top. */
internal fun styledLyricText(
    text: CharSequence,
    isTranslation: Boolean,
    isCurrent: Boolean,
    textColor: Int,
    translationScale: Float = 0.76f,
    translationAlpha: Int = 153
): CharSequence = SpannableStringBuilder(text.toString()).apply {
    if (isEmpty()) return@apply
    val size = (if (isTranslation) translationScale else 1f) * (if (isCurrent) 1f else 0.8f)
    if (size != 1f) setSpan(RelativeSizeSpan(size), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val alpha = ((if (isTranslation) translationAlpha else Color.alpha(textColor)) *
        (if (isCurrent) 1f else 0.58f)).roundToInt().coerceIn(0, 255)
    if (isTranslation || !isCurrent) {
        setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(textColor, alpha)),
            0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (isCurrent && !isTranslation) {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (text is Spanned) TextUtils.copySpansFrom(text, 0, text.length, Any::class.java, this, 0)
}
