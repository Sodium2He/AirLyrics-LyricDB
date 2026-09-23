package com.andsi.airlyrics.lyrics.catalog

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.andsi.airlyrics.core.text.MetadataNormalizer
import java.io.File

internal data class FixtureTrack(
    val trackId: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String? = artist,
    val durationMs: Long?,
    val disc: Int? = null,
    val trackNumber: Int? = null,
    val lyricsStatus: String,
    val lyrics: String? = null,
    val format: String? = null,
    val textHash: String? = null,
    val aliases: List<Triple<String, String, String>> = emptyList()
)

internal object ShardFixture {
    fun write(
        file: File,
        shardId: String,
        revision: String,
        tracks: List<FixtureTrack>
    ) {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA foreign_keys=ON;")
            db.execSQL(
                """
                CREATE TABLE db_info (
                    schema_version INTEGER NOT NULL,
                    normalization_version INTEGER NOT NULL,
                    library_id TEXT NOT NULL,
                    shard_id TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    bucket_path TEXT NOT NULL,
                    bucket_kind TEXT NOT NULL,
                    built_at_utc INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE track (
                    track_id INTEGER PRIMARY KEY NOT NULL,
                    relative_path TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    title TEXT,
                    album TEXT,
                    album_artist TEXT,
                    duration_ms INTEGER,
                    disc INTEGER,
                    track_number INTEGER,
                    genre TEXT,
                    mbid TEXT,
                    isrc TEXT,
                    lyrics_status TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE track_artist (
                    track_id INTEGER NOT NULL,
                    ordinal INTEGER NOT NULL,
                    artist TEXT NOT NULL,
                    PRIMARY KEY (track_id, ordinal)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE match_alias (
                    track_id INTEGER NOT NULL,
                    field TEXT NOT NULL,
                    old_value TEXT NOT NULL,
                    source TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE lyrics (
                    lyric_id INTEGER PRIMARY KEY NOT NULL,
                    track_id INTEGER NOT NULL,
                    tag_kind TEXT NOT NULL,
                    language TEXT,
                    description TEXT,
                    variant_order INTEGER NOT NULL,
                    format TEXT,
                    raw_text TEXT,
                    text_hash TEXT,
                    diagnostic TEXT
                )
                """.trimIndent()
            )
            db.insertOrThrow(
                "db_info",
                null,
                ContentValues().apply {
                    put("schema_version", 1)
                    put("normalization_version", 1)
                    put("library_id", "lib")
                    put("shard_id", shardId)
                    put("revision", revision)
                    put("bucket_path", "Album/A/B")
                    put("bucket_kind", "depth3")
                    put("built_at_utc", 0)
                }
            )
            tracks.forEach { track ->
                db.insertOrThrow(
                    "track",
                    null,
                    ContentValues().apply {
                        put("track_id", track.trackId)
                        put("relative_path", "Album/A/B/${track.trackId}.m4a")
                        put("filename", "${track.trackId}.m4a")
                        putNullable("title", track.title)
                        putNullable("album", track.album)
                        putNullable("album_artist", track.albumArtist)
                        putNullable("duration_ms", track.durationMs)
                        putNullable("disc", track.disc)
                        putNullable("track_number", track.trackNumber)
                        put("lyrics_status", track.lyricsStatus)
                    }
                )
                if (!track.artist.isNullOrBlank()) {
                    db.insertOrThrow(
                        "track_artist",
                        null,
                        ContentValues().apply {
                            put("track_id", track.trackId)
                            put("ordinal", 0)
                            put("artist", track.artist)
                        }
                    )
                }
                track.aliases.forEach { (field, old, source) ->
                    db.insertOrThrow(
                        "match_alias",
                        null,
                        ContentValues().apply {
                            put("track_id", track.trackId)
                            put("field", field)
                            put("old_value", old)
                            put("source", source)
                        }
                    )
                }
                if (track.lyrics != null) {
                    val hash = track.textHash ?: MetadataNormalizer.primary(track.lyrics)
                    db.insertOrThrow(
                        "lyrics",
                        null,
                        ContentValues().apply {
                            put("lyric_id", track.trackId * 10)
                            put("track_id", track.trackId)
                            put("tag_kind", "LYRICS")
                            put("variant_order", 0)
                            putNullable("format", track.format)
                            put("raw_text", track.lyrics)
                            put("text_hash", hash)
                        }
                    )
                }
            }
        } finally {
            db.close()
        }
    }
}
