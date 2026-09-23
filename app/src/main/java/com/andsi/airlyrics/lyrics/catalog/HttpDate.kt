package com.andsi.airlyrics.lyrics.catalog

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

object HttpDate {
    private val rfc1123 = Regex(
        """^[A-Za-z]{3,9},?\s+(\d{1,2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2})(?: GMT| UT|[ ]?([+-]\d{4}))?$""",
        RegexOption.IGNORE_CASE
    )
    private val usListing = Regex(
        """^(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})\s*([AP]M)$""",
        RegexOption.IGNORE_CASE
    )
    private val months = mapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
        "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12
    )

    fun parseToEpochMs(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseRfc1123(trimmed) ?: parseIso(trimmed) ?: parseUsListing(trimmed)
    }

    fun isStrongEtag(etag: String?): Boolean {
        val value = etag?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return !value.startsWith("W/", ignoreCase = false)
    }

    private fun parseRfc1123(value: String): Long? {
        val match = rfc1123.matchEntire(value) ?: return null
        val month = months[match.groupValues[2].replaceFirstChar { it.titlecase() }] ?: return null
        val offset = parseOffset(match.groupValues.getOrNull(7))
        return runCatching {
            LocalDateTime.of(
                match.groupValues[3].toInt(),
                month,
                match.groupValues[1].toInt(),
                match.groupValues[4].toInt(),
                match.groupValues[5].toInt(),
                match.groupValues[6].toInt()
            ).toInstant(offset).toEpochMilli()
        }.getOrNull()
    }

    private fun parseUsListing(value: String): Long? {
        val match = usListing.matchEntire(value) ?: return null
        val hourRaw = match.groupValues[4].toInt()
        val pm = match.groupValues[6].equals("PM", ignoreCase = true)
        val hour = when {
            pm && hourRaw < 12 -> hourRaw + 12
            !pm && hourRaw == 12 -> 0
            else -> hourRaw
        }
        return runCatching {
            LocalDateTime.of(
                match.groupValues[3].toInt(),
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                hour,
                match.groupValues[5].toInt()
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun parseIso(value: String): Long? {
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun parseOffset(raw: String?): ZoneOffset {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return ZoneOffset.UTC
        return runCatching {
            ZoneOffset.of(value.substring(0, 3) + ":" + value.substring(3))
        }.getOrDefault(ZoneOffset.UTC)
    }
}
