package com.andsi.airlyrics.lyrics.catalog

import java.io.File

enum class WifiSyncVerdict {
    WIFI,
    NOT_WIFI,
    VPN_UNCONFIRMED,
    UNAVAILABLE
}

enum class SyncRejectReason {
    NOT_WIFI,
    VPN_UNCONFIRMED,
    CANCELLED,
    NO_URL,
    PROBE_FAILED,
    LAST_MODIFIED_UNTRUSTED,
    LAST_MODIFIED_OLDER,
    GENERATION_OLDER,
    GENERATION_SAME_DIFFERENT,
    LIBRARY_MISMATCH,
    SHARD_MISSING,
    SHARD_OLDER,
    SHARD_INVALID,
    MANIFEST_CHANGED,
    HASH_MISMATCH,
    ACTIVATE_FAILED,
    UNCHANGED
}

sealed class SyncOutcome {
    data class Activated(
        val libraryId: String,
        val generation: Long,
        val downloadedShards: Int,
        val reusedShards: Int
    ) : SyncOutcome()

    data class Rejected(val reason: SyncRejectReason, val detail: String? = null) : SyncOutcome()
}

data class RemoteProbe(
    val statusCode: Int,
    val lastModifiedHttp: String? = null,
    val etag: String? = null,
    val contentLength: Long? = null,
    val isCollection: Boolean = false
) {
    val lastModifiedEpochMs: Long?
        get() = HttpDate.parseToEpochMs(lastModifiedHttp)

    val okFile: Boolean
        get() = statusCode in 200..299 && !isCollection

    val strongEtag: String?
        get() = etag?.takeIf { HttpDate.isStrongEtag(it) }
}

data class RemoteDownload(
    val statusCode: Int,
    val bytes: ByteArray? = null,
    val file: File? = null,
    val etag: String? = null,
    val lastModifiedHttp: String? = null
) {
    val ok: Boolean
        get() = statusCode in 200..299
}

data class AcceptedShardState(
    val shardId: String,
    val revision: String,
    val relativeUrl: String,
    val lastModifiedMs: Long,
    val etag: String?,
    val sha256: String
)

data class AcceptedSyncState(
    val libraryId: String,
    val generation: Long,
    val manifestSha256: String,
    val manifestLastModifiedMs: Long,
    val manifestEtag: String?,
    val shards: Map<String, AcceptedShardState>
)

interface RemoteLibraryClient {
    fun probe(relativeUrl: String, extraHeaders: Map<String, String> = emptyMap()): RemoteProbe

    fun download(
        relativeUrl: String,
        dest: File?,
        extraHeaders: Map<String, String> = emptyMap()
    ): RemoteDownload
}

interface SyncAcceptanceStore {
    fun load(): AcceptedSyncState?
    fun save(state: AcceptedSyncState)
}

object WifiCapabilityEvaluator {
    fun verdict(
        transports: Set<String>?,
        hasInternet: Boolean
    ): WifiSyncVerdict {
        if (transports == null) return WifiSyncVerdict.UNAVAILABLE
        val wifi = "WIFI" in transports
        val vpn = "VPN" in transports
        if (vpn && !wifi) return WifiSyncVerdict.VPN_UNCONFIRMED
        if (!wifi) return WifiSyncVerdict.NOT_WIFI
        if (!hasInternet) return WifiSyncVerdict.UNAVAILABLE
        return WifiSyncVerdict.WIFI
    }
}
