package com.andsi.airlyrics.core.text

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MetadataNormalizerTest {
    private val fixture: JSONObject by lazy { loadFixture("unicode-normalization.json") }

    @Test
    fun primary_matchesSharedUnicodeFixture() {
        val cases = fixture.getJSONArray("primary")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(
                case.getString("id"),
                case.getString("expected"),
                MetadataNormalizer.primary(case.getString("input"))
            )
        }
    }

    @Test
    fun samePrimary_matchesSharedUnicodeFixture() {
        val cases = fixture.getJSONArray("same_primary")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(
                case.getString("id"),
                MetadataNormalizer.primary(case.getString("left")),
                MetadataNormalizer.primary(case.getString("right"))
            )
        }
    }

    @Test
    fun differentPrimary_matchesSharedUnicodeFixture() {
        val cases = fixture.getJSONArray("different_primary")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertNotEquals(
                case.getString("id"),
                MetadataNormalizer.primary(case.getString("left")),
                MetadataNormalizer.primary(case.getString("right"))
            )
        }
    }

    @Test
    fun sameSecondary_matchesSharedUnicodeFixture() {
        val cases = fixture.getJSONArray("same_secondary")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(
                case.getString("id"),
                MetadataNormalizer.secondary(case.getString("left")),
                MetadataNormalizer.secondary(case.getString("right"))
            )
        }
    }

    @Test
    fun differentSecondary_keepsVersionWords() {
        val cases = fixture.getJSONArray("different_secondary")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertNotEquals(
                case.getString("id"),
                MetadataNormalizer.secondary(case.getString("left")),
                MetadataNormalizer.secondary(case.getString("right"))
            )
        }
    }

    private fun loadFixture(name: String): JSONObject {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing shared fixture: $name"
        }
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }
}
