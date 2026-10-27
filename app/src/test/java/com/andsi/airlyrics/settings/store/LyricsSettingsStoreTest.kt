package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsSettingsStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun displayModes_returnDefaultsWhenNothingWasSaved() {
        assertEquals(LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION, LyricsSettingsStore.getContentDisplayMode(context))
        assertEquals(LyricsLineDisplayMode.CURRENT_ONLY, LyricsSettingsStore.getLineDisplayMode(context))
        assertEquals(LyricsSwitchAnimationMode.FADE, LyricsSettingsStore.getSwitchAnimationMode(context))
        assertFalse(LyricsSettingsStore.isWordByWordLyricsEnabled(context))
    }

    @Test
    fun displayModeSettingsRoundTrip() {
        LyricsSettingsStore.setContentDisplayMode(context, LyricsContentDisplayMode.TRANSLATION_ONLY)
        LyricsSettingsStore.setLineDisplayMode(context, LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT)
        LyricsSettingsStore.setSwitchAnimationMode(context, LyricsSwitchAnimationMode.FADE)
        LyricsSettingsStore.setWordByWordLyricsEnabled(context, true)

        assertEquals(LyricsContentDisplayMode.TRANSLATION_ONLY, LyricsSettingsStore.getContentDisplayMode(context))
        assertEquals(LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT, LyricsSettingsStore.getLineDisplayMode(context))
        assertEquals(LyricsSwitchAnimationMode.FADE, LyricsSettingsStore.getSwitchAnimationMode(context))
        assertTrue(LyricsSettingsStore.isWordByWordLyricsEnabled(context))
    }

    @Test
    fun wordByWordLyricsEnabled_usesCompatibilityPreferenceKey() {
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)

        LyricsSettingsStore.setWordByWordLyricsEnabled(context, true)

        assertTrue(preferences.contains("karaoke_lyrics_enabled"))
        assertTrue(preferences.getBoolean("karaoke_lyrics_enabled", false))

        preferences.edit()
            .clear()
            .putBoolean("karaoke_lyrics_enabled", true)
            .commit()

        assertTrue(LyricsSettingsStore.isWordByWordLyricsEnabled(context))
    }

}
