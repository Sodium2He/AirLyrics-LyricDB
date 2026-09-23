package com.andsi.airlyrics.app.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.andsi.airlyrics.settings.store.LibrarySyncStore
import java.util.concurrent.TimeUnit

class LibrarySyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        if (!LibrarySyncStore.isEnabled(applicationContext)) {
            return Result.success()
        }
        if (LibrarySyncStore.getManifestUrl(applicationContext).isBlank()) {
            return Result.success()
        }
        LibrarySyncCoordinator.syncNow(applicationContext)
        return Result.success()
    }
}

internal object LibrarySyncScheduler {
    private const val PERIODIC_NAME = "library-webdav-sync"

    fun ensureScheduled(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LibrarySyncWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }

    fun enqueueNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueue(
                OneTimeWorkRequestBuilder<LibrarySyncWorker>().build()
            )
        }
    }

    fun watchWifiRestore(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val binder = WifiNetworkBinder(appContext)
            binder.registerDefaultCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        if (LibrarySyncStore.isEnabled(appContext) &&
                            LibrarySyncStore.getManifestUrl(appContext).isNotBlank() &&
                            binder.select().verdict ==
                            com.andsi.airlyrics.lyrics.catalog.WifiSyncVerdict.WIFI
                        ) {
                            enqueueNow(appContext)
                        }
                    }
                }
            )
        }
    }

    fun onAppStart(context: Context) {
        ensureScheduled(context)
        watchWifiRestore(context)
        if (LibrarySyncStore.isEnabled(context) &&
            LibrarySyncStore.getManifestUrl(context).isNotBlank()
        ) {
            enqueueNow(context)
        }
    }
}
