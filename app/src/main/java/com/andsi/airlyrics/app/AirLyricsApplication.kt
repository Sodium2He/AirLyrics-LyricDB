package com.andsi.airlyrics.app

import android.app.Application
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.app.sync.LibrarySyncScheduler

class AirLyricsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNightMode.applyStoredMode(this)
        LibrarySyncScheduler.onAppStart(this)
    }
}
