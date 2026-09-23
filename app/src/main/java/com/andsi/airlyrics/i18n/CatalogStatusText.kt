package com.andsi.airlyrics.i18n

import android.content.Context
import com.andsi.airlyrics.R

fun catalogStatusText(context: Context, status: String): String = context.getString(when (status) {
    "matched" -> R.string.ui_catalog_matched
    "absent" -> R.string.ui_catalog_absent
    "ambiguous" -> R.string.ui_catalog_ambiguous
    "truncated" -> R.string.ui_catalog_truncated
    "read_error" -> R.string.ui_catalog_read_error
    "unmatched" -> R.string.ui_catalog_unmatched
    else -> R.string.ui_catalog_inactive
})
