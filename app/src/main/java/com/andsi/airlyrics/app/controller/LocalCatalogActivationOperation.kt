package com.andsi.airlyrics.app.controller

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.catalog.LocalCatalogActivateResult
import com.andsi.airlyrics.lyrics.catalog.LocalCatalogActivator
import com.andsi.airlyrics.lyrics.catalog.SafPublishTreeCopier
import java.io.File

internal fun interface CatalogActivationOperation {
    fun activateFromTree(treeUri: Uri): LocalCatalogActivateResult
}

internal class LocalCatalogActivationOperation(
    context: Context
) : CatalogActivationOperation {
    private val appContext = context.applicationContext

    override fun activateFromTree(treeUri: Uri): LocalCatalogActivateResult {
        val staging = File(appContext.cacheDir, "library-publish-import")
        staging.deleteRecursively()
        return try {
            SafPublishTreeCopier.copyToDirectory(appContext, treeUri, staging)
                ?: LocalCatalogActivator.activateFromPublishDirectory(appContext, staging)
        } finally {
            staging.deleteRecursively()
        }
    }
}
