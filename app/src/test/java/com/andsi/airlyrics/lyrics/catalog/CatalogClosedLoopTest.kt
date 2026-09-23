package com.andsi.airlyrics.lyrics.catalog

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogClosedLoopTest {
    @Test
    fun artistIsUsedBeforeCandidateLimit() {
        val shard = File(root, "common-title.sqlite")
        ShardFixture.write(shard, "s1", "r1", (1L..80L).map { id ->
            FixtureTrack(id, "Intro", "Artist $id", "Album", durationMs = 100000L,
                lyricsStatus = "present", lyrics = "[00:01.00]line $id", format = "lrc", textHash = "$id")
        })
        LibraryCatalog.open(File(root, "catalog.sqlite")).use { catalog ->
            catalog.activate(manifest(1L, "s1", "r1", shard), mapOf("s1" to shard))
            val outcome = catalog.lookup(TrackObservation("Intro", "Artist 80", "Album", durationMs = 100000L, durationKnown = true))
            assertEquals("[00:01.00]line 80", (outcome as CatalogLookupOutcome.Finish).result?.plainLrc)
        }
    }

    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "catalog-loop-${System.nanoTime()}").also { it.mkdirs() }
    }

    @Test
    fun activateAndMatch_doesNotUsePathAndKeepsVersionsApart() {
        val shardFile = File(root, "shard.sqlite")
        ShardFixture.write(
            file = shardFile,
            shardId = "s1",
            revision = "r1",
            tracks = listOf(
                FixtureTrack(
                    trackId = 1,
                    title = "Deep Mountain",
                    artist = "Artist",
                    album = "Studio",
                    durationMs = 180_000L,
                    lyricsStatus = "present",
                    lyrics = "[00:01.00]studio",
                    format = "lrc",
                    textHash = "studio"
                ),
                FixtureTrack(
                    trackId = 2,
                    title = "Deep Mountain",
                    artist = "Artist",
                    album = "Live",
                    durationMs = 181_000L,
                    lyricsStatus = "present",
                    lyrics = "[00:01.00]live",
                    format = "lrc",
                    textHash = "live"
                )
            )
        )
        val catalog = LibraryCatalog.open(File(root, "catalog.sqlite"))
        catalog.activate(
            manifest(generation = 1L, shardId = "s1", revision = "r1", file = shardFile),
            mapOf("s1" to shardFile)
        )

        val live = catalog.lookup(
            TrackObservation(
                title = "Deep Mountain",
                artist = "Artist",
                album = "Live",
                durationMs = 181_000L,
                durationKnown = true
            )
        ) as CatalogLookupOutcome.Finish
        assertEquals("[00:01.00]live", live.result?.plainLrc?.trim())
        assertEquals("library", live.result?.plainProviderId)

        val studio = catalog.lookup(
            TrackObservation(
                title = "Deep Mountain",
                artist = "Artist",
                album = "Studio",
                durationMs = 180_000L,
                durationKnown = true
            )
        ) as CatalogLookupOutcome.Finish
        assertEquals("[00:01.00]studio", studio.result?.plainLrc?.trim())
        catalog.close()
    }

    @Test
    fun replacingShard_updatesIndexAndDropsRemovedTracks() {
        val first = File(root, "first.sqlite")
        val second = File(root, "second.sqlite")
        ShardFixture.write(
            first, "s1", "r1",
            listOf(
                FixtureTrack(1, "Old", "Artist", "Album", durationMs = 100_000L, lyricsStatus = "present", lyrics = "[00:01.00]old", format = "lrc", textHash = "old")
            )
        )
        ShardFixture.write(
            second, "s1", "r2",
            listOf(
                FixtureTrack(1, "New", "Artist", "Album", durationMs = 100_000L, lyricsStatus = "present", lyrics = "[00:01.00]new", format = "lrc", textHash = "new")
            )
        )
        val catalog = LibraryCatalog.open(File(root, "catalog.sqlite"))
        catalog.activate(manifest(1L, "s1", "r1", first), mapOf("s1" to first))
        catalog.activate(manifest(2L, "s1", "r2", second), mapOf("s1" to second))

        val oldHit = catalog.lookup(
            TrackObservation(title = "Old", artist = "Artist", album = "Album", durationMs = 100_000L, durationKnown = true)
        )
        assertTrue(oldHit is CatalogLookupOutcome.Continue || (oldHit is CatalogLookupOutcome.Finish && oldHit.result == null))

        val newHit = catalog.lookup(
            TrackObservation(title = "New", artist = "Artist", album = "Album", durationMs = 100_000L, durationKnown = true)
        ) as CatalogLookupOutcome.Finish
        assertEquals("[00:01.00]new", newHit.result?.plainLrc?.trim())
        assertEquals(2L, newHit.result?.catalogGeneration)
        catalog.close()
    }

    @Test
    fun knownAbsent_doesNotReturnLyrics() {
        val shardFile = File(root, "absent.sqlite")
        ShardFixture.write(
            shardFile, "s1", "r1",
            listOf(
                FixtureTrack(1, "Instrumental", "Artist", "Album", durationMs = 90_000L, lyricsStatus = "absent")
            )
        )
        val catalog = LibraryCatalog.open(File(root, "catalog.sqlite"))
        catalog.activate(manifest(1L, "s1", "r1", shardFile), mapOf("s1" to shardFile))
        val hit = catalog.lookup(
            TrackObservation(
                title = "Instrumental",
                artist = "Artist",
                album = "Album",
                durationMs = 90_000L,
                durationKnown = true
            )
        ) as CatalogLookupOutcome.Finish
        assertNull(hit.result)
        catalog.close()
    }

    private fun manifest(
        generation: Long,
        shardId: String,
        revision: String,
        file: File
    ): PublishManifest {
        return PublishManifest(
            schemaVersion = 1,
            libraryId = "lib",
            generation = generation,
            builtAtUtc = 0L,
            shards = listOf(
                ManifestShard(
                    shardId = shardId,
                    bucket = "Album/A/B",
                    bucketKind = "depth3",
                    revision = revision,
                    relativeUrl = "shards/x",
                    sha256 = "abc",
                    byteSize = file.length(),
                    trackCount = 1
                )
            )
        )
    }
}
