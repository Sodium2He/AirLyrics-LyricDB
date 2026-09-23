package com.andsi.airlyrics.lyrics.catalog

import android.content.Context
import java.io.File

internal class ServiceCatalogFixture {
    private val tracks = linkedMapOf<String, FixtureTrack>()
    private var generation = 0L

    fun save(context: Context, title: String, artist: String, album: String, duration: Long, lyrics: String) {
        val id = tracks[title]?.trackId ?: (tracks.size + 1).toLong()
        tracks[title] = FixtureTrack(id, title, artist, album, durationMs = duration,
            lyricsStatus = "present", lyrics = lyrics, format = "lrc", textHash = Sha256Hex.ofBytes(lyrics.toByteArray()))
        val revision = "r${++generation}"
        val shard = File(LibraryCatalog.directory(context), "$revision.sqlite")
        ShardFixture.write(shard, "service", revision, tracks.values.toList())
        LibraryCatalog.open(LibraryCatalog.catalogFile(context)).use { catalog ->
            catalog.activate(PublishManifest(1, "lib", generation, 0,
                listOf(ManifestShard("service", "Album", "depth3", revision, "$revision.sqlite",
                    Sha256Hex.ofFile(shard), shard.length(), tracks.size))), mapOf("service" to shard))
        }
    }

    fun clear(context: Context) {
        tracks.clear()
        generation = 0
        LibraryCatalog.directory(context).deleteRecursively()
    }
}

