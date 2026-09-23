package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

object LibrarySyncStore {
    private const val PREFS_NAME = "library_sync"
    private const val KEY_MANIFEST_URL = "manifest_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_DETAIL = "last_detail"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_GENERATION = "last_generation"
    private const val KEY_LAST_LIBRARY_ID = "last_library_id"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getManifestUrl(context: Context): String {
        return store(context).getString(KEY_MANIFEST_URL).orEmpty()
    }

    fun getUsername(context: Context): String {
        return store(context).getString(KEY_USERNAME).orEmpty()
    }

    fun getPassword(context: Context): String {
        return store(context).getString(KEY_PASSWORD).orEmpty()
    }

    fun isEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_ENABLED, enabled)
    }

    fun setEndpoint(
        context: Context,
        manifestUrl: String,
        username: String,
        password: String
    ) {
        store(context).edit {
            putString(KEY_MANIFEST_URL, manifestUrl.trim())
            putString(KEY_USERNAME, username.trim())
            putString(KEY_PASSWORD, password)
        }
    }

    fun lastReason(context: Context): String? {
        return store(context).getString(KEY_LAST_REASON)
    }

    fun lastDetail(context: Context): String? = store(context).getString(KEY_LAST_DETAIL)

    fun lastAtUtc(context: Context): Long {
        return store(context).getLong(KEY_LAST_AT, 0L)
    }

    fun lastGeneration(context: Context): Long? {
        val value = store(context).getLong(KEY_LAST_GENERATION, -1L)
        return value.takeIf { it >= 0L }
    }

    fun lastLibraryId(context: Context): String? {
        return store(context).getString(KEY_LAST_LIBRARY_ID)?.takeIf { it.isNotBlank() }
    }

    fun recordOutcome(
        context: Context,
        reason: String,
        libraryId: String? = null,
        generation: Long? = null,
        detail: String? = null
    ) {
        store(context).edit {
            putString(KEY_LAST_REASON, reason)
            putString(KEY_LAST_DETAIL, detail.orEmpty())
            putLong(KEY_LAST_AT, System.currentTimeMillis())
            if (libraryId != null) putString(KEY_LAST_LIBRARY_ID, libraryId)
            if (generation != null) putLong(KEY_LAST_GENERATION, generation)
        }
    }

    fun recordReject(context: Context, reason: String, detail: String? = null) {
        recordOutcome(context, reason, detail = detail)
    }
}
