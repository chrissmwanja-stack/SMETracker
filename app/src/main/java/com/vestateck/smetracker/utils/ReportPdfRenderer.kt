// utils/ReportPdfRenderer.kt
package com.vestateck.smetracker.utils

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single label/value line in a summary block, e.g. "This Month" -> "UGX 450,000". */
data class ReportSummaryRow(val label: String, val value: String, val emphasize: Boolean = false)

/**
 * One itemized table. [weights] must be the same size as [headers] and
 * describes each column's share of the usable table width - they don't need
 * to sum to 1, they're normalized at draw time (so [1f, 2f, 1f] behaves the
 * same as [0.25f, 0.5f, 0.25f]). [rightAlignedColumns] are the column indices
 * to right-align (typically amount/quantity columns).
 */
data class ReportTable(
    val heading: String? = null,
    val headers: List<String>,
    val rows: List<List<String>>,
    val weights: List<Float> = List(headers.size) { 1f / headers.size },
    val rightAlignedColumns: Set<Int> = emptySet()
)

/** One section of the report: an optional heading, an optional summary block, and/or tables. */
data class ReportSection(
    val heading: String? = null,
    val summaryRows: List<ReportSummaryRow> = emptyList(),
    val tables: List<ReportTable> = emptyList()
)

data class ReportPdfData(
    val businessName: String,
    val businessAddress: String,
    val businessPhone: String,
    val reportTitle: String,
    val generatedAtMillis: Long = System.currentTimeMillis(),
    val sections: List<ReportSection>
)

/**
 * Renders a ReportPdfData into a paginated, multi-page PDF. Unlike
 * ReceiptRenderer (one continuous canvas sized to fit everything, since a
 * receipt is always short), an itemized report can run to many pages, so
 * this tracks a running y position via ensureSpace() and starts a fresh
 * PdfDocument page - redrawing the business/report header chrome - whenever
 * the next piece of content would run past the bottom margin.
 */
object ReportPdfRenderer {

    // A4 at 72pt/inch.
    private const val PAGE_WIDTH = 595f
    private const val PAGE_HEIGHT = 842f
    private const val MARGIN = 36f
    private const val CONTENT_TOP = MARGIN + 56f // leaves room for the repeated header chrome
    private const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            isFakeBoldText = bold
            this.color = color
        }

    private fun ellipsize(text: String, maxWidth: Float, p: Paint): String {
        if (maxWidth <= 0f || p.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && p.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun reportsDir(context: Context): File =
        File(context.cacheDir, "reports").apply { mkdirs() }

    fun buildPdfFile(context: Context, data: ReportPdfData, fileName: String): File {
        val pdf = PdfDocument()
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create())
        var canvas = page.canvas
        var y = CONTENT_TOP

        fun drawChrome() {
            val titlePaint = paint(15f, bold = true)
            val subtitlePaint = paint(11f, color = Color.DKGRAY)
            val smallPaint = paint(9f, color = Color.DKGRAY)
            canvas.drawText(data.businessName, MARGIN, MARGIN + 14f, titlePaint)
            canvas.drawText(data.reportTitle, MARGIN, MARGIN + 32f, subtitlePaint)
            val genText = "Generated ${dateFormat.format(Date(data.generatedAtMillis))} · Page $pageNumber"
            canvas.drawText(genText, PAGE_WIDTH - MARGIN - smallPaint.measureText(genText), MARGIN + 14f, smallPaint)
            val contactLine = listOf(data.businessAddress, data.businessPhone).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (contactLine.isNotBlank()) {
                canvas.drawText(contactLine, PAGE_WIDTH - MARGIN - smallPaint.measureText(contactLine), MARGIN + 30f, smallPaint)
            }
            canvas.drawLine(MARGIN, MARGIN + 42f, PAGE_WIDTH - MARGIN, MARGIN + 42f, paint(1f, color = Color.LTGRAY))
        }

        fun newPage() {
            pdf.finishPage(page)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create())
            canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            y = CONTENT_TOP
            drawChrome()
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > CONTENT_BOTTOM) newPage()
        }

        canvas.drawColor(Color.WHITE)
        drawChrome()

        fun sectionHeading(text: String) {
            ensureSpace(24f)
            canvas.drawText(text, MARGIN, y, paint(12.5f, bold = true))
            y += 8f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint(1f, color = Color.LTGRAY))
            y += 16f
        }

        fun summaryRow(row: ReportSummaryRow) {
            ensureSpace(18f)
            val p = paint(11f, bold = row.emphasize)
            canvas.drawText(row.label, MARGIN, y, p)
            canvas.drawText(row.value, PAGE_WIDTH - MARGIN - p.measureText(row.value), y, p)
            y += 18f
        }

        fun table(t: ReportTable) {
            t.heading?.let { sectionHeading(it) }
            val totalWeight = t.weights.sum().takeIf { it > 0f } ?: 1f
            val usableWidth = PAGE_WIDTH - 2 * MARGIN
            val colWidths = t.weights.map { it / totalWeight * usableWidth }

            if (t.rows.isEmpty()) {
                ensureSpace(16f)
                canvas.drawText("No records.", MARGIN, y, paint(10f, color = Color.DKGRAY))
                y += 18f
                return
            }

            fun headerRow() {
                ensureSpace(22f)
                val hp = paint(9.5f, bold = true)
                var x = MARGIN
                t.headers.forEachIndexed { i, h ->
                    val w = colWidths.getOrElse(i) { 0f }
                    if (i in t.rightAlignedColumns) {
                        canvas.drawText(h, x + w - hp.measureText(h), y, hp)
                    } else {
                        canvas.drawText(h, x, y, hp)
                    }
                    x += w
                }
                y += 8f
                canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, paint(1f, color = Color.LTGRAY))
                y += 14f
            }

            headerRow()
            val rowPaint = paint(9.5f)
            t.rows.forEach { row ->
                if (y + 16f > CONTENT_BOTTOM) {
                    newPage()
                    headerRow() // repeat the header on the continuation page
                }
                var x = MARGIN
                row.forEachIndexed { i, cell ->
                    val w = colWidths.getOrElse(i) { 0f }
                    val text = ellipsize(cell, w - 4f, rowPaint)
                    if (i in t.rightAlignedColumns) {
                        canvas.drawText(text, x + w - rowPaint.measureText(text), y, rowPaint)
                    } else {
                        canvas.drawText(text, x, y, rowPaint)
                    }
                    x += w
                }
                y += 16f
            }
            y += 10f
        }

        data.sections.forEach { section ->
            section.heading?.let { sectionHeading(it) }
            section.summaryRows.forEach { summaryRow(it) }
            if (section.summaryRows.isNotEmpty()) y += 6f
            section.tables.forEach { table(it) }
            y += 4f
        }

        pdf.finishPage(page)

        val file = File(reportsDir(context), "$fileName.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}