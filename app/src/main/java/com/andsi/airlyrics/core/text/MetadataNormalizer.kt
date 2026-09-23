package com.andsi.airlyrics.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Shared metadata normalization used by Windows and Android matching.
 *
 * Primary keys keep original wording, including live/remix/instrumental markers.
 * Width, punctuation, and feat. spelling only affect secondary keys.
 */
object MetadataNormalizer {
    private val whitespaceRegex = Regex("[\\t\\n\\r\\u00A0\\u3000 ]+")
    private val punctuationRegex = Regex("[\\p{Punct}、。，．！？：；「」『』（）()\\[\\]{}]+")
    private val featRegex = Regex(
        """(?i)(?<![A-Za-z0-9])(?:featuring|feat\.?|ft\.?)(?![A-Za-z0-9])"""
    )

    fun primary(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        return collapseWhitespace(nfc.lowercase(Locale.ROOT))
    }

    fun secondary(raw: String): String {
        val folded = foldFullwidthAscii(primary(raw))
        val featCanonical = featRegex.replace(folded, "feat")
        return collapseWhitespace(punctuationRegex.replace(featCanonical, " "))
    }

    fun stripLeadingTrackPrefix(raw: String): String {
        return raw.trim().replace(Regex("^\\d{1,3}(?:\\s*[-.)]\\s*|\\s+)"), "")
    }

    fun applyFileOffset(timeMs: Long, fileOffsetMs: Long): Long {
        return timeMs + fileOffsetMs
    }

    private fun collapseWhitespace(text: String): String {
        return whitespaceRegex.replace(text.trim(), " ")
    }

    private fun foldFullwidthAscii(text: String): String {
        val builder = StringBuilder(text.length)
        text.forEach { ch ->
            builder.append(
                if (ch in '\uFF01'..'\uFF5E') {
                    ch - 0xFEE0
                } else {
                    ch
                }
            )
        }
        return builder.toString()
    }
}
