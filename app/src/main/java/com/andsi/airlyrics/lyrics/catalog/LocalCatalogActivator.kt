package com.andsi.airlyrics.lyrics.catalog

import android.content.Context
import java.io.File

sealed class LocalCatalogActivateResult {
    data class Activated(
        val libraryId: String,
        val generation: Long,
        val shardCount: Int
    ) : LocalCatalogActivateResult()

    data class Failed(val reason: Reason) : LocalCatalogActivateResult()

    enum class Reason {
        MANIFEST_MISSING,
        MANIFEST_INVALID,
        SHARD_MISSING,
        SHARD_SIZE,
        SHARD_HASH,
        LIBRARY_MISMATCH,
        GENERATION_OLDER,
        ACTIVATE_FAILED
    }
}

object LocalCatalogActivator {
    const val MANIFEST_FILE = "manifest.json"
    const val SHARDS_DIR = "shards"

    fun activateFromPublishDirectory(
        context: Context,
        publishRoot: File,
        force: Boolean = false
    ): LocalCatalogActivateResult {
        val manifestFile = File(publishRoot, MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.MANIFEST_MISSING)
        }
        val manifest = try {
            PublishManifestParser.parse(manifestFile.readText())
        } catch (_: Exception) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.MANIFEST_INVALID)
        }

        val sources = LinkedHashMap<String, File>()
        for (shard in manifest.shards) {
            val source = try {
                PublishRelativeUrl.resolve(publishRoot, shard.relativeUrl)
            } catch (_: Exception) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_MISSING)
            }
            if (!source.isFile) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_MISSING)
            }
            if (source.length() != shard.byteSize) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_SIZE)
            }
            val hash = Sha256Hex.ofFile(source)
            if (!hash.equals(shard.sha256, ignoreCase = true)) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_HASH)
            }
            sources[shard.shardId] = source
        }

        val catalogFile = LibraryCatalog.catalogFile(context)
        catalogFile.parentFile?.mkdirs()
        var previousFiles: Set<File> = emptySet()
        val current = if (catalogFile.isFile) {
            LibraryCatalog.open(catalogFile).use {
                previousFiles = it.shardFiles()
                it.currentMeta()
            }
        } else {
            null
        }
        if (!force && current != null && current.libraryId != manifest.libraryId) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.LIBRARY_MISMATCH)
        }
        if (!force && current != null && manifest.generation < current.generation) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.GENERATION_OLDER)
        }
        if (!force && current != null &&
            current.libraryId == manifest.libraryId &&
            current.generation == manifest.generation
        ) {
            return LocalCatalogActivateResult.Activated(
                libraryId = manifest.libraryId,
                generation = manifest.generation,
                shardCount = manifest.shards.size
            )
        }

        val libraryDir = LibraryCatalog.directory(context)
        val localShards = LinkedHashMap<String, File>()
        try {
            for (shard in manifest.shards) {
                val dest = File(
                    File(libraryDir, SHARDS_DIR),
                    "${PublishRelativeUrl.token(shard.shardId)}/${PublishRelativeUrl.token(shard.revision)}${if (force) "-repair-${java.util.UUID.randomUUID()}" else ""}.sqlite"
                )
                val source = sources.getValue(shard.shardId)
                if (!(dest.isFile &&
                        dest.length() == shard.byteSize &&
                        Sha256Hex.ofFile(dest).equals(shard.sha256, ignoreCase = true))
                ) {
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, dest.name + ".tmp")
                    source.copyTo(tmp, overwrite = true)
                    if (tmp.length() != shard.byteSize ||
                        !Sha256Hex.ofFile(tmp).equals(shard.sha256, ignoreCase = true)
                    ) {
                        tmp.delete()
                        return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_HASH)
                    }
                    if (dest.exists() && !dest.delete()) {
                        tmp.delete()
                        return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.ACTIVATE_FAILED)
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
                localShards[shard.shardId] = dest
            }

            LibraryCatalog.open(catalogFile).use { catalog ->
                catalog.activate(manifest, localShards, force)
            }
        } catch (_: Exception) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.ACTIVATE_FAILED)
        }

        // Keep prior files alive for readers that still hold the previous generation.
        cleanupUnreferenced(File(libraryDir, SHARDS_DIR), localShards.values.toSet() + previousFiles)
        context.sendBroadcast(android.content.Intent(LibraryCatalog.ACTION_CHANGED).setPackage(context.packageName))
        return LocalCatalogActivateResult.Activated(
            libraryId = manifest.libraryId,
            generation = manifest.generation,
            shardCount = manifest.shards.size
        )
    }

    private fun cleanupUnreferenced(shardsRoot: File, keep: Set<File>) {
        if (!shardsRoot.isDirectory) return
        val keepPaths = keep.map { it.canonicalFile }.toSet()
        shardsRoot.walkTopDown()
            .filter { it.isFile && it.extension == "sqlite" }
            .forEach { file ->
                if (file.canonicalFile !in keepPaths) {
                    file.delete()
                }
            }
    }
}
