package com.andsi.airlyrics.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.andsi.airlyrics.core.time.PlaybackClockSnapshot
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.model.MediaSnapshotSequencer
import com.andsi.airlyrics.media.model.SessionQueueItem

object CurrentMediaReader {
    internal data class ControllerCandidate<T>(
        val value: T,
        val packageName: String,
        val hasMetadata: Boolean,
        val hasPlaybackState: Boolean,
        val hasMediaTitle: Boolean,
        val isPlaying: Boolean
    )

    fun getActiveControllers(context: Context): List<MediaController> {
        return try {
            val mediaSessionManager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MediaNotificationListenerService::class.java)
            mediaSessionManager.getActiveSessions(component)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readSelectedCurrentMedia(context: Context, selectedPackage: String?): CurrentMediaInfo? {
        if (selectedPackage.isNullOrBlank()) return null

        return selectedController(getActiveControllers(context), selectedPackage)
            ?.toCurrentMediaInfo()
    }

    fun selectedController(
        controllers: List<MediaController>,
        selectedPackage: String?
    ): MediaController? {
        return selectedCandidate(controllers.map { it.toCandidate() }, selectedPackage)?.value
    }

    fun selectedControllersByPackage(
        controllers: List<MediaController>
    ): Map<String, MediaController> {
        return controllers
            .filter { it.packageName.isNotBlank() }
            .groupBy { it.packageName }
            .mapNotNull { (packageName, packageControllers) ->
                selectedController(packageControllers, packageName)?.let { packageName to it }
            }
            .toMap()
    }

    fun bestController(
        controllers: List<MediaController>,
        selectedPackage: String?
    ): MediaController? {
        return bestCandidate(controllers.map { it.toCandidate() }, selectedPackage)?.value
    }

    fun currentMediaFromController(
        controller: MediaController,
        sessionEpoch: Long = 0L
    ): CurrentMediaInfo? {
        return controller.toCurrentMediaInfo(sessionEpoch)
    }

    private fun MediaController.hasMediaTitle(): Boolean {
        return metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.isNotBlank()
            ?: false
    }

    private fun MediaController.toCandidate(): ControllerCandidate<MediaController> {
        return ControllerCandidate(
            value = this,
            packageName = packageName,
            hasMetadata = metadata != null,
            hasPlaybackState = playbackState != null,
            hasMediaTitle = hasMediaTitle(),
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        )
    }

    internal fun <T> selectedCandidate(
        candidates: List<ControllerCandidate<T>>,
        selectedPackage: String?
    ): ControllerCandidate<T>? {
        if (selectedPackage.isNullOrBlank()) return null

        val selectedCandidates = candidates.filter {
            it.packageName == selectedPackage &&
                (it.hasMetadata || it.hasPlaybackState)
        }

        return selectedCandidates.firstOrNull { it.hasMediaTitle && it.isPlaying }
            ?: selectedCandidates.firstOrNull { it.hasMediaTitle }
            ?: selectedCandidates.firstOrNull { it.isPlaying }
            ?: selectedCandidates.firstOrNull { it.hasMetadata }
            ?: selectedCandidates.firstOrNull()
    }

    internal fun <T> bestCandidate(
        candidates: List<ControllerCandidate<T>>,
        selectedPackage: String?
    ): ControllerCandidate<T>? {
        val usableCandidates = candidates.filter { it.hasMetadata || it.hasPlaybackState }
        return selectedCandidate(usableCandidates, selectedPackage)
            ?: usableCandidates.firstOrNull { it.hasMediaTitle && it.isPlaying }
            ?: usableCandidates.firstOrNull { it.hasMediaTitle }
            ?: usableCandidates.firstOrNull { it.isPlaying }
            ?: usableCandidates.firstOrNull { it.hasMetadata }
            ?: usableCandidates.firstOrNull()
    }

    fun MediaController.toCurrentMediaInfo(sessionEpoch: Long = 0L): CurrentMediaInfo? {
        val metadata = this.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        if (title.isBlank()) return null

        val artistRaw = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val albumArtistRaw = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val state = this.playbackState
        val positionUnknown = state == null ||
            state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val positionBase = state?.position
            ?.takeUnless { it == PlaybackState.PLAYBACK_POSITION_UNKNOWN }
        val durationKnown = metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)

        return CurrentMediaInfo(
            sourcePackage = this.packageName,
            title = title,
            artist = artistRaw ?: albumArtistRaw.orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = if (durationKnown) metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) else 0L,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = estimatedPositionMs(state),
            snapshotSequence = MediaSnapshotSequencer.next(),
            albumArtist = albumArtistRaw,
            genre = metadata.getString(MediaMetadata.METADATA_KEY_GENRE),
            trackNumber = metadata.optionalInt(MediaMetadata.METADATA_KEY_TRACK_NUMBER),
            discNumber = metadata.optionalInt(MediaMetadata.METADATA_KEY_DISC_NUMBER),
            durationKnown = durationKnown,
            positionKnown = !positionUnknown,
            positionBaseMs = positionBase,
            positionAnchorElapsedRealtimeMs = state?.lastPositionUpdateTime?.takeIf { it > 0L },
            playbackSpeed = state?.playbackSpeed,
            playbackState = state?.state,
            sessionEpoch = sessionEpoch,
            queueItemId = state?.activeQueueItemId
                ?.takeUnless { it == MediaSession.QueueItem.UNKNOWN_ID.toLong() },
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            queue = sessionQueue(this)
        )
    }

    private fun sessionQueue(controller: MediaController): List<SessionQueueItem> {
        val items = runCatching { controller.queue }.getOrNull().orEmpty()
        return items.map { item ->
            val description = item.description
            val extras = description.extras
            val duration = extras?.let { bundle ->
                if (bundle.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                    bundle.getLong(MediaMetadata.METADATA_KEY_DURATION)
                } else {
                    null
                }
            }
            SessionQueueItem(
                queueId = item.queueId,
                title = description.title?.toString()?.takeIf { it.isNotBlank() },
                artist = description.subtitle?.toString()?.takeIf { it.isNotBlank() },
                album = description.description?.toString()?.takeIf { it.isNotBlank() },
                durationMs = duration?.takeIf { it > 0L },
                durationKnown = duration != null && duration > 0L,
                trackNumber = extras?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_TRACK_NUMBER) }
                    ?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER)?.toInt(),
                discNumber = extras?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DISC_NUMBER) }
                    ?.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER)?.toInt(),
                mediaId = description.mediaId
            )
        }
    }

    private fun MediaMetadata.optionalInt(key: String): Int? {
        if (!containsKey(key)) return null
        return getLong(key).toInt()
    }

    fun estimatedPositionMs(state: PlaybackState?): Long {
        return estimatedPositionMs(state, SystemClock.elapsedRealtime())
    }

    internal fun estimatedPositionMs(state: PlaybackState?, elapsedRealtimeMs: Long): Long {
        return playbackClockSnapshot(state).positionAt(elapsedRealtimeMs).positionMs ?: 0L
    }

    internal fun playbackClockSnapshot(state: PlaybackState?): PlaybackClockSnapshot {
        if (state == null || state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            return PlaybackClockSnapshot.Unknown
        }
        return PlaybackClockSnapshot(
            positionBaseMs = state.position,
            positionKnown = true,
            anchorElapsedRealtimeMs = state.lastPositionUpdateTime.takeIf { it > 0L },
            playbackSpeed = state.playbackSpeed,
            isPlaying = state.state == PlaybackState.STATE_PLAYING,
            durationMs = null,
            durationKnown = false
        )
    }
}
