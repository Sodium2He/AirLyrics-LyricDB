package com.andsi.airlyrics.settings.store

import android.content.Context
import android.content.Intent

object StatusHintsChangedBroadcast {
    const val ACTION = "com.andsi.airlyrics.STATUS_HINTS_CHANGED"

    fun send(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }
}
