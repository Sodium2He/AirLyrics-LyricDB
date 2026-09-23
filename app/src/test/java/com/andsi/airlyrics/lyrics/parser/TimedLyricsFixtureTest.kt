package com.andsi.airlyrics.lyrics.parser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedLyricsFixtureTest {
    @Test
    fun lineLrc_sharedFixtures() {
        val fixture = loadFixture("lrc-time-semantics.json")
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            val input = case.getString("input")
            val format = TimedLyricsFormatClassifier.classify(input)

            when (case.getString("format")) {
                "unsupported_bracket_word_by_word" -> {
                    assertEquals(id, TimedLyricsFormat.UNSUPPORTED_BRACKET_WORD_BY_WORD, format)
                    assertTrue(id, WordByWordLrcParser.parse(input).isEmpty())
                    assertFalse(id, case.optBoolean("word_by_word_accepted", true))
                }
                "line_lrc" -> {
                    assertEquals(id, TimedLyricsFormat.LINE_LRC, format)
                    val parsed = LrcParser.parseDocument(input)
                    assertEquals(id, case.getLong("file_offset_ms"), parsed.fileOffsetMs)

                    if (case.has("expected_raw_lines")) {
                        assertLines(id, case.getJSONArray("expected_raw_lines"), parsed.rawLines)
                    }
                    when {
                        case.has("expected_display_lines") -> {
                            assertLines(id, case.getJSONArray("expected_display_lines"), parsed.lines)
                        }
                        case.has("expected_lines") -> {
                            assertLines(id, case.getJSONArray("expected_lines"), parsed.lines)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun elrc_sharedFixtures() {
        val fixture = loadFixture("elrc-time-semantics.json")
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            val input = case.getString("input")
            assertEquals(id, TimedLyricsFormat.ELRC, TimedLyricsFormatClassifier.classify(input))

            val parsed = WordByWordLrcParser.parseImport(input)
            assertEquals(id, case.getLong("file_offset_ms"), parsed.fileOffsetMs)

            case.optJSONArray("diagnostics_contain")?.let { required ->
                val codes = parsed.diagnostics.map { it.code }.toSet()
                for (diagIndex in 0 until required.length()) {
                    assertTrue(
                        "$id missing ${required.getString(diagIndex)} in $codes",
                        required.getString(diagIndex) in codes
                    )
                }
            }

            if (case.has("expected_plain_lrc")) {
                assertEquals(id, case.getString("expected_plain_lrc"), parsed.plainLrc)
                assertEquals(id, case.getBoolean("has_translation"), parsed.hasTranslation)
            }

            if (case.has("expected_line_count")) {
                assertEquals(id, case.getInt("expected_line_count"), parsed.wordByWordLines.size)
            }

            if (case.has("expected_raw_start_ms")) {
                val first = parsed.wordByWordLines.single()
                assertEquals(id, case.getLong("expected_raw_start_ms"), first.startMs)
                assertEquals(
                    id,
                    case.getLong("expected_display_start_ms"),
                    LyricsFileOffset.apply(first.startMs, parsed.fileOffsetMs)
                )
            }

            if (case.has("expected_lines")) {
                val expectedLines = case.getJSONArray("expected_lines")
                assertEquals(id, expectedLines.length(), parsed.wordByWordLines.size)
                for (lineIndex in 0 until expectedLines.length()) {
                    val expected = expectedLines.getJSONObject(lineIndex)
                    val actual = parsed.wordByWordLines[lineIndex]
                    assertEquals(id, expected.getLong("start_ms"), actual.startMs)
                    assertEquals(id, expected.getString("text"), actual.text)
                    if (expected.has("end_ms")) {
                        assertEquals(id, expected.getLong("end_ms"), actual.endMs)
                    }
                    if (case.has("expect_last_character")) {
                        assertEquals(
                            id,
                            case.getString("expect_last_character"),
                            actual.text.last().toString()
                        )
                        assertEquals(
                            id,
                            case.getString("expect_last_character"),
                            actual.segments.last().text
                        )
                    }
                    if (expected.has("segments")) {
                        val expectedSegments = expected.getJSONArray("segments")
                        assertEquals(id, expectedSegments.length(), actual.segments.size)
                        for (segmentIndex in 0 until expectedSegments.length()) {
                            val expectedSegment = expectedSegments.getJSONObject(segmentIndex)
                            val actualSegment = actual.segments[segmentIndex]
                            assertEquals(id, expectedSegment.getString("text"), actualSegment.text)
                            assertEquals(id, expectedSegment.getLong("start_ms"), actualSegment.startMs)
                            if (expectedSegment.has("end_ms")) {
                                assertEquals(id, expectedSegment.getLong("end_ms"), actualSegment.endMs)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertLines(
        id: String,
        expected: org.json.JSONArray,
        actual: List<LrcLine>
    ) {
        assertEquals(id, expected.length(), actual.size)
        for (index in 0 until expected.length()) {
            val item = expected.getJSONObject(index)
            assertEquals(id, item.getLong("time_ms"), actual[index].timeMs)
            assertEquals(id, item.getString("text"), actual[index].text)
            if (item.has("translation")) {
                assertEquals(id, item.getString("translation"), actual[index].translation)
            }
        }
    }

    private fun loadFixture(name: String): JSONObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing shared fixture: $name"
        }
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }
}
