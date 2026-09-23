package com.andsi.airlyrics.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.andsi.airlyrics.lyrics.catalog.FileSyncAcceptanceStore
import com.andsi.airlyrics.lyrics.catalog.LibraryCatalog
import com.andsi.airlyrics.lyrics.catalog.LocalCatalogActivator
import com.andsi.airlyrics.lyrics.catalog.ManifestShard
import com.andsi.airlyrics.lyrics.catalog.PublishRelativeUrl
import com.andsi.airlyrics.lyrics.catalog.Sha256Hex
import com.andsi.airlyrics.lyrics.catalog.SyncOutcome
import com.andsi.airlyrics.lyrics.catalog.SyncRejectReason
import com.andsi.airlyrics.lyrics.catalog.WebDavSyncEngine
import com.andsi.airlyrics.lyrics.catalog.WifiCapabilityEvaluator
import com.andsi.airlyrics.lyrics.catalog.WifiSyncVerdict
import com.andsi.airlyrics.settings.store.LibrarySyncStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface LibrarySyncOperation {
    fun sync(): SyncOutcome
    fun forceSync(): SyncOutcome = sync()
}

internal class AndroidLibrarySyncOperation(
    context: Context
) : LibrarySyncOperation {
    private val appContext = context.applicationContext

    override fun sync(): SyncOutcome = LibrarySyncCoordinator.syncNow(appContext)
    override fun forceSync(): SyncOutcome = LibrarySyncCoordinator.syncNow(appContext, force = true)
}

internal object LibrarySyncCoordinator {
    @Synchronized
    fun syncNow(context: Context, force: Boolean = false): SyncOutcome {
        val appContext = context.applicationContext
        if (LibrarySyncStore.getManifestUrl(appContext).isBlank()) {
            LibrarySyncStore.recordReject(appContext, SyncRejectReason.NO_URL.name)
            return SyncOutcome.Rejected(SyncRejectReason.NO_URL)
        }

        val binder = WifiNetworkBinder(appContext)
        val selected = binder.select()
        if (selected.verdict != WifiSyncVerdict.WIFI || selected.network == null) {
            val reason = when (selected.verdict) {
                WifiSyncVerdict.VPN_UNCONFIRMED -> SyncRejectReason.VPN_UNCONFIRMED
                else -> SyncRejectReason.NOT_WIFI
            }
            LibrarySyncStore.recordReject(appContext, reason.name)
            return SyncOutcome.Rejected(reason)
        }

        val cancelled = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == selected.network) cancelled.set(true)
            }

            override fun onAvailable(network: Network) {
                if (binder.select().verdict != WifiSyncVerdict.WIFI) {
                    cancelled.set(true)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val verdict = WifiCapabilityEvaluator.verdict(
                    transports = WifiNetworkBinder.transports(networkCapabilities),
                    hasInternet = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                )
                if (verdict != WifiSyncVerdict.WIFI) cancelled.set(true)
            }
        }

        return try {
            runCatching { binder.registerDefaultCallback(callback) }
            val staging = File(appContext.cacheDir, "library-webdav-staging")
            val engine = WebDavSyncEngine(
                wifiGate = { binder.select().verdict },
                remote = BoundWebDavClient(
                    network = selected.network,
                    manifestUrl = LibrarySyncStore.getManifestUrl(appContext),
                    username = LibrarySyncStore.getUsername(appContext).ifBlank { null },
                    password = LibrarySyncStore.getPassword(appContext).ifBlank { null }
                ),
                stateStore = FileSyncAcceptanceStore.forLibrary(LibraryCatalog.directory(appContext)),
                activator = { dir ->
                    LocalCatalogActivator.activateFromPublishDirectory(appContext, dir, force)
                },
                currentCatalog = {
                    LibraryCatalog.openIfPresent(appContext)?.use { it.currentMeta() }
                },
                existingVerifiedShard = { shard -> verifiedLocalShard(appContext, shard) },
                stagingRoot = staging,
                cancelled = { cancelled.get() },
                force = force
            )
            val outcome = engine.sync()
            when (outcome) {
                is SyncOutcome.Activated -> LibrarySyncStore.recordOutcome(
                    context = appContext,
                    reason = "ACTIVATED",
                    libraryId = outcome.libraryId,
                    generation = outcome.generation
                )
                is SyncOutcome.Rejected -> LibrarySyncStore.recordReject(
                    appContext,
                    outcome.reason.name,
                    outcome.detail
                )
            }
            outcome
        } finally {
            binder.unregister(callback)
        }
    }

    private fun verifiedLocalShard(context: Context, shard: ManifestShard): File? {
        return try {
            val dest = File(
                File(LibraryCatalog.directory(context), LocalCatalogActivator.SHARDS_DIR),
                "${PublishRelativeUrl.token(shard.shardId)}/${PublishRelativeUrl.token(shard.revision)}.sqlite"
            )
            if (dest.isFile &&
                dest.length() == shard.byteSize &&
                Sha256Hex.ofFile(dest).equals(shard.sha256, ignoreCase = true)
            ) {
                dest
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
