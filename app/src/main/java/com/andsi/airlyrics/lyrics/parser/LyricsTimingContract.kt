package com.andsi.airlyrics.lyrics.parser

data class LyricsParseDiagnostic(
    val code: String,
    val detail: String? = null
)

data class LyricsFileOffset(
    val offsetMs: Long,
    val diagnostics: List<LyricsParseDiagnostic> = emptyList()
) {
    companion object {
        private val integerOffsetRegex = Regex(
            """^\[offset:([+-]?\d+)]$""",
            RegexOption.IGNORE_CASE
        )

        fun parse(metadataLines: List<String>): LyricsFileOffset {
            val diagnostics = mutableListOf<LyricsParseDiagnostic>()
            val parsedOffsets = mutableListOf<Long>()

            metadataLines.forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("[offset:", ignoreCase = true)) {
                    return@forEach
                }

                val integerMatch = integerOffsetRegex.matchEntire(trimmed)
                if (integerMatch != null) {
                    parsedOffsets += integerMatch.groupValues[1].toLong()
                } else {
                    diagnostics += LyricsParseDiagnostic(
                        code = "unsupported_offset_format",
                        detail = trimmed
                    )
                }
            }

            if (parsedOffsets.size > 1) {
                diagnostics += LyricsParseDiagnostic("multiple_offset_tags")
            }

            return LyricsFileOffset(
                offsetMs = parsedOffsets.lastOrNull() ?: 0L,
                diagnostics = diagnostics
            )
        }

        fun apply(timeMs: Long, offsetMs: Long): Long = timeMs + offsetMs
    }
}

data class ParsedPlainLyrics(
    val lines: List<LrcLine>,
    val rawLines: List<LrcLine>,
    val fileOffsetMs: Long,
    val diagnostics: List<LyricsParseDiagnostic> = emptyList()
)

enum class TimedLyricsFormat {
    LINE_LRC,
    ELRC,
    UNSUPPORTED_BRACKET_WORD_BY_WORD,
    MIXED_AMBIGUOUS,
    PLAIN
}

object TimedLyricsFormatClassifier {
    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTimeTagRegex = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    fun classify(text: String): TimedLyricsFormat {
        if (text.isBlank()) return TimedLyricsFormat.PLAIN

        var sawElrc = false
        var sawLineLrc = false
        var sawBracketWordByWord = false

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            if (wordTimeTagRegex.containsMatchIn(line)) {
                sawElrc = true
                return@forEach
            }
            when (classifyLineWithoutElrc(line)) {
                LineKind.BRACKET_WORD_BY_WORD -> sawBracketWordByWord = true
                LineKind.LINE_LRC -> sawLineLrc = true
                LineKind.OTHER -> Unit
            }
        }

        return when {
            sawElrc && sawBracketWordByWord -> TimedLyricsFormat.MIXED_AMBIGUOUS
            sawElrc -> TimedLyricsFormat.ELRC
            sawBracketWordByWord && !sawLineLrc -> TimedLyricsFormat.UNSUPPORTED_BRACKET_WORD_BY_WORD
            sawBracketWordByWord -> TimedLyricsFormat.UNSUPPORTED_BRACKET_WORD_BY_WORD
            sawLineLrc -> TimedLyricsFormat.LINE_LRC
            else -> TimedLyricsFormat.PLAIN
        }
    }

    private fun classifyLineWithoutElrc(line: String): LineKind {
        val tags = timeTagRegex.findAll(line).toList()
        if (tags.isEmpty()) return LineKind.OTHER

        val fragments = tags.mapIndexed { index, tag ->
            val start = tag.range.last + 1
            val end = tags.getOrNull(index + 1)?.range?.first ?: line.length
            if (start > end) "" else line.substring(start, end)
        }

        val nonBlankFragments = fragments.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlankFragments.isEmpty()) return LineKind.LINE_LRC

        val looksLikeCharacterKaraoke = tags.size >= 3 &&
            nonBlankFragments.size >= 2 &&
            nonBlankFragments.all { fragment ->
                fragment.length <= 2 && !fragment.contains(' ')
            }
        if (looksLikeCharacterKaraoke) return LineKind.BRACKET_WORD_BY_WORD

        return LineKind.LINE_LRC
    }

    private enum class LineKind {
        LINE_LRC,
        BRACKET_WORD_BY_WORD,
        OTHER
    }
}
