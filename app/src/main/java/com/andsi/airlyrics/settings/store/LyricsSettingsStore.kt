package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.core.prefs.prefs

object LyricsSettingsStore {
    fun getLineFilter(context: Context) = com.andsi.airlyrics.core.model.LyricLineFilter(
        enabled = store(context).getBoolean("line_filter_enabled", false),
        characters = store(context).getString("line_filter_characters", ".·・…").orEmpty(),
        filterEmpty = store(context).getBoolean("line_filter_empty", true)
    )

    fun setLineFilter(context: Context, filter: com.andsi.airlyrics.core.model.LyricLineFilter) {
        store(context).edit {
            putBoolean("line_filter_enabled", filter.enabled)
            putString("line_filter_characters", filter.characters)
            putBoolean("line_filter_empty", filter.filterEmpty)
        }
    }

    fun areStatusHintsEnabled(context: Context): Boolean = store(context).getBoolean("show_status_hints", false)

    fun setStatusHintsEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean("show_status_hints", enabled)
        StatusHintsChangedBroadcast.send(context)
    }

    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_CONTENT_DISPLAY_MODE = "content_display_mode"
    private const val KEY_LINE_DISPLAY_MODE = "line_display_mode"
    private const val KEY_SWITCH_ANIMATION_MODE = "switch_animation_mode"
    // Persisted compatibility contract. Do not change the serialized value.
    private const val KEY_WORD_BY_WORD_LYRICS_ENABLED = "karaoke_lyrics_enabled"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getContentDisplayMode(context: Context): LyricsContentDisplayMode {
        val value = store(context).getString(KEY_CONTENT_DISPLAY_MODE, LyricsContentDisplayMode.default.key)

        return LyricsContentDisplayMode.fromKey(value)
    }

    fun setContentDisplayMode(context: Context, mode: LyricsContentDisplayMode) {
        store(context).setString(KEY_CONTENT_DISPLAY_MODE, mode.key)
    }

    fun getLineDisplayMode(context: Context): LyricsLineDisplayMode {
        val value = store(context).getString(KEY_LINE_DISPLAY_MODE, LyricsLineDisplayMode.default.key)

        return LyricsLineDisplayMode.fromKey(value)
    }

    fun setLineDisplayMode(context: Context, mode: LyricsLineDisplayMode) {
        store(context).setString(KEY_LINE_DISPLAY_MODE, mode.key)
    }

    fun getSwitchAnimationMode(context: Context): LyricsSwitchAnimationMode {
        val value = store(context).getString(KEY_SWITCH_ANIMATION_MODE, LyricsSwitchAnimationMode.default.key)

        return LyricsSwitchAnimationMode.fromKey(value)
    }

    fun setSwitchAnimationMode(context: Context, mode: LyricsSwitchAnimationMode) {
        store(context).setString(KEY_SWITCH_ANIMATION_MODE, mode.key)
    }

    fun isWordByWordLyricsEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_WORD_BY_WORD_LYRICS_ENABLED, false)
    }

    fun setWordByWordLyricsEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_WORD_BY_WORD_LYRICS_ENABLED, enabled)
    }

}
