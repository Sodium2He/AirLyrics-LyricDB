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
class LocalCatalogActivatorTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "publish-activate-${System.nanoTime()}").also { it.mkdirs() }
        File(context.filesDir, LibraryCatalog.LIBRARY_DIR).deleteRecursively()
    }

    @Test
    fun activateFromPublishDirectory_decodesHashSegmentAndServesLyrics() {
        val publish = File(root, "publish")
        val written = File(root, "plain.sqlite")
        writePresentShard(written, "s-hash", "r1", "Hash Song", "[00:01.00]hash")
        val shardFile = File(publish, "shards/depth3/Album/Instrumental+/# アニメ/r1.sqlite")
        shardFile.parentFile?.mkdirs()
        written.copyTo(shardFile, overwrite = true)
        writeManifest(
            publish,
            generation = 3L,
            shardId = "s-hash",
            revision = "r1",
            relativeUrl = "shards/depth3/Album/Instrumental%2B/%23%20アニメ/r1.sqlite",
            file = shardFile
        )

        val result = LocalCatalogActivator.activateFromPublishDirectory(context, publish)
        val activated = result as LocalCatalogActivateResult.Activated
        assertEquals("lib", activated.libraryId)
        assertEquals(3L, activated.generation)

        LibraryCatalog.openIfPresent(context)!!.use { catalog ->
            val hit = catalog.lookup(
                TrackObservation(
                    title = "Hash Song",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 180_000L,
                    durationKnown = true
                )
            ) as CatalogLookupOutcome.Finish
            assertEquals("[00:01.00]hash", hit.result?.plainLrc?.trim())
        }
    }

    @Test
    fun hashMismatch_doesNotReplaceActiveGeneration() {
        val first = File(root, "first")
        val firstShard = File(first, "shards/s1/r1.sqlite")
        writePresentShard(firstShard, "s1", "r1", "Keep", "[00:01.00]keep")
        writeManifest(first, 1L, "s1", "r1", "shards/s1/r1.sqlite", firstShard)
        assertTrue(
            LocalCatalogActivator.activateFromPublishDirectory(context, first)
                is LocalCatalogActivateResult.Activated
        )

        val bad = File(root, "bad")
        val badShard = File(bad, "shards/s1/r2.sqlite")
        writePresentShard(badShard, "s1", "r2", "Bad", "[00:01.00]bad")
        writeManifest(
            publishRoot = bad,
            generation = 2L,
            shardId = "s1",
            revision = "r2",
            relativeUrl = "shards/s1/r2.sqlite",
            file = badShard,
            sha256 = "deadbeef"
        )

        val failed = LocalCatalogActivator.activateFromPublishDirectory(context, bad)
        assertEquals(
            LocalCatalogActivateResult.Reason.SHARD_HASH,
            (failed as LocalCatalogActivateResult.Failed).reason
        )

        LibraryCatalog.openIfPresent(context)!!.use { catalog ->
            assertEquals(1L, catalog.currentGeneration())
            val hit = catalog.lookup(
                TrackObservation(
                    title = "Keep",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 180_000L,
                    durationKnown = true
                )
            ) as CatalogLookupOutcome.Finish
            assertEquals("[00:01.00]keep", hit.result?.plainLrc?.trim())
        }
    }

    @Test
    fun olderGeneration_isRejected() {
        val first = File(root, "gen2")
        val shard = File(first, "shards/s1/r2.sqlite")
        writePresentShard(shard, "s1", "r2", "New", "[00:01.00]new")
        writeManifest(first, 2L, "s1", "r2", "shards/s1/r2.sqlite", shard)
        LocalCatalogActivator.activateFromPublishDirectory(context, first)

        val older = File(root, "gen1")
        val olderShard = File(older, "shards/s1/r1.sqlite")
        writePresentShard(olderShard, "s1", "r1", "Old", "[00:01.00]old")
        writeManifest(older, 1L, "s1", "r1", "shards/s1/r1.sqlite", olderShard)

        val failed = LocalCatalogActivator.activateFromPublishDirectory(context, older)
        assertEquals(
            LocalCatalogActivateResult.Reason.GENERATION_OLDER,
            (failed as LocalCatalogActivateResult.Failed).reason
        )
        LibraryCatalog.openIfPresent(context)!!.use { catalog ->
            assertEquals(2L, catalog.currentGeneration())
        }
    }

    private fun writePresentShard(file: File, shardId: String, revision: String, title: String, lyrics: String) {
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

    private fun writeManifest(
        publishRoot: File,
        generation: Long,
        shardId: String,
        revision: String,
        relativeUrl: String,
        file: File,
        sha256: String? = null
    ) {
        publishRoot.mkdirs()
        File(publishRoot, LocalCatalogActivator.MANIFEST_FILE).writeText(
            PublishManifestParser.encode(
                PublishManifest(
                    schemaVersion = 1,
                    libraryId = "lib",
                    generation = generation,
                    builtAtUtc = 0L,
                    shards = listOf(
                        ManifestShard(
                            shardId = shardId,
                            bucket = "Album/Instrumental+/# アニメ",
                            bucketKind = "depth3",
                            revision = revision,
                            relativeUrl = relativeUrl,
                            sha256 = sha256 ?: Sha256Hex.ofFile(file),
                            byteSize = file.length(),
                            trackCount = 1
                        )
                    )
                )
            )
        )
    }
}
