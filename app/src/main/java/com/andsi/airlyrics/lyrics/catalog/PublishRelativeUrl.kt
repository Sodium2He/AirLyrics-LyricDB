package com.andsi.airlyrics.lyrics.catalog

import android.net.Uri
import java.io.File

object PublishRelativeUrl {
    fun decodeSegments(relativeUrl: String): List<String> {
        require(relativeUrl.isNotBlank()) { "empty relative_url" }
        require(!relativeUrl.startsWith("/")) { "absolute relative_url" }
        val segments = relativeUrl.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "empty relative_url" }
        return segments.map { encoded ->
            val decoded = Uri.decode(encoded)
            require(
                decoded.isNotEmpty() &&
                    decoded != "." &&
                    decoded != ".." &&
                    '/' !in decoded &&
                    '\\' !in decoded
            ) {
                "unsafe relative_url segment: $encoded"
            }
            decoded
        }
    }

    fun resolve(publishRoot: File, relativeUrl: String): File {
        return decodeSegments(relativeUrl).fold(publishRoot) { dir, segment -> File(dir, segment) }
    }

    fun token(value: String): String {
        require(
            value.isNotBlank() &&
                value != "." &&
                value != ".." &&
                '/' !in value &&
                '\\' !in value
        ) {
            "unsafe path token: $value"
        }
        return value
    }
}
