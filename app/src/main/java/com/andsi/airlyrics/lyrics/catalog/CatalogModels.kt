package com.andsi.airlyrics.lyrics.catalog

/**
 * Playback observation used for library matching. Blank strings are missing values,
 * not reliable empty metadata. Path, filename, and persistent media IDs are not fields.
 */
data class TrackObservation(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val durationKnown: Boolean = false,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val observationToken: String = "",
    val catalogGeneration: Long? = null
) {
    companion object {
        fun fromLegacy(
            title: String,
            artist: String,
            album: String,
            durationMs: Long
        ): TrackObservation {
            return TrackObservation(
                title = title.takeIf { it.isNotBlank() },
                artist = artist.takeIf { it.isNotBlank() },
                album = album.takeIf { it.isNotBlank() },
                durationMs = durationMs.takeIf { it > 0L },
                durationKnown = durationMs > 0L
            )
        }
    }
}

data class CatalogTrackRef(
    val shardId: String,
    val revision: String,
    val trackId: Long,
    val localShardPath: String,
    val title: String?,
    val artist: String?,
    val artists: List<String>,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val disc: Int?,
    val trackNumber: Int?,
    val genre: String?,
    val lyricsStatus: String,
    val lyricsTextHash: String?,
    val matchedViaAlias: Boolean = false
)

data class CatalogCandidate(
    val ref: CatalogTrackRef
)

sealed class MatchDecision {
    data class Accepted(val candidate: CatalogTrackRef, val reason: String) : MatchDecision()

    data class UniqueLyrics(
        val candidates: List<CatalogTrackRef>,
        val lyricsTextHash: String,
        val reason: String
    ) : MatchDecision()

    data class Ambiguous(
        val candidates: List<CatalogTrackRef>,
        val reason: String
    ) : MatchDecision()

    data class Unmatched(val reason: String) : MatchDecision()

    data class UnresolvedTruncation(val recalled: Int, val limit: Int) : MatchDecision()
}

data class PublishManifest(
    val schemaVersion: Int,
    val libraryId: String,
    val generation: Long,
    val builtAtUtc: Long,
    val shards: List<ManifestShard>
)

data class ManifestShard(
    val shardId: String,
    val bucket: String,
    val bucketKind: String,
    val revision: String,
    val relativeUrl: String,
    val sha256: String,
    val byteSize: Long,
    val trackCount: Int
)

data class ShardLyricRow(
    val tagKind: String,
    val language: String?,
    val description: String?,
    val variantOrder: Int,
    val format: String?,
    val rawText: String?,
    val textHash: String?,
    val diagnostic: String?
)

sealed class CatalogLookupOutcome {
    data class Finish(
        val result: com.andsi.airlyrics.lyrics.LyricsProviderResult?,
        val status: String = if (result == null) "unmatched" else "matched"
    ) : CatalogLookupOutcome()
    data object Continue : CatalogLookupOutcome()
}

internal fun interface CatalogLyricsLookup {
    fun lookup(
        context: android.content.Context,
        observation: TrackObservation
    ): CatalogLookupOutcome
}

class CatalogLookupException(val status: String) : Exception("Library catalog: $status")

internal object NoCatalogLyricsLookup : CatalogLyricsLookup {
    override fun lookup(
        context: android.content.Context,
        observation: TrackObservation
    ): CatalogLookupOutcome = CatalogLookupOutcome.Continue
}
