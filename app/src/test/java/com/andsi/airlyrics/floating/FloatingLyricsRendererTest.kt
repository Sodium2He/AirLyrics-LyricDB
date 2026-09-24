package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsRendererTest {
    @Test
    fun emptyStatusHidesBackgroundAndLyricsRestoreVisibility() {
        val view = FloatingLyricsTextView(ApplicationProvider.getApplicationContext())
        view.setBackgroundColor(Color.BLACK)
        view.setPadding(20, 10, 20, 10)
        val renderer = FloatingLyricsRenderer(textViewProvider = { view })
        renderer.show("")
        assertEquals(android.view.View.INVISIBLE, view.visibility)
        renderer.parseAndShow("[00:00.00]lyrics", emptyText = "")
        assertEquals(android.view.View.VISIBLE, view.visibility)
        renderer.parseAndShow("", emptyText = "")
        assertEquals(android.view.View.INVISIBLE, view.visibility)
        renderer.show("No lyrics")
        assertEquals(android.view.View.VISIBLE, view.visibility)
    }

    @Test
    fun parseAndShowReportsOnlyRenderableLyricsAsAvailable() {
        val view = TextView(ApplicationProvider.getApplicationContext())
        val renderer = FloatingLyricsRenderer(textViewProvider = { view })

        assertEquals(
            ParsedLyricsAvailability.EMPTY,
            renderer.parseAndShow("[ar:Artist]\n[al:Album]", emptyText = "empty")
        )
        assertEquals("empty", view.text.toString())
        renderer.updatePlayback(10_000L, false)
        renderer.tick()
        renderer.refresh()
        assertEquals("empty", view.text.toString())

        assertEquals(
            ParsedLyricsAvailability.AVAILABLE,
            renderer.parseAndShow("[00:01.00]lyrics", emptyText = "empty")
        )

        assertEquals(
            ParsedLyricsAvailability.AVAILABLE,
            renderer.parseAndShow(
                plainLrc = "",
                wordByWordLines = listOf(timedLine("Standalone")),
                emptyText = "empty"
            )
        )
    }

    @Test
    fun lineSwitchDoesNotAnimateTheBackgroundView() {
        val view = FloatingLyricsTextView(ApplicationProvider.getApplicationContext())
        view.setBackgroundColor(Color.BLACK)
        val renderer = FloatingLyricsRenderer(
            textViewProvider = { view },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.FADE }
        )
        renderer.updatePlayback(1000L, false)
        renderer.parseAndShow("[00:01.00]one\n[00:02.00]two", emptyText = "empty")
        renderer.updatePlayback(2000L, false)
        renderer.tick()
        assertEquals("two", view.text.toString())
        assertEquals(1f, view.alpha)
        assertEquals(0f, view.translationY)
        assertEquals(1f, view.scaleX)
        view.resetTextAnimation()
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun updatePlayback_acceptsSmallSeekWhilePlaying() {
        var now = 10_000L
        val renderer = renderer(monotonicNowMsProvider = { now })

        renderer.updatePlayback(positionMs = 1_000L, isPlaying = true)
        now += 700L
        assertEquals(1_700L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 1_200L, isPlaying = true)
        assertEquals(1_200L, renderer.getEstimatedPositionMs())

        now += 100L
        assertEquals(1_300L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun updateClock_usesSpeedFromOriginalAnchor() {
        var now = 5_000L
        val renderer = renderer(monotonicNowMsProvider = { now })
        renderer.updateClock(
            com.andsi.airlyrics.core.time.PlaybackClockSnapshot.playing(
                positionBaseMs = 10_000L,
                anchorElapsedRealtimeMs = 2_000L,
                playbackSpeed = 1.5f
            )
        )
        now = 6_000L
        assertEquals(16_000L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun updatePlayback_acceptsLargeSeekAndPausedPosition() {
        var now = 10_000L
        val renderer = renderer(monotonicNowMsProvider = { now })

        renderer.updatePlayback(positionMs = 10_000L, isPlaying = true)
        now += 3_000L
        assertEquals(13_000L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 8_000L, isPlaying = true)
        assertEquals(8_000L, renderer.getEstimatedPositionMs())

        now += 700L
        renderer.updatePlayback(positionMs = 8_200L, isPlaying = false)
        assertEquals(8_200L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun setLyricsOffset_reportsChangesAndNeverReturnsANegativePosition() {
        val renderer = renderer()
        renderer.updatePlayback(positionMs = 1_000L, isPlaying = false)

        assertTrue(renderer.setLyricsOffset(500L))
        assertEquals(1_500L, renderer.getEstimatedPositionMs())
        assertFalse(renderer.setLyricsOffset(500L))

        assertTrue(renderer.setLyricsOffset(-2_000L))
        assertEquals(0L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun parseAndTick_renderConfiguredPlainContentAndNeighborLines() {
        val textView = TextView(context)
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            lineMode = LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT
        )
        renderer.parseAndShow(
            plainLrc = "[00:01.00]first\n[00:02.00]second\n[00:03.00]third",
            translatedLrc = "[00:01.00]第一句\n[00:02.00]第二句\n[00:03.00]第三句",
            emptyText = "empty"
        )

        renderer.updatePlayback(positionMs = 2_100L, isPlaying = false)
        renderer.tick()

        assertEquals("first\n第一句\nsecond\n第二句\nthird\n第三句", textView.text.toString())
    }

    @Test
    fun translationOnly_doesNotLeakWordByWordOriginalText() {
        val textView = TextView(context)
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.TRANSLATION_ONLY,
            wordByWordEnabled = true
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]Hello",
            translatedLrc = "[00:01.00]你好",
            wordByWordLines = listOf(timedLine("Hello")),
            emptyText = "empty"
        )

        assertTrue(renderer.isWordByWordActive())
        assertEquals("你好", textView.text.toString())
        assertTrue(textView.text.highlightSpans().all { Color.alpha(it.foregroundColor) == 153 })
    }

    @Test
    fun matchingWordByWordLine_highlightsBothLanguagesWithIndependentOpacity() {
        val textView = TextView(context).apply { setTextColor(Color.WHITE) }
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            wordByWordEnabled = true,
            highlightColor = Color.MAGENTA
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]Hello",
            translatedLrc = "[00:01.00]你好",
            wordByWordLines = listOf(timedLine("Hello")),
            emptyText = "empty"
        )

        assertEquals("Hello\n你好", textView.text.toString())
        val text = textView.text as Spanned
        val completedSpan = text.highlightSpans()
            .first { it.foregroundColor == Color.MAGENTA }
        assertEquals(0, text.getSpanStart(completedSpan))
        assertEquals(2, text.getSpanEnd(completedSpan))
        val translated = text.highlightSpans().filter { text.getSpanStart(it) >= "Hello\n".length }
        assertTrue(translated.isNotEmpty())
        assertTrue(translated.all { Color.alpha(it.foregroundColor) == 153 })
    }

    @Test
    fun mismatchedWordByWordLine_fallsBackToUnhighlightedPlainLyrics() {
        val textView = TextView(context)
        val renderer = renderer(textView = textView, wordByWordEnabled = true)
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]Hello",
            wordByWordLines = listOf(timedLine("Unrelated words")),
            emptyText = "empty"
        )

        assertEquals("Hello", textView.text.toString())
        assertTrue(textView.text.highlightSpans().isEmpty())
    }

    @Test
    fun wordByWordOnlyPayload_stillRendersWhenPlainLrcHasNoTimedLines() {
        val textView = TextView(context)
        val renderer = renderer(textView = textView, wordByWordEnabled = true)
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "not a timed lyric",
            wordByWordLines = listOf(timedLine("Standalone")),
            emptyText = "empty"
        )

        assertEquals("Standalone", textView.text.toString())
        assertTrue(textView.text.highlightSpans().isNotEmpty())
    }

    @Test
    fun refreshAndClear_resetTextWithoutKeepingPreviousTimingState() {
        val textView = TextView(context)
        val renderer = renderer(textView = textView)
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)
        renderer.parseAndShow("[00:01.00]line", emptyText = "empty")
        assertEquals("line", textView.text.toString())

        renderer.show("manual")
        renderer.refresh()
        assertEquals("manual", textView.text.toString())

        renderer.clear()
        assertEquals(0L, renderer.getEstimatedPositionMs())
        assertFalse(renderer.isWordByWordActive())
        assertEquals(1f, textView.alpha)
        assertEquals(0f, textView.translationY)
        assertEquals(1f, textView.scaleX)
        assertEquals(1f, textView.scaleY)
    }

    private fun renderer(
        textView: TextView? = null,
        contentMode: LyricsContentDisplayMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
        lineMode: LyricsLineDisplayMode = LyricsLineDisplayMode.CURRENT_ONLY,
        wordByWordEnabled: Boolean = false,
        highlightColor: Int = Color.MAGENTA,
        monotonicNowMsProvider: () -> Long = { 10_000L }
    ): FloatingLyricsRenderer {
        return FloatingLyricsRenderer(
            textViewProvider = { textView },
            contentModeProvider = { contentMode },
            lineModeProvider = { lineMode },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.NONE },
            wordByWordLyricsEnabledProvider = { wordByWordEnabled },
            wordByWordHighlightColorProvider = { highlightColor },
            noTranslationTextProvider = { "no translation" },
            monotonicNowMsProvider = monotonicNowMsProvider
        )
    }

    private fun timedLine(text: String): WordByWordLine {
        return WordByWordLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = text,
            segments = listOf(WordByWordSegment(text, 1_000L, 2_000L))
        )
    }

    private fun CharSequence.highlightSpans(): Array<ForegroundColorSpan> {
        return (this as? Spanned)
            ?.getSpans(0, length, ForegroundColorSpan::class.java)
            ?: emptyArray()
    }
}
