package com.andsi.airlyrics.app.sync

import android.net.Network
import android.util.Base64
import com.andsi.airlyrics.lyrics.catalog.RemoteDownload
import com.andsi.airlyrics.lyrics.catalog.RemoteLibraryClient
import com.andsi.airlyrics.lyrics.catalog.RemoteProbe
import com.andsi.airlyrics.lyrics.catalog.WebDavProbeParser
import com.andsi.airlyrics.lyrics.catalog.WebDavUrl
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class BoundWebDavClient(
    private val network: Network,
    private val manifestUrl: String,
    private val username: String?,
    private val password: String?,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) : RemoteLibraryClient {
    override fun probe(relativeUrl: String, extraHeaders: Map<String, String>): RemoteProbe {
        val url = URL(WebDavUrl.resolve(manifestUrl, relativeUrl))
        val head = open(url, "HEAD", extraHeaders)
        return try {
            val status = head.responseCode
            val fromHead = WebDavProbeParser.fromHeaders(status, head.headerFields)
            if (status == HttpURLConnection.HTTP_BAD_METHOD ||
                status == 501 ||
                status == 207 ||
                (status in 200..299 && fromHead.lastModifiedHttp.isNullOrBlank())
            ) {
                propfind(url, extraHeaders)
            } else {
                fromHead
            }
        } finally {
            head.disconnect()
        }
    }

    override fun download(
        relativeUrl: String,
        dest: File?,
        extraHeaders: Map<String, String>
    ): RemoteDownload {
        val url = URL(WebDavUrl.resolve(manifestUrl, relativeUrl))
        val connection = open(url, "GET", extraHeaders)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                return RemoteDownload(statusCode = status)
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            dest?.parentFile?.mkdirs()
            dest?.writeBytes(bytes)
            RemoteDownload(
                statusCode = status,
                bytes = bytes,
                file = dest,
                etag = connection.getHeaderField("ETag"),
                lastModifiedHttp = connection.getHeaderField("Last-Modified")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun propfind(url: URL, extraHeaders: Map<String, String>): RemoteProbe {
        val connection = open(url, "PROPFIND", extraHeaders)
        return try {
            connection.setRequestProperty("Depth", "0")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(PROPFIND_BODY.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readBytes().decodeToString() }
                .orEmpty()
            WebDavProbeParser.fromPropfind(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        url: URL,
        method: String,
        extraHeaders: Map<String, String>
    ): HttpURLConnection {
        val connection = network.openConnection(url) as HttpURLConnection
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = TunneledSslSocketFactory(network, lanSslContext.socketFactory)
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.useCaches = false
        connection.setRequestProperty("Translate", "f")
        extraHeaders.forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }
        val user = username?.trim().orEmpty()
        if (user.isNotEmpty()) {
            val token = Base64.encodeToString(
                "$user:${password.orEmpty()}".toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )
            connection.setRequestProperty("Authorization", "Basic $token")
        }
        return connection
    }

    private class TunneledSslSocketFactory(
        private val network: Network,
        private val ssl: SSLSocketFactory
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = ssl.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = ssl.supportedCipherSuites

        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket =
            ssl.createSocket(s, host, port, autoClose)

        override fun createSocket(host: String, port: Int): Socket {
            val plain = network.socketFactory.createSocket(host, port)
            return ssl.createSocket(plain, host, port, true)
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val plain = network.socketFactory.createSocket(host, port, localHost, localPort)
            return ssl.createSocket(plain, host, port, true)
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val plain = network.socketFactory.createSocket(host, port)
            return ssl.createSocket(plain, host.hostName, port, true)
        }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket {
            val plain = network.socketFactory.createSocket(address, port, localAddress, localPort)
            return ssl.createSocket(plain, address.hostName, port, true)
        }
    }

    companion object {
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><getlastmodified/><getetag/><getcontentlength/><resourcetype/></prop></propfind>"""

        private val lanSslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(LanTrustAll), SecureRandom())
        }

        private object LanTrustAll : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
