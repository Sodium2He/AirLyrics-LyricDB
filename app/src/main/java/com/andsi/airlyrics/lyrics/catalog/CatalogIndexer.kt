package com.andsi.airlyrics.lyrics.catalog

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.andsi.airlyrics.core.text.MetadataNormalizer
import java.io.File

internal object CatalogIndexer {
    fun index(catalog: SQLiteDatabase, shard: ManifestShard, shardFile: File) {
        val shardDb = SQLiteDatabase.openDatabase(
            shardFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            shardDb.rawQuery(
                "SELECT shard_id, revision FROM db_info LIMIT 1",
                null
            ).use { info ->
                if (!info.moveToFirst()) {
                    error("shard missing db_info: ${shardFile.absolutePath}")
                }
                val shardId = info.getString(0)
                val revision = info.getString(1)
                if (shardId != shard.shardId || revision != shard.revision) {
                    error("shard db_info mismatch for ${shard.shardId}")
                }
            }

            catalog.insertOrThrow(
                "catalog_shard",
                null,
                ContentValues().apply {
                    put("shard_id", shard.shardId)
                    put("revision", shard.revision)
                    put("bucket", shard.bucket)
                    put("bucket_kind", shard.bucketKind)
                    put("local_path", shardFile.absolutePath)
                    put("sha256", shard.sha256)
                    put("byte_size", shard.byteSize)
                    put("track_count", shard.trackCount)
                }
            )

            val artists = loadArtists(shardDb)
            val aliases = loadAliases(shardDb)
            val hashes = loadLyricHashes(shardDb)
            shardDb.rawQuery(
                """
                SELECT track_id, title, album, album_artist, duration_ms, disc, track_number, genre, lyrics_status
                FROM track
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val trackId = cursor.getLong(0)
                    val title = cursor.getStringOrNull(1)
                    val artistList = artists[trackId].orEmpty()
                    val artist = artistList.firstOrNull()
                    val album = cursor.getStringOrNull(2)
                    val albumArtist = cursor.getStringOrNull(3)
                    catalog.insertOrThrow(
                        "catalog_track",
                        null,
                        ContentValues().apply {
                            put("shard_id", shard.shardId)
                            put("track_id", trackId)
                            putNullable("title", title)
                            putNullable("title_primary", title?.let(MetadataNormalizer::primary))
                            putNullable(
                                "title_secondary",
                                title?.let(MetadataNormalizer::secondary)
                            )
                            putNullable("artist", artist)
                            putNullable("artist_primary", artist?.let(MetadataNormalizer::primary))
                            putNullable("album", album)
                            putNullable("album_primary", album?.let(MetadataNormalizer::primary))
                            putNullable("album_artist", albumArtist)
                            putNullable(
                                "album_artist_primary",
                                albumArtist?.let(MetadataNormalizer::primary)
                            )
                            putNullable("duration_ms", cursor.getLongOrNull(4))
                            putNullable("disc", cursor.getIntOrNull(5))
                            putNullable("track_number", cursor.getIntOrNull(6))
                            putNullable("genre", cursor.getStringOrNull(7))
                            put("lyrics_status", cursor.getString(8))
                            putNullable("lyrics_text_hash", hashes[trackId])
                        }
                    )
                    artistList.forEachIndexed { ordinal, name ->
                        catalog.insertOrThrow(
                            "catalog_artist",
                            null,
                            ContentValues().apply {
                                put("shard_id", shard.shardId)
                                put("track_id", trackId)
                                put("ordinal", ordinal)
                                put("artist", name)
                                put("artist_primary", MetadataNormalizer.primary(name))
                            }
                        )
                    }
                    aliases[trackId].orEmpty().forEach { alias ->
                        catalog.insertOrThrow(
                            "catalog_alias",
                            null,
                            ContentValues().apply {
                                put("shard_id", shard.shardId)
                                put("track_id", trackId)
                                put("field", alias.field)
                                put("old_value", alias.oldValue)
                                put("old_value_primary", MetadataNormalizer.primary(alias.oldValue))
                                put("source", alias.source)
                            }
                        )
                    }
                }
            }
        } finally {
            shardDb.close()
        }
    }

    private fun loadArtists(shardDb: SQLiteDatabase): Map<Long, List<String>> {
        val artists = mutableMapOf<Long, MutableList<String>>()
        shardDb.rawQuery(
            "SELECT track_id, artist FROM track_artist ORDER BY track_id, ordinal",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                artists.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(cursor.getString(1))
            }
        }
        return artists
    }

    private fun loadAliases(shardDb: SQLiteDatabase): Map<Long, List<StoredAlias>> {
        val aliases = mutableMapOf<Long, MutableList<StoredAlias>>()
        shardDb.rawQuery(
            "SELECT track_id, field, old_value, source FROM match_alias",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                aliases.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(
                    StoredAlias(
                        field = cursor.getString(1),
                        oldValue = cursor.getString(2),
                        source = cursor.getString(3)
                    )
                )
            }
        }
        return aliases
    }

    private fun loadLyricHashes(shardDb: SQLiteDatabase): Map<Long, String> {
        val hashes = mutableMapOf<Long, MutableList<String>>()
        shardDb.rawQuery(
            """
            SELECT track_id, text_hash FROM lyrics
            WHERE text_hash IS NOT NULL AND text_hash <> ''
            ORDER BY track_id, variant_order
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hashes.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(cursor.getString(1))
            }
        }
        return hashes.mapValues { (_, values) -> values.joinToString("|") }
    }

    private data class StoredAlias(
        val field: String,
        val oldValue: String,
        val source: String
    )
}
