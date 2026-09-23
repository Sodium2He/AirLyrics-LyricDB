package com.andsi.airlyrics.lyrics.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavProtocolHelpersTest {
    @Test
    fun urlResolve_keepsEncodedHashSegments() {
        val manifest = "https://dav.example/AirLyrics/manifest.json"
        assertEquals(
            "https://dav.example/AirLyrics/",
            WebDavUrl.folderBase(manifest)
        )
        assertEquals(
            "https://dav.example/AirLyrics/shards/depth3/Album/Instrumental%2B/%23%20anime/r1.sqlite",
            WebDavUrl.resolve(
                manifest,
                "shards/depth3/Album/Instrumental%2B/%23%20anime/r1.sqlite"
            )
        )
    }

    @Test
    fun httpDate_parsesRfc1123AndRejectsBlank() {
        val parsed = HttpDate.parseToEpochMs("Wed, 21 Sep 2026 01:00:00 GMT")
        assertEquals(java.time.Instant.parse("2026-09-21T01:00:00Z").toEpochMilli(), parsed)
        assertNull(HttpDate.parseToEpochMs(null))
        assertNull(HttpDate.parseToEpochMs("not-a-date"))
        assertEquals(
            java.time.LocalDateTime.of(2026, 9, 21, 1, 47).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
            HttpDate.parseToEpochMs("9/21/2026 1:47 AM")
        )
        assertTrue(
            HttpDate.parseToEpochMs("Wed, 21 Sep 2026 03:00:00 GMT")!! >
                HttpDate.parseToEpochMs("Wed, 21 Sep 2026 01:00:00 GMT")!!
        )
    }

    @Test
    fun etag_weakIsNotStrong() {
        assertTrue(HttpDate.isStrongEtag("\"abc\""))
        assertFalse(HttpDate.isStrongEtag("W/\"abc\""))
        assertFalse(HttpDate.isStrongEtag(null))
    }

    @Test
    fun propfindParser_readsModifiedAndCollection() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:propstat>
                  <D:prop>
                    <D:getlastmodified>Wed, 21 Sep 2026 01:00:00 GMT</D:getlastmodified>
                    <D:getetag>"x"</D:getetag>
                    <D:getcontentlength>12</D:getcontentlength>
                    <D:resourcetype/>
                  </D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val probe = WebDavProbeParser.fromPropfind(207, xml)
        assertEquals("Wed, 21 Sep 2026 01:00:00 GMT", probe.lastModifiedHttp)
        assertEquals("\"x\"", probe.etag)
        assertEquals(12L, probe.contentLength)
        assertFalse(probe.isCollection)

        val collection = WebDavProbeParser.fromPropfind(
            207,
            "<prop><resourcetype><collection/></resourcetype></prop>"
        )
        assertTrue(collection.isCollection)
    }
}
