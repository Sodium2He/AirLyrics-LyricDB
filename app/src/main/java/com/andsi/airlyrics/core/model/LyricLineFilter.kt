package com.andsi.airlyrics.core.model

data class LyricLineFilter(
    val enabled: Boolean = false,
    val characters: String = ".·・…",
    val filterEmpty: Boolean = true
) {
    fun apply(raw: String): String {
        if (!enabled) return raw
        val ignored = characters.codePoints().toArray().toSet()
        return raw.lineSequence().filter { line ->
            if (!lineTime.containsMatchIn(line)) return@filter true
            val text = line.replace(timing, "")
            !(text.isEmpty() && filterEmpty || text.isNotEmpty() &&
                text.codePoints().toArray().all { it in ignored })
        }.joinToString("\n")
    }

    companion object {
        private val lineTime = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")
        private val timing = Regex("""[\[<]\d{1,2}:\d{2}(?:[.:]\d{1,3})?[\]>]""")
    }
}
