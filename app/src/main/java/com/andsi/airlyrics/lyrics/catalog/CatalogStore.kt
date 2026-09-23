package com.andsi.airlyrics.lyrics.catalog

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.andsi.airlyrics.core.text.MetadataNormalizer
import java.io.Closeable
import java.io.File

class CatalogStore(private val dbFile: File) : Closeable {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null).also { database ->
        database.execSQL("PRAGMA foreign_keys=ON;")
        createSchema(database)
    }

    fun currentMeta(): CatalogMeta? {
        db.rawQuery("SELECT library_id, generation, schema_version, normalization_version FROM catalog_meta LIMIT 1", null)
            .use { cursor ->
                if (!cursor.moveToFirst()) return null
                return CatalogMeta(
                    libraryId = cursor.getString(0),
                    generation = cursor.getLong(1),
                    schemaVersion = cursor.getInt(2),
                    normalizationVersion = cursor.getInt(3)
                )
            }
    }

    fun shardFiles(): Set<File> = loadShards().values.map { File(it.localPath) }.toSet()

    fun replaceGeneration(manifest: PublishManifest, shards: Map<String, File>, force: Boolean = false) {
        val current = currentMeta()
        if (!force && current != null && current.libraryId != manifest.libraryId) {
            error("catalog library_id ${current.libraryId} does not match ${manifest.libraryId}")
        }
        if (!force && current != null && manifest.generation < current.generation) {
            error("catalog refuses generation ${manifest.generation} older than ${current.generation}")
        }
        if (!force && current != null &&
            current.libraryId == manifest.libraryId &&
            current.generation == manifest.generation
        ) {
            return
        }

        val existing = loadShards()
        db.beginTransaction()
        try {
            val incomingIds = manifest.shards.map { it.shardId }.toSet()
            existing.keys.filter { shardId -> shardId !in incomingIds }.forEach(::deleteShard)
            manifest.shards.forEach { shard ->
                val file = shards[shard.shardId]
                    ?: error("missing local shard file for ${shard.shardId}")
                val previous = existing[shard.shardId]
                if (!force && previous != null &&
                    previous.revision == shard.revision &&
                    previous.localPath == file.absolutePath
                ) {
                    return@forEach
                }
                deleteShard(shard.shardId)
                CatalogIndexer.index(db, shard, file)
            }
            db.execSQL("DELETE FROM catalog_meta")
            db.execSQL(
                """
                INSERT INTO catalog_meta (
                    library_id, generation, schema_version, normalization_version, activated_at_utc
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    manifest.libraryId,
                    manifest.generation,
                    SCHEMA_VERSION,
                    NORMALIZATION_VERSION,
                    System.currentTimeMillis()
                )
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recall(observation: TrackObservation): List<CatalogTrackRef> {
        val title = observation.title ?: return emptyList()
        val titlePrimary = MetadataNormalizer.primary(title)
        val titleSecondary = MetadataNormalizer.secondary(title)
        val artistPrimary = observation.artist?.takeIf { it.isNotBlank() }?.let(MetadataNormalizer::primary)
        val artistClause = if (artistPrimary == null) "" else """
            AND (t.artist_primary = ? OR EXISTS (
                SELECT 1 FROM catalog_artist ca
                WHERE ca.shard_id = t.shard_id AND ca.track_id = t.track_id AND ca.artist_primary = ?
            ))
        """.trimIndent()
        val limit = TrackMatcher.RECALL_LIMIT + 1
        val sql = """
            SELECT t.shard_id, t.track_id, t.title, t.artist, t.album, t.album_artist,
                   t.duration_ms, t.disc, t.track_number, t.genre, t.lyrics_status, t.lyrics_text_hash,
                   s.revision, s.local_path, t.title_primary
            FROM catalog_track t
            JOIN catalog_shard s ON s.shard_id = t.shard_id
            WHERE (t.title_primary = ?
               OR t.title_secondary = ?
               OR EXISTS (
                    SELECT 1 FROM catalog_alias a
                    WHERE a.shard_id = t.shard_id
                      AND a.track_id = t.track_id
                      AND a.field = 'title'
                      AND a.old_value_primary = ?
               ))
            $artistClause
            LIMIT $limit
        """.trimIndent()
        val args = mutableListOf(titlePrimary, titleSecondary, titlePrimary)
        if (artistPrimary != null) args.addAll(listOf(artistPrimary, artistPrimary))
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val results = mutableListOf<CatalogTrackRef>()
            while (cursor.moveToNext()) {
                val shardId = cursor.getString(0)
                val trackId = cursor.getLong(1)
                val titlePrimaryStored = cursor.getStringOrNull(14)
                val artists = loadArtists(shardId, trackId)
                val viaAlias = titlePrimaryStored != titlePrimary &&
                    cursor.getStringOrNull(2)?.let(MetadataNormalizer::secondary) != titleSecondary
                results += CatalogTrackRef(
                    shardId = shardId,
                    revision = cursor.getString(12),
                    trackId = trackId,
                    localShardPath = cursor.getString(13),
                    title = cursor.getStringOrNull(2),
                    artist = cursor.getStringOrNull(3),
                    artists = artists,
                    album = cursor.getStringOrNull(4),
                    albumArtist = cursor.getStringOrNull(5),
                    durationMs = cursor.getLongOrNull(6),
                    disc = cursor.getIntOrNull(7),
                    trackNumber = cursor.getIntOrNull(8),
                    genre = cursor.getStringOrNull(9),
                    lyricsStatus = cursor.getString(10),
                    lyricsTextHash = cursor.getStringOrNull(11),
                    matchedViaAlias = viaAlias
                )
            }
            return results
        }
    }

    fun albumNeighbors(
        observation: TrackObservation,
        limit: Int = QueuePrefetchPlanner.LIMIT
    ): List<CatalogTrackRef> {
        val album = observation.album ?: return emptyList()
        val trackNumber = observation.trackNumber ?: return emptyList()
        val albumPrimary = MetadataNormalizer.primary(album)
        val albumArtistPrimary = observation.albumArtist?.let(MetadataNormalizer::primary)
        val disc = observation.discNumber ?: 1
        val artistClause = if (albumArtistPrimary != null) {
            "AND t.album_artist_primary = ?"
        } else {
            ""
        }
        val sql = """
            SELECT t.shard_id, t.track_id, t.title, t.artist, t.album, t.album_artist,
                   t.duration_ms, t.disc, t.track_number, t.genre, t.lyrics_status, t.lyrics_text_hash,
                   s.revision, s.local_path
            FROM catalog_track t
            JOIN catalog_shard s ON s.shard_id = t.shard_id
            WHERE t.album_primary = ?
              AND t.track_number IS NOT NULL
              AND (
                COALESCE(t.disc, 1) > ?
                OR (COALESCE(t.disc, 1) = ? AND t.track_number > ?)
              )
              $artistClause
            ORDER BY COALESCE(t.disc, 1), t.track_number
            LIMIT $limit
        """.trimIndent()
        val args = buildList {
            add(albumPrimary)
            add(disc.toString())
            add(disc.toString())
            add(trackNumber.toString())
            if (albumArtistPrimary != null) add(albumArtistPrimary)
        }.toTypedArray()
        db.rawQuery(sql, args).use { cursor ->
            val results = mutableListOf<CatalogTrackRef>()
            while (cursor.moveToNext()) {
                val shardId = cursor.getString(0)
                val trackId = cursor.getLong(1)
                results += CatalogTrackRef(
                    shardId = shardId,
                    revision = cursor.getString(12),
                    trackId = trackId,
                    localShardPath = cursor.getString(13),
                    title = cursor.getStringOrNull(2),
                    artist = cursor.getStringOrNull(3),
                    artists = loadArtists(shardId, trackId),
                    album = cursor.getStringOrNull(4),
                    albumArtist = cursor.getStringOrNull(5),
                    durationMs = cursor.getLongOrNull(6),
                    disc = cursor.getIntOrNull(7),
                    trackNumber = cursor.getIntOrNull(8),
                    genre = cursor.getStringOrNull(9),
                    lyricsStatus = cursor.getString(10),
                    lyricsTextHash = cursor.getStringOrNull(11)
                )
            }
            return results
        }
    }

    override fun close() {
        db.close()
    }

    private fun loadShards(): Map<String, IndexedShard> {
        db.rawQuery("SELECT shard_id, revision, local_path FROM catalog_shard", null).use { cursor ->
            val shards = mutableMapOf<String, IndexedShard>()
            while (cursor.moveToNext()) {
                shards[cursor.getString(0)] = IndexedShard(
                    revision = cursor.getString(1),
                    localPath = cursor.getString(2)
                )
            }
            return shards
        }
    }

    private fun deleteShard(shardId: String) {
        val args = arrayOf(shardId)
        db.execSQL("DELETE FROM catalog_alias WHERE shard_id = ?", args)
        db.execSQL("DELETE FROM catalog_artist WHERE shard_id = ?", args)
        db.execSQL("DELETE FROM catalog_track WHERE shard_id = ?", args)
        db.execSQL("DELETE FROM catalog_shard WHERE shard_id = ?", args)
    }

    private fun loadArtists(shardId: String, trackId: Long): List<String> {
        db.rawQuery(
            "SELECT artist FROM catalog_artist WHERE shard_id = ? AND track_id = ? ORDER BY ordinal",
            arrayOf(shardId, trackId.toString())
        ).use { cursor ->
            val artists = mutableListOf<String>()
            while (cursor.moveToNext()) {
                artists += cursor.getString(0)
            }
            return artists
        }
    }

    private data class IndexedShard(val revision: String, val localPath: String)

    data class CatalogMeta(
        val libraryId: String,
        val generation: Long,
        val schemaVersion: Int,
        val normalizationVersion: Int
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val NORMALIZATION_VERSION = 1

        fun openIfPresent(dbFile: File): CatalogStore? {
            return if (dbFile.isFile) CatalogStore(dbFile) else null
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_meta (
                    library_id TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    schema_version INTEGER NOT NULL,
                    normalization_version INTEGER NOT NULL,
                    activated_at_utc INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_shard (
                    shard_id TEXT PRIMARY KEY NOT NULL,
                    revision TEXT NOT NULL,
                    bucket TEXT NOT NULL,
                    bucket_kind TEXT NOT NULL,
                    local_path TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    track_count INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_track (
                    shard_id TEXT NOT NULL,
                    track_id INTEGER NOT NULL,
                    title TEXT,
                    title_primary TEXT,
                    title_secondary TEXT,
                    artist TEXT,
                    artist_primary TEXT,
                    album TEXT,
                    album_primary TEXT,
                    album_artist TEXT,
                    album_artist_primary TEXT,
                    duration_ms INTEGER,
                    disc INTEGER,
                    track_number INTEGER,
                    genre TEXT,
                    lyrics_status TEXT NOT NULL,
                    lyrics_text_hash TEXT,
                    PRIMARY KEY (shard_id, track_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_artist (
                    shard_id TEXT NOT NULL,
                    track_id INTEGER NOT NULL,
                    ordinal INTEGER NOT NULL,
                    artist TEXT NOT NULL,
                    artist_primary TEXT NOT NULL,
                    PRIMARY KEY (shard_id, track_id, ordinal)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS catalog_alias (
                    shard_id TEXT NOT NULL,
                    track_id INTEGER NOT NULL,
                    field TEXT NOT NULL,
                    old_value TEXT NOT NULL,
                    old_value_primary TEXT NOT NULL,
                    source TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_title ON catalog_track(title_primary)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_title_secondary ON catalog_track(title_secondary)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_title_artist ON catalog_track(title_primary, artist_primary)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_album ON catalog_track(album_primary, album_artist_primary)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_duration ON catalog_track(duration_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_alias ON catalog_alias(field, old_value_primary)")
            if (db.version < 1) {
                db.beginTransaction()
                try {
                    db.rawQuery("SELECT shard_id, track_id, title FROM catalog_track", null).use { rows ->
                        while (rows.moveToNext()) {
                            db.execSQL("UPDATE catalog_track SET title_secondary = ? WHERE shard_id = ? AND track_id = ?",
                                arrayOf<Any?>(rows.getStringOrNull(2)?.let(MetadataNormalizer::secondary), rows.getString(0), rows.getLong(1)))
                        }
                    }
                    db.version = 1
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }
}

internal fun Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

internal fun Cursor.getLongOrNull(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}

internal fun Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) null else getInt(index)
}

internal fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}
