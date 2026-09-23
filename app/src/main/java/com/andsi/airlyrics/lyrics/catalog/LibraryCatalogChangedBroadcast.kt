package com.andsi.airlyrics.lyrics.catalog

import android.content.Context
import android.content.Intent

object LibraryCatalogChangedBroadcast {
    const val ACTION = "com.andsi.airlyrics.LIBRARY_CATALOG_CHANGED"

    fun send(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }
}
