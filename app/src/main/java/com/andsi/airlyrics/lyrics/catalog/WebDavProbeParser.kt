package com.andsi.airlyrics.lyrics.catalog

object WebDavUrl {
    fun folderBase(manifestUrl: String): String {
        val trimmed = manifestUrl.trim()
        require(trimmed.isNotEmpty()) { "empty manifest url" }
        return if (trimmed.endsWith("manifest.json", ignoreCase = true)) {
            trimmed.substringBeforeLast('/') + "/"
        } else if (trimmed.endsWith("/")) {
            trimmed
        } else {
            "$trimmed/"
        }
    }

    fun resolve(manifestUrl: String, relativeUrl: String): String {
        val base = folderBase(manifestUrl)
        val relative = relativeUrl.trim().removePrefix("/")
        return base + relative
    }
}

object WebDavProbeParser {
    fun fromHeaders(statusCode: Int, headers: Map<String, List<String>>): RemoteProbe {
        return RemoteProbe(
            statusCode = statusCode,
            lastModifiedHttp = header(headers, "Last-Modified"),
            etag = header(headers, "ETag"),
            contentLength = header(headers, "Content-Length")?.toLongOrNull(),
            isCollection = false
        )
    }

    fun fromPropfind(statusCode: Int, xml: String): RemoteProbe {
        val collection = xml.contains("<collection", ignoreCase = true) ||
            xml.contains(":collection", ignoreCase = true)
        return RemoteProbe(
            statusCode = statusCode,
            lastModifiedHttp = xmlTag(xml, "getlastmodified"),
            etag = xmlTag(xml, "getetag"),
            contentLength = xmlTag(xml, "getcontentlength")?.toLongOrNull(),
            isCollection = collection
        )
    }

    private fun header(headers: Map<String, List<String>>, name: String): String? {
        val match = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) } ?: return null
        return match.value.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun xmlTag(xml: String, localName: String): String? {
        val regex = Regex(
            """<(?:[\w-]+:)?$localName\b[^>]*>([^<]*)</(?:[\w-]+:)?$localName>""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
