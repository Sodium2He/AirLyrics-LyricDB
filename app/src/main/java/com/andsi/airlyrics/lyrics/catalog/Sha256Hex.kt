package com.andsi.airlyrics.lyrics.catalog

import java.io.File
import java.security.MessageDigest

object Sha256Hex {
    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun ofBytes(bytes: ByteArray): String {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun hex(digest: ByteArray): String {
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }
}
