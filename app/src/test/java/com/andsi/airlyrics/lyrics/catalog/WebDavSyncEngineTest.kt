package com.andsi.airlyrics.lyrics.catalog

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebDavSyncEngineTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var staging: File
    private lateinit var remote: FakeRemote
    private lateinit var state: MemoryAcceptanceStore
    private val activations = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "sync-${System.nanoTime()}").also { it.mkdirs() }
        staging = File(root, "staging")
        remote = FakeRemote()
        state = MemoryAcceptanceStore()
        File(context.filesDir, LibraryCatalog.LIBRARY_DIR).deleteRecursively()
        activations.clear()
    }

    @Test
    fun notWifi_doesNotProbeRemote() {
        publishRemote(generation = 1L)
        val outcome = engine(wifi = WifiSyncVerdict.NOT_WIFI).sync()
        assertEquals(SyncRejectReason.NOT_WIFI, (outcome as SyncOutcome.Rejected).reason)
        assertTrue(remote.probes.isEmpty())
        assertTrue(remote.downloads.isEmpty())
    }

    @Test
    fun vpnWithoutWifi_doesNotProbeRemote() {
        publishRemote(generation = 1L)
        val outcome = engine(wifi = WifiSyncVerdict.VPN_UNCONFIRMED).sync()
        assertEquals(SyncRejectReason.VPN_UNCONFIRMED, (outcome as SyncOutcome.Rejected).reason)
        assertTrue(remote.probes.isEmpty())
    }

    @Test
    fun missingLastModified_stopsWithoutActivating() {
        publishRemote(generation = 1L, lastModified = null)
        val outcome = engine().sync()
        assertEquals(SyncRejectReason.LAST_MODIFIED_UNTRUSTED, (outcome as SyncOutcome.Rejected).reason)
        assertTrue(activations.isEmpty())
        assertTrue(LibraryCatalog.openIfPresent(context) == null)
    }

    @Test
    fun olderLastModified_keepsPreviousGeneration() {
        val first = activateLocal(generation = 1L, title = "Keep", lyrics = "[00:01.00]keep")
        state.save(
            AcceptedSyncState(
                libraryId = "lib",
                generation = 1L,
                manifestSha256 = Sha256Hex.ofBytes(first.manifestBytes),
                manifestLastModifiedMs = HttpDate.parseToEpochMs(NEWER)!!,
                manifestEtag = "\"one\"",
                shards = mapOf(
                    "s1" to AcceptedShardState(
                        shardId = "s1",
                        revision = "r1",
                        relativeUrl = "shards/s1/r1.sqlite",
                        lastModifiedMs = HttpDate.parseToEpochMs(NEWER)!!,
                        etag = "\"one\"",
                        sha256 = Sha256Hex.ofFile(first.shardFile)
                    )
                )
            )
        )
        publishRemote(
            generation = 2L,
            title = "New",
            lyrics = "[00:01.00]new",
            lastModified = OLDER
        )

        val outcome = engine().sync()
        assertEquals(SyncRejectReason.LAST_MODIFIED_OLDER, (outcome as SyncOutcome.Rejected).reason)
        assertEquals(1L, LibraryCatalog.openIfPresent(context)!!.use { it.currentGeneration() })
        assertKeepLyrics("Keep", "[00:01.00]keep")
    }

    @Test
    fun missingShard_isPartialPublishAndKeepsOld() {
        activateLocal(generation = 1L, title = "Keep", lyrics = "[00:01.00]keep")
        publishRemote(generation = 2L, title = "New", lyrics = "[00:01.00]new")
        remote.resources["shards/s1/r2.sqlite"]!!.status = 404

        val outcome = engine().sync()
        assertEquals(SyncRejectReason.SHARD_MISSING, (outcome as SyncOutcome.Rejected).reason)
        assertEquals(1L, LibraryCatalog.openIfPresent(context)!!.use { it.currentGeneration() })
        assertKeepLyrics("Keep", "[00:01.00]keep")
    }

    @Test
    fun disconnectDuringDownload_keepsOld() {
        activateLocal(generation = 1L, title = "Keep", lyrics = "[00:01.00]keep")
        publishRemote(generation = 2L, title = "New", lyrics = "[00:01.00]new")
        remote.failAfterOperations = 2

        val outcome = engine().sync()
        assertEquals(SyncRejectReason.PROBE_FAILED, (outcome as SyncOutcome.Rejected).reason)
        assertEquals(1L, LibraryCatalog.openIfPresent(context)!!.use { it.currentGeneration() })
        assertKeepLyrics("Keep", "[00:01.00]keep")
    }

    @Test
    fun cancelledMidSync_doesNotActivate() {
        publishRemote(generation = 1L)
        var probes = 0
        val outcome = engine(cancelled = { probes += 1; probes > 1 }).sync()
        assertEquals(SyncRejectReason.CANCELLED, (outcome as SyncOutcome.Rejected).reason)
        assertTrue(LibraryCatalog.openIfPresent(context) == null)
    }

    @Test
    fun olderGeneration_isRejected() {
        activateLocal(generation = 2L, title = "New", lyrics = "[00:01.00]new")
        publishRemote(generation = 1L, title = "Old", lyrics = "[00:01.00]old", revision = "r1")
        val outcome = engine().sync()
        assertEquals(SyncRejectReason.GENERATION_OLDER, (outcome as SyncOutcome.Rejected).reason)
        assertEquals(2L, LibraryCatalog.openIfPresent(context)!!.use { it.currentGeneration() })
    }

    @Test
    fun sameGenerationDifferentHash_isRejected() {
        val first = activateLocal(generation = 1L, title = "Keep", lyrics = "[00:01.00]keep")
        state.save(
            AcceptedSyncState(
                libraryId = "lib",
                generation = 1L,
                manifestSha256 = Sha256Hex.ofBytes(first.manifestBytes),
                manifestLastModifiedMs = HttpDate.parseToEpochMs(OLDER)!!,
                manifestEtag = "\"one\"",
                shards = emptyMap()
            )
        )
        publishRemote(generation = 1L, title = "Other", lyrics = "[00:01.00]other")
        val outcome = engine().sync()
        assertEquals(SyncRejectReason.GENERATION_SAME_DIFFERENT, (outcome as SyncOutcome.Rejected).reason)
        assertKeepLyrics("Keep", "[00:01.00]keep")
    }

    @Test
    fun libraryMismatch_isRejected() {
        activateLocal(generation = 1L, title = "Keep", lyrics = "[00:01.00]keep")
        publishRemote(generation = 2L, libraryId = "other")
        val outcome = engine().sync()
        assertEquals(SyncRejectReason.LIBRARY_MISMATCH, (outcome as SyncOutcome.Rejected).reason)
        assertEquals(1L, LibraryCatalog.openIfPresent(context)!!.use { it.currentGeneration() })
    }

    @Test
    fun successfulSync_activatesNewGeneration() {
        publishRemote(generation = 3L, title = "Hash Song", lyrics = "[00:01.00]hash")
        val outcome = engine().sync()
        val activated = outcome as SyncOutcome.Activated
        assertEquals(3L, activated.generation)
        assertEquals(1, activated.downloadedShards)
        assertKeepLyrics("Hash Song", "[00:01.00]hash")
        assertEquals(3L, state.load()!!.generation)
    }

    @Test
    fun deletedShardInNewManifest_isRemovedFromCatalog() {
        val firstShard = File(root, "first.sqlite")
        writePresentShard(firstShard, "s1", "r1", "Keep", "[00:01.00]keep")
        val secondShard = File(root, "second.sqlite")
        writePresentShard(secondShard, "s2", "r1", "Gone", "[00:01.00]gone")
        val firstPublish = File(root, "both")
        copyShard(firstPublish, firstShard, "s1", "r1")
        copyShard(firstPublish, secondShard, "s2", "r1")
        writeManifest(
            firstPublish,
            generation = 1L,
            shards = listOf(
                shardEntry("s1", "r1", File(firstPublish, "shards/s1/r1.sqlite")),
                shardEntry("s2", "r1", File(firstPublish, "shards/s2/r1.sqlite"))
            )
        )
        assertTrue(
            LocalCatalogActivator.activateFromPublishDirectory(context, firstPublish)
                is LocalCatalogActivateResult.Activated
        )

        publishRemote(generation = 2L, title = "Keep", lyrics = "[00:01.00]keep")
        val outcome = engine().sync()
        assertTrue(outcome is SyncOutcome.Activated)
        LibraryCatalog.openIfPresent(context)!!.use { catalog ->
            assertEquals(2L, catalog.currentGeneration())
            val gone = catalog.lookup(
                TrackObservation(
                    title = "Gone",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 180_000L,
                    durationKnown = true
                )
            )
            assertEquals(CatalogLookupOutcome.Continue, gone)
        }
        assertKeepLyrics("Keep", "[00:01.00]keep")
    }

    @Test
    fun wifiCapabilityEvaluator_requiresWifiAndRejectsVpnOnly() {
        assertEquals(
            WifiSyncVerdict.WIFI,
            WifiCapabilityEvaluator.verdict(setOf("WIFI"), hasInternet = true)
        )
        assertEquals(
            WifiSyncVerdict.WIFI,
            WifiCapabilityEvaluator.verdict(setOf("WIFI", "VPN"), hasInternet = true)
        )
        assertEquals(
            WifiSyncVerdict.VPN_UNCONFIRMED,
            WifiCapabilityEvaluator.verdict(setOf("VPN"), hasInternet = true)
        )
        assertEquals(
            WifiSyncVerdict.NOT_WIFI,
            WifiCapabilityEvaluator.verdict(setOf("CELLULAR"), hasInternet = true)
        )
        assertEquals(
            WifiSyncVerdict.NOT_WIFI,
            WifiCapabilityEvaluator.verdict(setOf("CELLULAR", "NOT_METERED"), hasInternet = true)
        )
    }

    private fun engine(
        wifi: WifiSyncVerdict = WifiSyncVerdict.WIFI,
        cancelled: () -> Boolean = { false }
    ): WebDavSyncEngine {
        return WebDavSyncEngine(
            wifiGate = { wifi },
            remote = remote,
            stateStore = state,
            activator = { dir ->
                val result = LocalCatalogActivator.activateFromPublishDirectory(context, dir)
                if (result is LocalCatalogActivateResult.Activated) {
                    activations += result.generation
                }
                result
            },
            currentCatalog = {
                LibraryCatalog.openIfPresent(context)?.use { it.currentMeta() }
            },
            existingVerifiedShard = { null },
            stagingRoot = staging,
            cancelled = cancelled
        )
    }

    private data class LocalPublish(
        val shardFile: File,
        val manifestBytes: ByteArray
    )

    private fun activateLocal(
        generation: Long,
        title: String,
        lyrics: String,
        revision: String = "r$generation"
    ): LocalPublish {
        val publish = File(root, "local-$generation")
        val shard = File(publish, "shards/s1/$revision.sqlite")
        writePresentShard(shard, "s1", revision, title, lyrics)
        writeManifest(
            publish,
            generation,
            listOf(shardEntry("s1", revision, shard))
        )
        val result = LocalCatalogActivator.activateFromPublishDirectory(context, publish)
        assertTrue(result is LocalCatalogActivateResult.Activated)
        return LocalPublish(shard, File(publish, "manifest.json").readBytes())
    }

    private fun publishRemote(
        generation: Long,
        title: String = "Song",
        lyrics: String = "[00:01.00]song",
        lastModified: String? = NEWER,
        libraryId: String = "lib",
        revision: String = "r$generation"
    ) {
        val shard = File(root, "remote-$generation.sqlite")
        writePresentShard(shard, "s1", revision, title, lyrics)
        val relative = "shards/s1/$revision.sqlite"
        val manifest = PublishManifestParser.encode(
            PublishManifest(
                schemaVersion = 1,
                libraryId = libraryId,
                generation = generation,
                builtAtUtc = 0L,
                shards = listOf(
                    ManifestShard(
                        shardId = "s1",
                        bucket = "Album/A/B",
                        bucketKind = "depth3",
                        revision = revision,
                        relativeUrl = relative,
                        sha256 = Sha256Hex.ofFile(shard),
                        byteSize = shard.length(),
                        trackCount = 1
                    )
                )
            )
        ).toByteArray()
        remote.put("manifest.json", manifest, lastModified)
        remote.put(relative, shard.readBytes(), lastModified)
    }

    private fun copyShard(publish: File, source: File, shardId: String, revision: String) {
        val dest = File(publish, "shards/$shardId/$revision.sqlite")
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
    }

    private fun shardEntry(shardId: String, revision: String, file: File): ManifestShard {
        return ManifestShard(
            shardId = shardId,
            bucket = "Album/A/B",
            bucketKind = "depth3",
            revision = revision,
            relativeUrl = "shards/$shardId/$revision.sqlite",
            sha256 = Sha256Hex.ofFile(file),
            byteSize = file.length(),
            trackCount = 1
        )
    }

    private fun writeManifest(publishRoot: File, generation: Long, shards: List<ManifestShard>) {
        publishRoot.mkdirs()
        File(publishRoot, LocalCatalogActivator.MANIFEST_FILE).writeText(
            PublishManifestParser.encode(
                PublishManifest(
                    schemaVersion = 1,
                    libraryId = "lib",
                    generation = generation,
                    builtAtUtc = 0L,
                    shards = shards
                )
            )
        )
    }

    private fun writePresentShard(
        file: File,
        shardId: String,
        revision: String,
        title: String,
        lyrics: String
    ) {
        ShardFixture.write(
            file,
            shardId,
            revision,
            listOf(
                FixtureTrack(
                    trackId = 1,
                    title = title,
                    artist = "Artist",
                    album = "Album",
                    durationMs = 180_000L,
                    lyricsStatus = "present",
                    lyrics = lyrics,
                    format = "lrc",
                    textHash = "h"
                )
            )
        )
    }

    private fun assertKeepLyrics(title: String, lyrics: String) {
        LibraryCatalog.openIfPresent(context)!!.use { catalog ->
            val hit = catalog.lookup(
                TrackObservation(
                    title = title,
                    artist = "Artist",
                    album = "Album",
                    durationMs = 180_000L,
                    durationKnown = true
                )
            ) as CatalogLookupOutcome.Finish
            assertEquals(lyrics, hit.result?.plainLrc?.trim())
        }
    }

    private class MemoryAcceptanceStore : SyncAcceptanceStore {
        private var state: AcceptedSyncState? = null
        override fun load(): AcceptedSyncState? = state
        override fun save(state: AcceptedSyncState) {
            this.state = state
        }
    }

    private class FakeRemote : RemoteLibraryClient {
        data class Resource(
            var bytes: ByteArray,
            var lastModifiedHttp: String?,
            var etag: String? = "\"etag1\"",
            var status: Int = 200,
            var isCollection: Boolean = false
        )

        val resources = mutableMapOf<String, Resource>()
        val probes = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        var failAfterOperations: Int? = null
        private var operations = 0

        fun put(relativeUrl: String, bytes: ByteArray, lastModifiedHttp: String?) {
            resources[relativeUrl] = Resource(bytes, lastModifiedHttp)
        }

        override fun probe(relativeUrl: String, extraHeaders: Map<String, String>): RemoteProbe {
            tick()
            probes += relativeUrl
            val resource = resources[relativeUrl]
                ?: return RemoteProbe(statusCode = 404)
            return RemoteProbe(
                statusCode = resource.status,
                lastModifiedHttp = resource.lastModifiedHttp,
                etag = resource.etag,
                contentLength = resource.bytes.size.toLong(),
                isCollection = resource.isCollection
            )
        }

        override fun download(
            relativeUrl: String,
            dest: File?,
            extraHeaders: Map<String, String>
        ): RemoteDownload {
            tick()
            downloads += relativeUrl
            val resource = resources[relativeUrl]
                ?: return RemoteDownload(statusCode = 404)
            if (resource.status !in 200..299) {
                return RemoteDownload(statusCode = resource.status)
            }
            dest?.parentFile?.mkdirs()
            dest?.writeBytes(resource.bytes)
            return RemoteDownload(
                statusCode = 200,
                bytes = resource.bytes,
                file = dest,
                etag = resource.etag,
                lastModifiedHttp = resource.lastModifiedHttp
            )
        }

        private fun tick() {
            operations += 1
            val limit = failAfterOperations
            if (limit != null && operations > limit) {
                error("disconnected")
            }
        }
    }

    companion object {
        private const val OLDER = "Wed, 21 Sep 2026 01:00:00 GMT"
        private const val NEWER = "Wed, 21 Sep 2026 03:00:00 GMT"
    }
}
