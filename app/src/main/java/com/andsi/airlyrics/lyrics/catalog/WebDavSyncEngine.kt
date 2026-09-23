package com.andsi.airlyrics.lyrics.catalog

import java.io.File

class WebDavSyncEngine(
    private val wifiGate: () -> WifiSyncVerdict,
    private val remote: RemoteLibraryClient,
    private val stateStore: SyncAcceptanceStore,
    private val activator: (File) -> LocalCatalogActivateResult,
    private val currentCatalog: () -> CatalogStore.CatalogMeta?,
    private val existingVerifiedShard: (ManifestShard) -> File?,
    private val stagingRoot: File,
    private val cancelled: () -> Boolean = { false },
    private val force: Boolean = false
) {
    private var step = "START"
    private var resource = MANIFEST_RELATIVE
    private var httpStatus: Int? = null
    fun sync(): SyncOutcome {
        return try {
            val outcome = syncOnce(retryOnManifestChange = true)
            if (outcome is SyncOutcome.Rejected && outcome.reason != SyncRejectReason.UNCHANGED) {
                outcome.copy(detail = outcome.detail ?: diagnostic())
            } else outcome
        } catch (_: CancelledSync) {
            SyncOutcome.Rejected(SyncRejectReason.CANCELLED)
        } catch (error: Exception) {
            val cause = generateSequence<Throwable>(error) { it.cause }.take(8).last()
            SyncOutcome.Rejected(SyncRejectReason.PROBE_FAILED, diagnostic() + " | " + cause.javaClass.simpleName)
        }
    }

    private fun diagnostic(): String = "$step | $resource" + (httpStatus?.let { " | HTTP $it" } ?: "")

    private fun probe(relative: String): RemoteProbe {
        step = "PROBE"
        resource = relative
        httpStatus = null
        return remote.probe(relative).also { httpStatus = it.statusCode }
    }

    private fun syncOnce(retryOnManifestChange: Boolean): SyncOutcome {
        when (wifiGate()) {
            WifiSyncVerdict.WIFI -> Unit
            WifiSyncVerdict.VPN_UNCONFIRMED ->
                return SyncOutcome.Rejected(SyncRejectReason.VPN_UNCONFIRMED)
            WifiSyncVerdict.NOT_WIFI,
            WifiSyncVerdict.UNAVAILABLE ->
                return SyncOutcome.Rejected(SyncRejectReason.NOT_WIFI)
        }

        val accepted = stateStore.load()
        val catalog = currentCatalog()
        val manifestProbe = probe(MANIFEST_RELATIVE)
        val manifestCheck = validateProbe(
            probe = manifestProbe,
            expectedSize = null,
            previousLastModifiedMs = accepted?.manifestLastModifiedMs
        )
        if (manifestCheck != null) return SyncOutcome.Rejected(manifestCheck)
        throwIfCancelled()

        val manifestDownload = downloadWithMatch(MANIFEST_RELATIVE, dest = null, probe = manifestProbe)
        if (!manifestDownload.ok || manifestDownload.bytes == null) {
            return SyncOutcome.Rejected(statusReason(manifestDownload.statusCode))
        }
        val manifestBytes = manifestDownload.bytes
        val manifestSha = Sha256Hex.ofBytes(manifestBytes)
        step = "PARSE_MANIFEST"
        val manifest = try {
            PublishManifestParser.parse(manifestBytes.decodeToString())
        } catch (_: Exception) {
            return SyncOutcome.Rejected(SyncRejectReason.PROBE_FAILED)
        }

        val identity = validateIdentity(manifest, catalog, accepted, manifestSha)
        if (identity != null) return identity
        throwIfCancelled()

        val shardProbes = LinkedHashMap<String, RemoteProbe>()
        for (shard in manifest.shards) {
            throwIfCancelled()
            val previous = accepted?.shards?.get(shard.shardId)
            val probe = probe(shard.relativeUrl)
            val probeReason = validateProbe(
                probe = probe,
                expectedSize = shard.byteSize,
                previousLastModifiedMs = previous?.lastModifiedMs
            )
            if (probeReason != null) {
                return SyncOutcome.Rejected(
                    if (probeReason == SyncRejectReason.PROBE_FAILED &&
                        (probe.statusCode == 404 || probe.statusCode == 410)
                    ) {
                        SyncRejectReason.SHARD_MISSING
                    } else if (probeReason == SyncRejectReason.LAST_MODIFIED_OLDER) {
                        SyncRejectReason.SHARD_OLDER
                    } else {
                        probeReason
                    }
                )
            }
            shardProbes[shard.shardId] = probe
        }

        resetStaging()
        val stagingManifest = File(stagingRoot, LocalCatalogActivator.MANIFEST_FILE)
        stagingManifest.writeBytes(manifestBytes)

        var downloaded = 0
        var reused = 0
        for (shard in manifest.shards) {
            throwIfCancelled()
            val dest = PublishRelativeUrl.resolve(stagingRoot, shard.relativeUrl)
            dest.parentFile?.mkdirs()
            val local = if (force) null else existingVerifiedShard(shard)
            if (local != null) {
                local.copyTo(dest, overwrite = true)
                reused += 1
                continue
            }
            val probe = shardProbes.getValue(shard.shardId)
            val download = downloadWithMatch(shard.relativeUrl, dest, probe)
            if (!download.ok || download.file == null || !download.file.isFile) {
                return SyncOutcome.Rejected(
                    if (download.statusCode == 404 || download.statusCode == 410) {
                        SyncRejectReason.SHARD_MISSING
                    } else {
                        SyncRejectReason.SHARD_INVALID
                    }
                )
            }
            if (download.file.length() != shard.byteSize ||
                !Sha256Hex.ofFile(download.file).equals(shard.sha256, ignoreCase = true)
            ) {
                return SyncOutcome.Rejected(SyncRejectReason.HASH_MISMATCH)
            }
            downloaded += 1
        }

        throwIfCancelled()
        val reprobe = probe(MANIFEST_RELATIVE)
        val redownload = downloadWithMatch(MANIFEST_RELATIVE, dest = null, probe = reprobe)
        if (!redownload.ok || redownload.bytes == null) {
            return SyncOutcome.Rejected(statusReason(redownload.statusCode))
        }
        val finalSha = Sha256Hex.ofBytes(redownload.bytes)
        if (finalSha != manifestSha) {
            return if (retryOnManifestChange) {
                syncOnce(retryOnManifestChange = false)
            } else {
                SyncOutcome.Rejected(SyncRejectReason.MANIFEST_CHANGED)
            }
        }

        step = "ACTIVATE"
        httpStatus = null
        val activated = activator(stagingRoot)
        return when (activated) {
            is LocalCatalogActivateResult.Activated -> {
                stateStore.save(
                    AcceptedSyncState(
                        libraryId = manifest.libraryId,
                        generation = manifest.generation,
                        manifestSha256 = manifestSha,
                        manifestLastModifiedMs = manifestProbe.lastModifiedEpochMs!!,
                        manifestEtag = manifestProbe.etag,
                        shards = manifest.shards.associate { shard ->
                            val probe = shardProbes.getValue(shard.shardId)
                            shard.shardId to AcceptedShardState(
                                shardId = shard.shardId,
                                revision = shard.revision,
                                relativeUrl = shard.relativeUrl,
                                lastModifiedMs = probe.lastModifiedEpochMs!!,
                                etag = probe.etag,
                                sha256 = shard.sha256
                            )
                        }
                    )
                )
                SyncOutcome.Activated(
                    libraryId = activated.libraryId,
                    generation = activated.generation,
                    downloadedShards = downloaded,
                    reusedShards = reused
                )
            }
            is LocalCatalogActivateResult.Failed -> SyncOutcome.Rejected(
                when (activated.reason) {
                    LocalCatalogActivateResult.Reason.LIBRARY_MISMATCH ->
                        SyncRejectReason.LIBRARY_MISMATCH
                    LocalCatalogActivateResult.Reason.GENERATION_OLDER ->
                        SyncRejectReason.GENERATION_OLDER
                    LocalCatalogActivateResult.Reason.SHARD_MISSING ->
                        SyncRejectReason.SHARD_MISSING
                    LocalCatalogActivateResult.Reason.SHARD_HASH,
                    LocalCatalogActivateResult.Reason.SHARD_SIZE ->
                        SyncRejectReason.HASH_MISMATCH
                    LocalCatalogActivateResult.Reason.MANIFEST_INVALID,
                    LocalCatalogActivateResult.Reason.MANIFEST_MISSING ->
                        SyncRejectReason.PROBE_FAILED
                    LocalCatalogActivateResult.Reason.ACTIVATE_FAILED ->
                        SyncRejectReason.ACTIVATE_FAILED
                }
            )
        }
    }

    private fun validateIdentity(
        manifest: PublishManifest,
        catalog: CatalogStore.CatalogMeta?,
        accepted: AcceptedSyncState?,
        manifestSha: String
    ): SyncOutcome? {
        if (force) return null
        if (catalog != null && catalog.libraryId != manifest.libraryId) {
            return SyncOutcome.Rejected(SyncRejectReason.LIBRARY_MISMATCH)
        }
        if (accepted != null && accepted.libraryId != manifest.libraryId) {
            return SyncOutcome.Rejected(SyncRejectReason.LIBRARY_MISMATCH)
        }
        val currentGeneration = catalog?.generation ?: accepted?.generation
        if (currentGeneration != null && manifest.generation < currentGeneration) {
            return SyncOutcome.Rejected(SyncRejectReason.GENERATION_OLDER)
        }
        if (currentGeneration != null &&
            manifest.generation == currentGeneration &&
            accepted != null &&
            accepted.manifestSha256 != manifestSha
        ) {
            return SyncOutcome.Rejected(SyncRejectReason.GENERATION_SAME_DIFFERENT)
        }
        if (!force && currentGeneration != null &&
            manifest.generation == currentGeneration &&
            (accepted == null || accepted.manifestSha256 == manifestSha)
        ) {
            return SyncOutcome.Rejected(SyncRejectReason.UNCHANGED)
        }
        return null
    }

    private fun validateProbe(
        probe: RemoteProbe,
        expectedSize: Long?,
        previousLastModifiedMs: Long?
    ): SyncRejectReason? {
        if (probe.statusCode == 401 || probe.statusCode == 403) {
            return SyncRejectReason.PROBE_FAILED
        }
        if (probe.statusCode !in 200..299) {
            return statusReason(probe.statusCode)
        }
        if (probe.isCollection) return SyncRejectReason.PROBE_FAILED
        val lastModified = probe.lastModifiedEpochMs
            ?: return SyncRejectReason.LAST_MODIFIED_UNTRUSTED
        if (!force && previousLastModifiedMs != null && lastModified < previousLastModifiedMs) {
            return SyncRejectReason.LAST_MODIFIED_OLDER
        }
        if (expectedSize != null && probe.contentLength != null && probe.contentLength != expectedSize) {
            return SyncRejectReason.SHARD_INVALID
        }
        return null
    }

    private fun downloadWithMatch(
        relativeUrl: String,
        dest: File?,
        probe: RemoteProbe
    ): RemoteDownload {
        step = "GET"
        resource = relativeUrl
        httpStatus = null
        val headers = LinkedHashMap<String, String>()
        probe.strongEtag?.let { headers["If-Match"] = it }
        val download = remote.download(relativeUrl, dest, headers)
        httpStatus = download.statusCode
        if (download.statusCode == 412) {
            return remote.download(relativeUrl, dest, emptyMap())
        }
        return download
    }

    private fun resetStaging() {
        if (stagingRoot.exists()) {
            stagingRoot.deleteRecursively()
        }
        stagingRoot.mkdirs()
    }

    private fun throwIfCancelled() {
        if (cancelled()) throw CancelledSync
    }

    private fun statusReason(statusCode: Int): SyncRejectReason {
        return if (statusCode == 404 || statusCode == 410) {
            SyncRejectReason.SHARD_MISSING
        } else {
            SyncRejectReason.PROBE_FAILED
        }
    }

    private object CancelledSync : RuntimeException()

    companion object {
        const val MANIFEST_RELATIVE = "manifest.json"
    }
}
