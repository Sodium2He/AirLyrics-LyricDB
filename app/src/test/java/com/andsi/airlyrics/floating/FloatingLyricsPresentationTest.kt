package com.andsi.airlyrics.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.catalog.ShardLyricsAdapter
import com.andsi.airlyrics.core.model.LyricLineFilter
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsPresentationTest {
    @Test fun timedUpdatesKeepWholeRowGeometryAndTranslationTiming() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = FloatingLyricsTextView(context).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            minWidth = 240
            maxWidth = 240
        }
        val style = FloatingLyricsStyleStore.getStyle(context).copy(textSizeSp = 24f,
            translationTextSizeSp = 16f, translationAlpha = 120)
        val adapted = ShardLyricsAdapter.adapt(
            "[00:01.000]<00:01.000>AVATAR ffi<00:04.000>\n" +
                "[00:01.000]<00:01.000>中文<00:02.500>译文<00:05.000>", "elrc")
        assertEquals(4_000L, adapted.wordByWordLines.single().endMs)
        assertEquals(5_000L, adapted.translationWordByWordLines.single().endMs)
        val renderer = FloatingLyricsRenderer({ view }, wordByWordLyricsEnabledProvider = { true },
            styleProvider = { style })
        renderer.updatePlayback(1_100L, false)
        renderer.parseAndShow(adapted.plainLrc, wordByWordLines = adapted.wordByWordLines,
            translationWordByWordLines = adapted.translationWordByWordLines, emptyText = "empty")
        fun draw() {
            view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        draw()
        val builds = view.layoutBuildCount
        val height = view.height
        for (position in listOf(1300L, 2500L, 4100L, 1600L)) {
            renderer.updatePlayback(position, false)
            renderer.tick()
            draw()
            assertEquals(builds, view.layoutBuildCount)
            assertEquals(height, view.height)
        }
        assertEquals("AVATAR ffi\n中文译文", view.text.toString())
    }

    @Test fun longLineScrollFollowsWordsAndSeeksWithoutAccumulatingWallTime() {
        val motion = LyricRowMotion(1000, 11000, true, WordByWordLine(1000, 11000, "long"), Color.CYAN)
        assertEquals(0f, lyricScrollOffset(600f, 200f, 1000, motion, 0f))
        assertEquals(210f, lyricScrollOffset(600f, 200f, 5000, motion, 300f))
        assertEquals(400f, lyricScrollOffset(600f, 200f, 11000, motion, 600f))
        assertEquals(0f, lyricScrollOffset(600f, 200f, 1000, motion, 0f))
        assertEquals(0f, lyricScrollOffset(100f, 200f, 5000, motion, 80f))
        assertEquals(0f, lyricScrollOffset(600f, 200f, 5000, motion.copy(isCurrent = false), 300f))
        assertEquals(200f, lyricScrollOffset(600f, 200f, 6000, motion.copy(words = null), null), 0.001f)
    }

    @Test fun optionalFilterRemovesSameTimePlaceholderBeforeBilingualPairing() {
        val raw = "[ar:Artist]\n[04:39.290]<04:39.290>.<04:39.290>\n" +
            "[04:39.290]<04:39.290>Original<04:42.430>\n" +
            "[04:39.290]<04:39.290>译文<04:42.810>\n[04:43.000]\n[04:44.000] "
        assertEquals(raw, LyricLineFilter().apply(raw))
        val filtered = LyricLineFilter(enabled = true, characters = ".").apply(raw)
        assertFalse(filtered.contains(">.<"))
        assertFalse(filtered.contains("[04:43.000]"))
        assertTrue(filtered.contains("[04:44.000] "))
        assertTrue(filtered.contains("[ar:Artist]"))
        val adapted = ShardLyricsAdapter.adapt(filtered, "elrc")
        assertEquals("Original", adapted.wordByWordLines.single().text)
        assertEquals("译文", adapted.translationWordByWordLines.single().text)
        assertTrue(adapted.plainLrc.contains("Original / 译文"))
    }

    @Test fun translationStylePersistsIndependentlyOfOriginalOpacityAndSize() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val style = FloatingLyricsStyleStore.getStyle(context).copy(translationTextSizeSp = 15f, translationAlpha = 102)
        FloatingLyricsStyleStore.setStyle(context, style)
        FloatingLyricsStyleStore.setTextSize(context, 32f)
        FloatingLyricsStyleStore.setTextAlpha(context, 200)
        val restored = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(15f, restored.translationTextSizeSp)
        assertEquals(102, restored.translationAlpha)
        assertEquals(200, Color.alpha(restored.textColor))
    }
}
