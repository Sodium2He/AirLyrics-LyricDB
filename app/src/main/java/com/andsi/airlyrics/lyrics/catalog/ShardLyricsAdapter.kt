package com.andsi.airlyrics.lyrics.catalog

import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.LyricsFileOffset
import com.andsi.airlyrics.lyrics.parser.TimedLyricsFormat
import com.andsi.airlyrics.lyrics.parser.TimedLyricsFormatClassifier
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser

object ShardLyricsAdapter {
    const val PROVIDER_ID = "library"
    const val PROVIDER_NAME = "Library catalog"

    fun toProviderResult(
        rawText: String,
        format: String?,
        matched: CatalogTrackRef,
        catalogGeneration: Long,
        matchKind: String
    ): LyricsProviderResult {
        val classified = TimedLyricsFormatClassifier.classify(rawText)
        val adapted = adapt(rawText, format, classified)
        return LyricsProviderResult(
            plainProviderId = PROVIDER_ID,
            plainProviderName = PROVIDER_NAME,
            plainLrc = adapted.plainLrc,
            translatedLrc = adapted.translatedLrc,
            wordByWordLines = adapted.wordByWordLines,
            translationWordByWordLines = adapted.translationWordByWordLines,
            catalogRawText = rawText,
            catalogFormat = format,
            matchedTitle = matched.title.orEmpty(),
            matchedArtist = matched.artist.orEmpty(),
            matchedAlbum = matched.album.orEmpty(),
            matchedDurationMs = matched.durationMs ?: 0L,
            catalogGeneration = catalogGeneration,
            matchKind = matchKind
        )
    }

    fun adapt(
        rawText: String,
        format: String?,
        classified: TimedLyricsFormat = TimedLyricsFormatClassifier.classify(rawText)
    ): AdaptedLyrics {
        if (classified == TimedLyricsFormat.ELRC || format == "elrc") {
            val parsed = WordByWordLrcParser.parseImport(prepareBilingualElrc(rawText))
            return AdaptedLyrics(
                plainLrc = displayLyrics(rawText.replace(wordTimeTag, "")),
                wordByWordLines = retainTerminalTiming(parsed.wordByWordLines, rawText),
                translationWordByWordLines = timedTranslations(rawText),
                translatedLrc = null,
                format = TimedLyricsFormat.ELRC,
                diagnostics = emptyList()
            )
        }
        return AdaptedLyrics(
            plainLrc = if (classified == TimedLyricsFormat.LINE_LRC) displayLyrics(rawText) else rawText,
            wordByWordLines = emptyList(),
            translatedLrc = null,
            format = classified,
            diagnostics = emptyList()
        )
    }

    // Match the original import path: pair same-time translations and retain track metadata.
    private fun displayLyrics(rawText: String): String = LrcParser.normalizeForStorage(rawText)

    private val wordTimeTag = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")

    private fun retainTerminalTiming(lines: List<WordByWordLine>, raw: String): List<WordByWordLine> {
        val terminals = linkedMapOf<Long, Long>()
        raw.lineSequence().forEach { row ->
            val start = LrcParser.parse(row.replace(wordTimeTag, "")).firstOrNull { !it.isMetadata }?.timeMs
            val terminal = terminalTime(row)
            if (start != null && terminal != null) terminals.putIfAbsent(start, terminal)
        }
        return lines.map { line ->
            val terminal = terminals[line.startMs]
            if (terminal == null || terminal <= line.segments.last().startMs) line
            else line.copy(endMs = terminal, segments = line.segments.dropLast(1) +
                line.segments.last().copy(endMs = terminal))
        }
    }

    private fun terminalTime(row: String): Long? = wordTimeTag.findAll(row).lastOrNull()
        ?.takeIf { row.substring(it.range.last + 1).isBlank() }
        ?.value?.replace('<', '[')?.replace('>', ']')
        ?.let { LrcParser.parse(it).firstOrNull()?.timeMs }

    private fun timedTranslations(rawText: String): List<WordByWordLine> {
        val seenStarts = mutableSetOf<Long>()
        return rawText.lineSequence().mapNotNull { line ->
            if (!wordTimeTag.containsMatchIn(line)) return@mapNotNull null
            val parsed = WordByWordLrcParser.parse(line).singleOrNull() ?: return@mapNotNull null
            if (seenStarts.add(parsed.startMs)) return@mapNotNull null
            // A terminal tag supplies the duration of a whole-line translated segment.
            val terminal = terminalTime(line)
            if (terminal == null || terminal <= parsed.segments.last().startMs) parsed
            else parsed.copy(endMs = terminal, segments = parsed.segments.dropLast(1) +
                parsed.segments.last().copy(endMs = terminal))
        }.toList()
    }

    // Catalog exports can time both the original and its same-time translation.
    // The import parser expects subsequent translations without word tags; otherwise
    // the duplicate start makes it discard the original as a zero-duration line.
    private fun prepareBilingualElrc(rawText: String): String {
        val seenStarts = mutableSetOf<Long>()
        return rawText.lineSequence().joinToString("\n") { line ->
            if (!wordTimeTag.containsMatchIn(line)) return@joinToString line
            val plain = line.replace(wordTimeTag, "")
            val start = LrcParser.parse(plain).firstOrNull { !it.isMetadata }?.timeMs
            if (start != null && !seenStarts.add(start)) plain else line
        }
    }
}

data class AdaptedLyrics(
    val plainLrc: String,
    val wordByWordLines: List<WordByWordLine>,
    val translatedLrc: String?,
    val format: TimedLyricsFormat,
    val diagnostics: List<String>,
    val translationWordByWordLines: List<WordByWordLine> = emptyList()
)

internal fun WordByWordLine.shifted(deltaMs: Long): WordByWordLine {
    if (deltaMs == 0L) return this
    return copy(
        startMs = LyricsFileOffset.apply(startMs, deltaMs),
        endMs = LyricsFileOffset.apply(endMs, deltaMs),
        segments = segments.map { segment -> segment.shifted(deltaMs) }
    )
}

private fun WordByWordSegment.shifted(deltaMs: Long): WordByWordSegment {
    return copy(
        startMs = LyricsFileOffset.apply(startMs, deltaMs),
        endMs = LyricsFileOffset.apply(endMs, deltaMs)
    )
}

