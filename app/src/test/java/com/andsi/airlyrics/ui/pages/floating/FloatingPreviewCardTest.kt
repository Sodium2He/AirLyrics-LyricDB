package com.andsi.airlyrics.ui.pages.floating

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.colorTextMuted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class FloatingPreviewCardTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun toggleUsesAppChromeColorWhenPreviewTextIsWhiteInLightTheme() {
        val app = RuntimeEnvironment.getApplication()
        ThemeSettingsStore.setDark(app, false)
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val host = activity.graph.uiHost
        val previewStyle = host.floatingStyle().copy(textColor = Color.WHITE)

        val handle = host.createFloatingPreviewCard(
            isExpanded = { true },
            setExpanded = {},
            style = { previewStyle },
            lineDisplayMode = { LyricsLineDisplayMode.CURRENT_ONLY },
            isWordByWordLyricsEnabled = { false },
            plainPreviewText = { "Original first visual line\nOriginal second visual line\nTranslation" },
            wordByWordPreviewText = { "Preview" }
        )
        val toggle = (handle.cardView as LinearLayout).getChildAt(1) as TextView

        assertEquals(Color.WHITE, handle.lyricTextView.currentTextColor)
        assertEquals(host.colorTextMuted, toggle.currentTextColor)
        assertNotEquals(previewStyle.textColor, toggle.currentTextColor)
        val preview = handle.lyricTextView
        preview.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(120, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        preview.layout(0, 0, preview.measuredWidth, preview.measuredHeight)
        org.junit.Assert.assertTrue(preview.layout.lineCount > 2)
        assertEquals(preview.text.length, preview.layout.getLineEnd(preview.layout.lineCount - 1))
        org.junit.Assert.assertTrue(preview.measuredHeight >= preview.layout.height)
    }
}
