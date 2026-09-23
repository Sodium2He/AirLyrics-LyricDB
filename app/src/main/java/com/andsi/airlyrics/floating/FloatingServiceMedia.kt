package com.andsi.airlyrics.floating

import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaNotificationListenerService
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toPlaybackClockSnapshot
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.lyrics.catalog.LibraryCatalog
import com.andsi.airlyrics.lyrics.catalog.LyricsPrefetchCache
import com.andsi.airlyrics.lyrics.catalog.PrefetchQueueItem
import com.andsi.airlyrics.lyrics.catalog.QueuePrefetchPlanner
import com.andsi.airlyrics.lyrics.catalog.TrackObservation
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import java.util.concurrent.Executors

internal fun FloatingLyricsService.shouldObserveSelectedMedia(): Boolean {
    return isWindowControllerReady() &&
        (windowController.isVisible ||
            ((autoHiddenForPause || autoHiddenForDisplayScope) &&
                QuickFloatingStore.isDesiredVisible(this))) &&
        !selectedSourcePackage.isNullOrBlank()
}

internal fun FloatingLyricsService.startSelectedMediaObservation() {
    if (!shouldObserveSelectedMedia()) {
        stopSelectedMediaObservation()
        return
    }

    refreshSelectedCurrentMediaInfo()
    scheduleSelectedCurrentMediaInfoRefresh()
}

internal fun FloatingLyricsService.stopSelectedMediaObservation() {
    syncHandler.removeCallbacks(currentMediaRefreshRunnable)
}

internal fun FloatingLyricsService.scheduleSelectedCurrentMediaInfoRefresh() {
    syncHandler.removeCallbacks(currentMediaRefreshRunnable)
    if (shouldObserveSelectedMedia()) {
        syncHandler.postDelayed(
            currentMediaRefreshRunnable,
            FloatingLyricsService.CURRENT_MEDIA_REFRESH_INTERVAL_MS
        )
    }
}

internal fun FloatingLyricsService.refreshSelectedCurrentMediaInfo() {
    if (!shouldObserveSelectedMedia()) return

    val media = readSelectedCurrentMediaInfo()
    if (media != null) {
        applyCurrentMediaInfo(media)
    } else {
        selectedSourcePackage?.let(::handleMediaSourceLost)
    }
}

internal fun FloatingLyricsService.applyCurrentMediaInfo(media: CurrentMediaInfo): Boolean {
    if (media.title.isBlank()) return false
    if (!shouldAcceptMediaUpdate(media.sourcePackage)) return false
    if (!mediaSnapshotGate.markAcceptedIfFresh(media)) return false

    currentMedia = media

    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0

    val snapshot = media.toPlaybackClockSnapshot().let { clock ->
        if (clock.isPlaying &&
            clock.positionKnown &&
            (clock.anchorElapsedRealtimeMs == null || clock.anchorElapsedRealtimeMs <= 0L)
        ) {
            clock.copy(anchorElapsedRealtimeMs = SystemClock.elapsedRealtime())
        } else {
            clock
        }
    }
    renderer.updateClock(snapshot)
    renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media.toSongIdentity()))
    applyAutoHideWhenPaused()

    val playbackLyricsKey = media.playbackLyricsKey()
    refreshQueuePrefetch(media)
    if (automaticOnlineLookupSuppressedSong != null &&
        !isAutomaticOnlineLookupSuppressed(media)
    ) {
        automaticOnlineLookupSuppressedSong = null
    }
    if (playbackLyricsKey == lastPlaybackLyricsKey) {
        renderer.tick()
        return true
    }

    val lookupRequestKey = media.lyricsLookupRequestKey()
    lastPlaybackLyricsKey = playbackLyricsKey
    activeLyricsLookupRequestKey = lookupRequestKey
    loadLyricsForSong(media = media, lookupRequestKey = lookupRequestKey)

    return true
}

internal fun CurrentMediaInfo.playbackLyricsKey(): PlaybackLyricsKey {
    val metadataKey = LyricsPrefetchCache().key(toLibraryObservation(""))
    // Polling has no observer epoch; callbacks do. This is not a track change.
    return PlaybackLyricsKey("${sourcePackage.length}:$sourcePackage|$metadataKey")
}

internal fun CurrentMediaInfo.lyricsLookupRequestKey(nonce: String? = null): LyricsLookupRequestKey {
    val playbackKey = playbackLyricsKey()
    return LyricsLookupRequestKey(
        if (nonce == null) playbackKey.value else "${playbackKey.value}|$nonce"
    )
}

internal fun FloatingLyricsService.scheduleCurrentMediaRestore() {
    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0
    syncHandler.post(mediaRestoreRunnable)
}

internal fun FloatingLyricsService.restoreCurrentMediaOrRetry() {
    if (!currentMedia.isEmpty) return
    if (!QuickFloatingStore.isDesiredVisible(this)) return
    if (!isWindowControllerReady() || !windowController.isVisible) return
    if (selectedSourcePackage.isNullOrBlank()) return

    val restored = readSelectedCurrentMediaInfo()
        ?.let(::applyCurrentMediaInfo)
        ?: false

    if (restored) return

    if (mediaRestoreAttempt == 0) {
        requestNotificationListenerRebind()
    }

    val delay = FloatingLyricsService.MEDIA_RESTORE_RETRY_DELAYS_MS
        .getOrNull(mediaRestoreAttempt++)
        ?: return

    syncHandler.postDelayed(mediaRestoreRunnable, delay)
}

internal fun FloatingLyricsService.readSelectedCurrentMediaInfo(): CurrentMediaInfo? {
    return CurrentMediaReader.readSelectedCurrentMedia(
        context = this,
        selectedPackage = selectedSourcePackage
    )
}

internal fun FloatingLyricsService.requestNotificationListenerRebind() {
    val component = ComponentName(
        this,
        MediaNotificationListenerService::class.java
    )

    runCatching {
        NotificationListenerService.requestRebind(component)
    }
}

internal fun FloatingLyricsService.handleMediaSourceLost(sourcePackage: String) {
    if (sourcePackage.isBlank()) return
    if (sourcePackage != selectedSourcePackage) return
    if (currentMedia.isEmpty || currentMedia.sourcePackage != sourcePackage) return

    renderer.freezePlayback()
    currentMedia = currentMedia.copy(
        isPlaying = false,
        positionMs = renderer.getEstimatedPositionMs()
    )
    renderer.refresh()
    applyAutoHideWhenPaused()
}

internal fun FloatingLyricsService.selectMediaSource(packageName: String?) {
    selectedSourcePackage = packageName
    MediaSourceStore.saveSelectedPackage(this, packageName)
    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0

    clearLyricsState(
        getString(
            if (packageName == null) {
                R.string.ui_no_media_source_status
            } else {
                R.string.ui_media_source_waiting_status
            }
        )
    )

    if (packageName == null) {
        stopSelectedMediaObservation()
        return
    }

    if (isWindowControllerReady() && windowController.isVisible) {
        startSelectedMediaObservation()
        if (currentMedia.isEmpty) {
            scheduleCurrentMediaRestore()
        }
    }
}

private fun FloatingLyricsService.shouldAcceptMediaUpdate(sourcePackage: String): Boolean {
    if (sourcePackage.isBlank()) return false

    val selectedPackage = selectedSourcePackage ?: return false
    return sourcePackage == selectedPackage
}

internal fun CurrentMediaInfo.toLibraryObservation(observationToken: String): TrackObservation {
    return TrackObservation(
        title = title.takeIf { it.isNotBlank() },
        artist = artist.takeIf { it.isNotBlank() },
        album = album.takeIf { it.isNotBlank() },
        albumArtist = albumArtist?.takeIf { it.isNotBlank() },
        durationMs = durationMs.takeIf { durationKnown && it > 0L },
        durationKnown = durationKnown && durationMs > 0L,
        trackNumber = trackNumber,
        discNumber = discNumber,
        genre = genre?.takeIf { it.isNotBlank() },
        observationToken = observationToken
    )
}

internal fun CurrentMediaInfo.toPrefetchQueueItems(): List<PrefetchQueueItem> {
    return queue.map { item ->
        PrefetchQueueItem(
            queueId = item.queueId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            albumArtist = item.albumArtist,
            durationMs = item.durationMs,
            durationKnown = item.durationKnown,
            trackNumber = item.trackNumber,
            discNumber = item.discNumber
        )
    }
}

internal fun FloatingLyricsService.refreshQueuePrefetch(media: CurrentMediaInfo) {
    val items = media.toPrefetchQueueItems()
    val fingerprint = QueuePrefetchPlanner.fingerprint(
        sessionEpoch = media.sessionEpoch,
        items = items,
        activeQueueItemId = media.queueItemId
    )
    if (fingerprint != queueFingerprint) {
        lyricsPrefetchCache.clear()
        queueFingerprint = fingerprint
    }
    scheduleQueuePrefetch(media, items)
}

internal fun FloatingLyricsService.scheduleQueuePrefetch(
    media: CurrentMediaInfo,
    items: List<PrefetchQueueItem>
) {
    val nextQueue = QueuePrefetchPlanner.nextFromQueue(items, media.queueItemId)
    val currentObservation = media.toLibraryObservation("prefetch-current")
    prefetchExecutor.execute {
        LibraryCatalog.openIfPresent(this)?.use { catalog ->
            val targets = if (nextQueue.isNotEmpty()) {
                nextQueue.map { it.toObservation("prefetch-queue") }
            } else {
                catalog.albumNeighbors(currentObservation).map { ref ->
                    TrackObservation(
                        title = ref.title,
                        artist = ref.artist,
                        album = ref.album,
                        albumArtist = ref.albumArtist,
                        durationMs = ref.durationMs,
                        durationKnown = ref.durationMs != null && ref.durationMs > 0L,
                        trackNumber = ref.trackNumber,
                        discNumber = ref.disc
                    )
                }
            }
            targets.forEach { observation ->
                if (lyricsPrefetchCache.get(observation) != null) return@forEach
                when (val outcome = catalog.lookup(observation)) {
                    is com.andsi.airlyrics.lyrics.catalog.CatalogLookupOutcome.Finish -> {
                        outcome.result?.let { lyricsPrefetchCache.put(observation, it) }
                    }
                    else -> Unit
                }
            }
        }
    }
}
