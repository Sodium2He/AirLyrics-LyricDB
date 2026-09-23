package com.andsi.airlyrics.lyrics.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

class LibraryCatalog(private val store: CatalogStore) : Closeable {
    fun currentGeneration(): Long? = store.currentMeta()?.generation

    fun currentMeta(): CatalogStore.CatalogMeta? = store.currentMeta()
    fun shardFiles(): Set<File> = store.shardFiles()

    fun activate(manifest: PublishManifest, shards: Map<String, File>, force: Boolean = false) {
        store.replaceGeneration(manifest, shards, force)
    }

    fun lookup(observation: TrackObservation): CatalogLookupOutcome {
        val meta = store.currentMeta() ?: return CatalogLookupOutcome.Continue
        val decision = TrackMatcher.decide(observation, store.recall(observation))
        return when (decision) {
            is MatchDecision.Accepted -> finishRef(decision.candidate, meta.generation, decision.reason)
            is MatchDecision.UniqueLyrics -> finishRef(
                decision.candidates.first(),
                meta.generation,
                decision.reason
            )
            is MatchDecision.Ambiguous -> CatalogLookupOutcome.Finish(null, "ambiguous")
            is MatchDecision.UnresolvedTruncation -> CatalogLookupOutcome.Finish(null, "truncated")
            is MatchDecision.Unmatched -> CatalogLookupOutcome.Finish(null, "unmatched")
        }
    }

    fun albumNeighbors(observation: TrackObservation): List<CatalogTrackRef> {
        return store.albumNeighbors(observation)
    }

    fun lyricsFor(ref: CatalogTrackRef): com.andsi.airlyrics.lyrics.LyricsProviderResult? {
        val meta = store.currentMeta() ?: return null
        return when (val outcome = finishRef(ref, meta.generation, "prefetch")) {
            is CatalogLookupOutcome.Finish -> outcome.result
            else -> null
        }
    }

    override fun close() {
        store.close()
    }

    private fun finishRef(
        ref: CatalogTrackRef,
        generation: Long,
        reason: String
    ): CatalogLookupOutcome {
        if (ref.lyricsStatus != "present") {
            return CatalogLookupOutcome.Finish(null, if (ref.lyricsStatus == "absent") "absent" else "read_error")
        }
        val rows = try {
            ShardLyricsReader.read(File(ref.localShardPath), ref.trackId)
        } catch (_: android.database.sqlite.SQLiteException) {
            return CatalogLookupOutcome.Finish(null, "read_error")
        }
        val row = ShardLyricsReader.firstPresentText(rows)
            ?: return CatalogLookupOutcome.Finish(null, "read_error")
        val raw = row.rawText ?: return CatalogLookupOutcome.Finish(null, "read_error")
        return CatalogLookupOutcome.Finish(
            ShardLyricsAdapter.toProviderResult(
                rawText = raw,
                format = row.format,
                matched = ref,
                catalogGeneration = generation,
                matchKind = reason
            )
        )
    }

    companion object {
        const val LIBRARY_DIR = "symfoniumx-library"
        const val ACTION_CHANGED = "com.andsi.airlyrics.LIBRARY_CATALOG_CHANGED"
        const val CATALOG_FILE = "catalog.sqlite"

        fun directory(context: Context): File {
            return File(context.filesDir, LIBRARY_DIR)
        }

        fun catalogFile(context: Context): File {
            return File(directory(context), CATALOG_FILE)
        }

        fun openIfPresent(context: Context): LibraryCatalog? {
            val file = catalogFile(context)
            val store = CatalogStore.openIfPresent(file) ?: return null
            return LibraryCatalog(store)
        }

        fun open(catalogFile: File): LibraryCatalog {
            return LibraryCatalog(CatalogStore(catalogFile))
        }

        fun status(context: Context): LibraryCatalogStatus? {
            val catalog = openIfPresent(context) ?: return null
            catalog.use { opened ->
                val meta = opened.currentMeta() ?: return null
                return LibraryCatalogStatus(
                    libraryId = meta.libraryId,
                    generation = meta.generation
                )
            }
        }
    }
}

data class LibraryCatalogStatus(
    val libraryId: String,
    val generation: Long
)

internal object AndroidCatalogLyricsLookup : CatalogLyricsLookup {
    override fun lookup(context: Context, observation: TrackObservation): CatalogLookupOutcome {
        val catalog = LibraryCatalog.openIfPresent(context) ?: return CatalogLookupOutcome.Continue
        return try {
            catalog.lookup(observation)
        } finally {
            catalog.close()
        }
    }
}

object PublishManifestParser {
    fun parse(json: String): PublishManifest {
        val root = JSONObject(json)
        val shardsJson = root.getJSONArray("shards")
        val shards = buildList {
            for (index in 0 until shardsJson.length()) {
                val item = shardsJson.getJSONObject(index)
                add(
                    ManifestShard(
                        shardId = item.getString("shard_id"),
                        bucket = item.getString("bucket"),
                        bucketKind = item.getString("bucket_kind"),
                        revision = item.getString("revision"),
                        relativeUrl = item.getString("relative_url"),
                        sha256 = item.getString("sha256"),
                        byteSize = item.getLong("byte_size"),
                        trackCount = item.getInt("track_count")
                    )
                )
            }
        }
        return PublishManifest(
            schemaVersion = root.getInt("schema_version"),
            libraryId = root.getString("library_id"),
            generation = root.getLong("generation"),
            builtAtUtc = root.getLong("built_at_utc"),
            shards = shards
        )
    }

    fun encode(manifest: PublishManifest): String {
        val shards = JSONArray()
        manifest.shards.forEach { shard ->
            shards.put(
                JSONObject().apply {
                    put("shard_id", shard.shardId)
                    put("bucket", shard.bucket)
                    put("bucket_kind", shard.bucketKind)
                    put("revision", shard.revision)
                    put("relative_url", shard.relativeUrl)
                    put("sha256", shard.sha256)
                    put("byte_size", shard.byteSize)
                    put("track_count", shard.trackCount)
                }
            )
        }
        return JSONObject().apply {
            put("schema_version", manifest.schemaVersion)
            put("library_id", manifest.libraryId)
            put("generation", manifest.generation)
            put("built_at_utc", manifest.builtAtUtc)
            put("shards", shards)
        }.toString()
    }
}
