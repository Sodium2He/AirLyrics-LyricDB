package com.andsi.airlyrics.lyrics.catalog

import android.database.sqlite.SQLiteDatabase
import java.io.File

object ShardLyricsReader {
    fun read(shardFile: File, trackId: Long): List<ShardLyricRow> {
        val db = SQLiteDatabase.openDatabase(
            shardFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            db.rawQuery(
                """
                SELECT tag_kind, language, description, variant_order, format, raw_text, text_hash, diagnostic
                FROM lyrics
                WHERE track_id = ?
                ORDER BY variant_order
                """.trimIndent(),
                arrayOf(trackId.toString())
            ).use { cursor ->
                val rows = mutableListOf<ShardLyricRow>()
                while (cursor.moveToNext()) {
                    rows += ShardLyricRow(
                        tagKind = cursor.getString(0),
                        language = cursor.getStringOrNull(1),
                        description = cursor.getStringOrNull(2),
                        variantOrder = cursor.getInt(3),
                        format = cursor.getStringOrNull(4),
                        rawText = cursor.getStringOrNull(5),
                        textHash = cursor.getStringOrNull(6),
                        diagnostic = cursor.getStringOrNull(7)
                    )
                }
                return rows
            }
        } finally {
            db.close()
        }
    }

    fun firstPresentText(rows: List<ShardLyricRow>): ShardLyricRow? {
        return rows.firstOrNull { row -> !row.rawText.isNullOrBlank() }
    }
}
