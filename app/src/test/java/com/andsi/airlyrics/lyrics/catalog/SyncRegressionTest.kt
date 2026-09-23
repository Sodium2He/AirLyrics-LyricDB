package com.andsi.airlyrics.lyrics.catalog

import java.io.File
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class SyncRegressionTest {
    private val date = "Mon, 21 Sep 2026 00:00:00 GMT"
    private val bytes = "test shard".toByteArray()
    private fun manifest(generation: Long = 1L) = PublishManifest(1, "library", generation, 0,
        listOf(ManifestShard("s", "Album", "depth3", "r", "shards/r.sqlite", Sha256Hex.ofBytes(bytes), bytes.size.toLong(), 1)))

    private fun runSync(force: Boolean, changed: PublishManifest? = null, status: Int = 200, timeout: Boolean = false): Pair<SyncOutcome, Int> {
        val root = kotlin.io.path.createTempDirectory("airlyrics-sync-test").toFile()
        val original = PublishManifestParser.encode(manifest()).toByteArray()
        val body = changed?.let { PublishManifestParser.encode(it).toByteArray() } ?: original
        val state = AcceptedSyncState("library", 1L, Sha256Hex.ofBytes(original), HttpDate.parseToEpochMs(date)!!, null, emptyMap())
        var downloads = 0
        val remote = object : RemoteLibraryClient {
            override fun probe(relativeUrl: String, extraHeaders: Map<String, String>): RemoteProbe {
                if (timeout) throw SocketTimeoutException("must not expose credentials here")
                return RemoteProbe(status, date)
            }
            override fun download(relativeUrl: String, dest: File?, extraHeaders: Map<String, String>): RemoteDownload {
                val data = if (relativeUrl == "manifest.json") body else bytes.also { downloads++ }
                dest?.writeBytes(data)
                return RemoteDownload(200, data, dest)
            }
        }
        return try {
            val outcome = WebDavSyncEngine(
                wifiGate = { WifiSyncVerdict.WIFI }, remote = remote,
                stateStore = object : SyncAcceptanceStore {
                    override fun load() = state
                    override fun save(state: AcceptedSyncState) = Unit
                },
                activator = { LocalCatalogActivateResult.Activated("library", 1L, 1) },
                currentCatalog = { null },
                existingVerifiedShard = { error("Force sync must not reuse local shards") },
                stagingRoot = File(root, "staging"), force = force
            ).sync()
            outcome to downloads
        } finally { root.deleteRecursively() }
    }

    @Test fun ordinarySyncSkipsUnchangedGeneration() {
        val (outcome, count) = runSync(false)
        assertEquals(SyncRejectReason.UNCHANGED, (outcome as SyncOutcome.Rejected).reason)
        assertEquals(0, count)
    }

    @Test fun forcedSyncDownloadsEvenWhenGenerationUnchanged() {
        val (outcome, count) = runSync(true)
        assertTrue(outcome.toString(), outcome is SyncOutcome.Activated)
        assertEquals(1, count)
    }

    @Test fun forcedSyncAcceptsRollback() {
        assertTrue(runSync(true, manifest(0)).first is SyncOutcome.Activated)
    }

    @Test fun forcedSyncAcceptsMutatedGeneration() {
        val altered = manifest().copy(builtAtUtc = 42)
        assertTrue(runSync(true, altered).first is SyncOutcome.Activated)
    }

    @Test fun forcedSyncAcceptsRebuiltLibrary() {
        assertTrue(runSync(true, manifest().copy(libraryId = "rebuilt")).first is SyncOutcome.Activated)
    }

    @Test fun normalSyncStillRejectsRollbackAndRebuiltLibrary() {
        assertEquals(SyncRejectReason.GENERATION_OLDER, (runSync(false, manifest(0)).first as SyncOutcome.Rejected).reason)
        assertEquals(SyncRejectReason.LIBRARY_MISMATCH, (runSync(false, manifest().copy(libraryId = "rebuilt")).first as SyncOutcome.Rejected).reason)
    }

    @Test fun httpFailureShowsStepAndStatus() {
        val outcome = runSync(false, status = 401).first as SyncOutcome.Rejected
        assertEquals("PROBE | manifest.json | HTTP 401", outcome.detail)
    }

    @Test fun exceptionShowsClassWithoutLeakingMessage() {
        val outcome = runSync(false, timeout = true).first as SyncOutcome.Rejected
        assertEquals("PROBE | manifest.json | SocketTimeoutException", outcome.detail)
    }
}
