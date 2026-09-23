package com.andsi.airlyrics.lyrics.catalog

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileSyncAcceptanceStore(private val file: File) : SyncAcceptanceStore {
    override fun load(): AcceptedSyncState? {
        if (!file.isFile) return null
        return try {
            parse(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    override fun save(state: AcceptedSyncState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(state))
        if (file.exists() && !file.delete()) {
            tmp.delete()
            error("unable to replace sync state")
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "sync-state.json"

        fun forLibrary(libraryDir: File): FileSyncAcceptanceStore {
            return FileSyncAcceptanceStore(File(libraryDir, FILE_NAME))
        }

        fun parse(json: String): AcceptedSyncState {
            val root = JSONObject(json)
            val shardsJson = root.getJSONArray("shards")
            val shards = LinkedHashMap<String, AcceptedShardState>()
            for (index in 0 until shardsJson.length()) {
                val item = shardsJson.getJSONObject(index)
                val shardId = item.getString("shard_id")
                shards[shardId] = AcceptedShardState(
                    shardId = shardId,
                    revision = item.getString("revision"),
                    relativeUrl = item.getString("relative_url"),
                    lastModifiedMs = item.getLong("last_modified_ms"),
                    etag = if (item.isNull("etag")) null else item.getString("etag"),
                    sha256 = item.getString("sha256")
                )
            }
            return AcceptedSyncState(
                libraryId = root.getString("library_id"),
                generation = root.getLong("generation"),
                manifestSha256 = root.getString("manifest_sha256"),
                manifestLastModifiedMs = root.getLong("manifest_last_modified_ms"),
                manifestEtag = if (root.isNull("manifest_etag")) {
                    null
                } else {
                    root.getString("manifest_etag")
                },
                shards = shards
            )
        }

        fun encode(state: AcceptedSyncState): String {
            val shards = JSONArray()
            state.shards.values.forEach { shard ->
                shards.put(
                    JSONObject().apply {
                        put("shard_id", shard.shardId)
                        put("revision", shard.revision)
                        put("relative_url", shard.relativeUrl)
                        put("last_modified_ms", shard.lastModifiedMs)
                        if (shard.etag == null) {
                            put("etag", JSONObject.NULL)
                        } else {
                            put("etag", shard.etag)
                        }
                        put("sha256", shard.sha256)
                    }
                )
            }
            return JSONObject().apply {
                put("library_id", state.libraryId)
                put("generation", state.generation)
                put("manifest_sha256", state.manifestSha256)
                put("manifest_last_modified_ms", state.manifestLastModifiedMs)
                if (state.manifestEtag == null) {
                    put("manifest_etag", JSONObject.NULL)
                } else {
                    put("manifest_etag", state.manifestEtag)
                }
                put("shards", shards)
            }.toString()
        }
    }
}
