package com.andsi.airlyrics.lyrics.catalog

import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.floating.FloatingLyricsRenderer
import com.andsi.airlyrics.lyrics.parser.TimedLyricsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShardLyricsAdapterTest {
    @Test
    fun bothTimedLanguagesSurviveInCurrentAndNextModes() {
        val raw = "[ar:Artist]\n" +
            "[00:01.000]<00:01.000>Original<00:02.000>\n" +
            "[00:01.000]<00:01.000>译文<00:02.000>\n" +
            "[00:03.000]<00:03.000>Next<00:04.000>\n" +
            "[00:03.000]<00:03.000>下一句<00:04.000>"
        val adapted = ShardLyricsAdapter.adapt(raw, "elrc")
        assertEquals(listOf("Original", "Next"), adapted.wordByWordLines.map { it.text })
        for (highlight in listOf(false, true)) {
            for (mode in listOf(
                com.andsi.airlyrics.core.model.LyricsLineDisplayMode.CURRENT_ONLY,
                com.andsi.airlyrics.core.model.LyricsLineDisplayMode.CURRENT_AND_NEXT
            )) {
                val view = TextView(ApplicationProvider.getApplicationContext())
                val renderer = FloatingLyricsRenderer(
                    textViewProvider = { view },
                    lineModeProvider = { mode },
                    wordByWordLyricsEnabledProvider = { highlight }
                )
                renderer.updatePlayback(1_100L, false)
                renderer.parseAndShow(adapted.plainLrc, wordByWordLines = adapted.wordByWordLines, emptyText = "empty")
                val expected = if (mode == com.andsi.airlyrics.core.model.LyricsLineDisplayMode.CURRENT_ONLY)
                    "Original\n译文" else "Original\n译文\nNext\n下一句"
                assertEquals(expected, view.text.toString())
            }
        }
    }

    @Test
    fun databaseBilingualCurrentLineShowsBothTextsAndRetainsTrackMetadata() {
        listOf(
            "[ar:Artist]\n[al:Album]\n[ti:Title]\n[by:Editor]\n[00:01.00]Original\n[00:01.00]译文\n[00:03.00]Next",
            "[ar:Artist]\n[al:Album]\n[ti:Title]\n[by:Editor]\n[00:01.00]<00:01.00>Original\n[00:01.00]译文"
        ).forEach { raw ->
            val adapted = ShardLyricsAdapter.adapt(raw, null)
            val view = TextView(ApplicationProvider.getApplicationContext())
            val renderer = FloatingLyricsRenderer(
                textViewProvider = { view },
                contentModeProvider = { com.andsi.airlyrics.core.model.LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
                lineModeProvider = { com.andsi.airlyrics.core.model.LyricsLineDisplayMode.CURRENT_ONLY },
                wordByWordLyricsEnabledProvider = { true }
            )
            renderer.updatePlayback(1_100L, false)
            renderer.parseAndShow(adapted.plainLrc, wordByWordLines = adapted.wordByWordLines, emptyText = "empty")
            assertEquals("Original\n译文", view.text.toString())
            renderer.updatePlayback(0L, false)
            renderer.parseAndShow(adapted.plainLrc, wordByWordLines = adapted.wordByWordLines, emptyText = "empty")
            assertEquals("Artist\nAlbum\nTitle\nEditor", view.text.toString())
            assertTrue(adapted.plainLrc.contains("[ar:Artist]"))
            assertTrue(adapted.plainLrc.contains("[al:Album]"))
            assertTrue(adapted.plainLrc.contains("[ti:Title]"))
            assertTrue(adapted.plainLrc.contains("[by:Editor]"))
        }
    }

    @Test
    fun elrc_becomesWordByWordAndPlainLrcForExistingRenderer() {
        val raw = "[00:01.00]<00:01.00>あ<00:01.40>い"
        val adapted = ShardLyricsAdapter.adapt(raw, "elrc")
        assertEquals(TimedLyricsFormat.ELRC, adapted.format)
        assertEquals(1, adapted.wordByWordLines.size)
        assertEquals("あい", adapted.wordByWordLines.single().text.replace(" ", ""))
        assertTrue(adapted.plainLrc.contains("あい") || adapted.plainLrc.contains("あ"))

        val view = TextView(ApplicationProvider.getApplicationContext())
        val renderer = FloatingLyricsRenderer(
            textViewProvider = { view },
            wordByWordLyricsEnabledProvider = { true }
        )
        renderer.updatePlayback(1_200L, isPlaying = false)
        renderer.parseAndShow(
            plainLrc = adapted.plainLrc,
            wordByWordLines = adapted.wordByWordLines,
            emptyText = "empty"
        )
        assertTrue(view.text.toString().isNotBlank())
        assertTrue(renderer.isWordByWordActive())
    }

    @Test
    fun elrcInlineBilingual_keepsTranslationForDisplay() {
        val raw = "[00:01.00]<00:01.00>あ<00:01.40>い / hello"
        val adapted = ShardLyricsAdapter.adapt(raw, "elrc")
        assertTrue(adapted.plainLrc.contains("hello"))
        org.junit.Assert.assertNull(adapted.translatedLrc)
    }

    @Test
    fun elrcSeparateTranslationLine_keepsTranslationForDisplay() {
        val raw = "[00:01.00]<00:01.00>あ<00:01.40>い\n[00:01.00]hello"
        val adapted = ShardLyricsAdapter.adapt(raw, "elrc")
        assertTrue(adapted.plainLrc.contains("hello"))
        org.junit.Assert.assertNull(adapted.translatedLrc)
    }

    @Test
    fun lineLrc_goesToExistingRendererWithoutWordByWord() {
        val raw = "[offset:500]\n[00:01.00]hello"
        val adapted = ShardLyricsAdapter.adapt(raw, "lrc")
        assertEquals(TimedLyricsFormat.LINE_LRC, adapted.format)
        assertTrue(adapted.wordByWordLines.isEmpty())
        assertTrue(adapted.plainLrc.contains("hello"))

        val view = TextView(ApplicationProvider.getApplicationContext())
        val renderer = FloatingLyricsRenderer(textViewProvider = { view })
        renderer.updatePlayback(1_600L, isPlaying = false)
        renderer.parseAndShow(adapted.plainLrc, emptyText = "empty")
        assertEquals("hello", view.text.toString())
    }

    @Test
    fun bracketWordByWord_isNotClaimedAsElrc() {
        val raw = "[00:01.00]你[00:01.20]好[00:01.40]嗎"
        val adapted = ShardLyricsAdapter.adapt(raw, "lrc")
        assertEquals(TimedLyricsFormat.UNSUPPORTED_BRACKET_WORD_BY_WORD, adapted.format)
        assertTrue(adapted.wordByWordLines.isEmpty())
    }
}

