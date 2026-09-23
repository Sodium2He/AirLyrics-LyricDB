package com.andsi.airlyrics.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.andsi.airlyrics.lyrics.catalog.ShardLyricsAdapter
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class FloatingLyricsPresentationInstrumentedTest {
    @Test fun timedUpdatesKeepWholeRowGeometryAndTranslationTiming() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
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
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(10, 14, 24))
            view.draw(canvas)
            java.io.File(context.cacheDir, "presentation.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
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
    }
}
