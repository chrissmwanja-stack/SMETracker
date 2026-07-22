package com.vestateck.smetracker.utils

/**
 * Minimal CSV text parser — no external dependency needed for something
 * this small. Handles the cases a spreadsheet export can actually produce:
 * double-quoted fields (with embedded commas, newlines, and escaped ""
 * quotes), CRLF or LF line endings, and a leading UTF-8 BOM (Excel likes to
 * add one). Not a full RFC 4180 implementation, but covers everything a
 * shop owner's CSV/Google Sheets export will contain.
 */
object CsvParser {

    fun parse(text: String): List<List<String>> {
        val cleaned = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        val n = cleaned.length

        fun endField() {
            row.add(field.toString())
            field = StringBuilder()
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < n) {
            val c = cleaned[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && cleaned[i + 1] == '"' -> {
                        field.append('"')
                        i++ // consume the escaped quote's second character
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> endField()
                    '\r' -> { /* skip - the following \n (if any) ends the row */ }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        // Handle a final line with no trailing newline.
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        // Drop rows that are just an artifact of a trailing blank line.
        return rows.filterNot { it.size == 1 && it[0].isBlank() }
    }
}