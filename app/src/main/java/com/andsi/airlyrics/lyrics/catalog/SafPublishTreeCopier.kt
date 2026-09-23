package com.andsi.airlyrics.lyrics.catalog

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object SafPublishTreeCopier {
    fun copyToDirectory(
        context: Context,
        treeUri: Uri,
        dest: File
    ): LocalCatalogActivateResult.Failed? {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.MANIFEST_MISSING)
        val manifestDoc = root.findFile(LocalCatalogActivator.MANIFEST_FILE)
            ?: return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.MANIFEST_MISSING)
        dest.mkdirs()
        val manifestFile = File(dest, LocalCatalogActivator.MANIFEST_FILE)
        if (!copyFile(context, manifestDoc, manifestFile)) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.MANIFEST_MISSING)
        }
        val manifest = try {
            PublishManifestParser.parse(manifestFile.readText())
        } catch (_: Exception) {
            return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.MANIFEST_INVALID)
        }
        for (shard in manifest.shards) {
            val segments = try {
                PublishRelativeUrl.decodeSegments(shard.relativeUrl)
            } catch (_: Exception) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_MISSING)
            }
            var current: DocumentFile = root
            for (segment in segments) {
                current = current.findFile(segment)
                    ?: return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_MISSING)
            }
            if (!current.isFile) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_MISSING)
            }
            val out = PublishRelativeUrl.resolve(dest, shard.relativeUrl)
            out.parentFile?.mkdirs()
            if (!copyFile(context, current, out)) {
                return LocalCatalogActivateResult.Failed(LocalCatalogActivateResult.Reason.SHARD_MISSING)
            }
        }
        return null
    }

    private fun copyFile(context: Context, source: DocumentFile, dest: File): Boolean {
        val input = context.contentResolver.openInputStream(source.uri) ?: return false
        input.use { stream ->
            dest.outputStream().use { output ->
                stream.copyTo(output)
            }
        }
        return dest.isFile
    }
}
