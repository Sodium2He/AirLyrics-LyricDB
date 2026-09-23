package com.andsi.airlyrics.media

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.media.dump.MediaSessionDump
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import java.io.File
import java.nio.charset.StandardCharsets

class MediaNotificationListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mediaSessionObserver: MediaSessionObserver

    private val rescanRunnable = Runnable {
        mediaSessionObserver.refresh()
    }

    override fun onCreate() {
        LanguageSettingsStore.applyAppLocale(this)
        super.onCreate()
        mediaSessionObserver = MediaSessionObserver(
            context = this,
            handler = handler,
            listener = object : MediaSessionObserver.Listener {
                override fun onCurrentMediaChanged(media: CurrentMediaInfo) {
                    publishMediaInfo(media)
                }

                override fun onMediaSourceLost(packageName: String) {
                    publishMediaSourceLost(packageName)
                }

                override fun onObservationError(message: String, error: Throwable) {
                    if (error is SecurityException) {
                        Log.e(TAG, "Need notification access permission", error)
                    } else {
                        Log.e(TAG, message, error)
                    }
                }
            },
            dumpSink = ::writeSessionDump
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (BuildConfig.DEBUG) Log.d(TAG, "Notification listener connected")
        mediaSessionObserver.start()
    }

    override fun onListenerDisconnected() {
        cleanupMediaSessions()
        super.onListenerDisconnected()
        requestNotificationListenerRebind()
    }

    override fun onDestroy() {
        cleanupMediaSessions()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        scheduleMediaSessionRescan()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        scheduleMediaSessionRescan()
    }

    private fun scheduleMediaSessionRescan() {
        // Some players refresh sessions only after notification changes, so scan once after the burst.
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, MEDIA_SESSION_RESCAN_DELAY_MS)
    }

    private fun cleanupMediaSessions() {
        handler.removeCallbacks(rescanRunnable)
        if (::mediaSessionObserver.isInitialized) {
            mediaSessionObserver.stop()
        }
    }

    private fun requestNotificationListenerRebind() {
        val component = ComponentName(
            this,
            MediaNotificationListenerService::class.java
        )

        runCatching {
            requestRebind(component)
        }
    }

    private fun publishMediaSourceLost(packageName: String) {
        if (packageName.isBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "media source lost: source=$packageName")
        }

        CurrentMediaBroadcast.mediaSourceLostIntent(this, packageName)?.let(::sendBroadcast)
    }

    private fun publishMediaInfo(media: CurrentMediaInfo) {
        if (media.title.isBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "media: source=${media.sourcePackage} title=${media.title} artist=${media.artist} " +
                        "duration=${media.durationMs} position=${media.positionMs} playing=${media.isPlaying} " +
                        "sequence=${media.snapshotSequence}"
            )
        }

        sendBroadcast(CurrentMediaBroadcast.mediaUpdateIntent(this, media))
    }

    private fun writeSessionDump(dump: MediaSessionDump) {
        if (!BuildConfig.DEBUG) return

        runCatching {
            val dir = File(filesDir, "symfoniumx-dumps")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "last-session.json").writeText(
                dump.toJson().toString(2),
                StandardCharsets.UTF_8
            )
            Log.d(TAG, "session dump: ${dump.toJson()}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to write session dump", error)
        }
    }

    companion object {
        private const val TAG = "MediaNotificationListenerService"
        private const val MEDIA_SESSION_RESCAN_DELAY_MS = 200L
    }
}
