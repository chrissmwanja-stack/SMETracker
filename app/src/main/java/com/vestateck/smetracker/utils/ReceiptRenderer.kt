// utils/ReceiptRenderer.kt
package com.vestateck.smetracker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.vestateck.smetracker.data.entities.PaymentMethod
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.data.remote.model.Business
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line item as it should appear on the printed/shared receipt. */
data class ReceiptLineData(
    val description: String,
    val quantity: Int,
    val amount: Double
)

/**
 * Everything needed to render a receipt for one "checkout" - i.e. every Sale
 * row created together by a single AddSaleScreen submission (see
 * SMEViewModel.addSaleLines). Those rows share one customer, date, payment
 * method, AND receipt number by construction - addSaleLines claims one
 * provisionalReceiptNumber for the whole checkout and SaleSync.pushPending
 * claims one finalReceiptNumber per group of rows that share it (see both
 * doc comments), even though each product is still booked as its own Sale
 * row. Reading it off `sales.first()` here is just convenience, not a
 * "pick one of several different numbers" choice.
 */
data class ReceiptData(
    val businessName: String,
    val businessPhone: String,
    val businessAddress: String,
    val receiptNumber: String,
    val isProvisional: Boolean,
    val customerName: String,
    val dateMillis: Long,
    val paymentMethod: PaymentMethod,
    val lines: List<ReceiptLineData>,
    val total: Double
) {
    companion object {
        fun from(business: Business?, sales: List<Sale>): ReceiptData? {
            if (sales.isEmpty()) return null
            val first = sales.first()
            val finalNumber = first.finalReceiptNumber
            return ReceiptData(
                businessName = business?.name?.ifBlank { null } ?: "SME Tracker",
                businessPhone = business?.ownerPhone ?: "",
                businessAddress = business?.address ?: "",
                receiptNumber = if (finalNumber.isNullOrBlank()) first.provisionalReceiptNumber else finalNumber,
                isProvisional = finalNumber.isNullOrBlank(),
                customerName = first.customerName,
                dateMillis = first.date,
                paymentMethod = first.paymentMethod,
                lines = sales.map { ReceiptLineData(it.description, it.quantity, it.amount) },
                total = sales.sumOf { it.amount }
            )
        }
    }
}

/**
 * Renders a ReceiptData onto a plain android.graphics.Canvas - deliberately
 * NOT a Compose capture. The same draw() pass produces the on-screen share
 * image (a Bitmap) and the PDF page, so the two outputs can never drift out
 * of sync with each other, and there's no dependency on the Compose
 * graphics-layer capture APIs.
 *
 * Layout is intentionally simple (single-line, non-wrapping text, ellipsized
 * if too long) rather than doing real text layout/wrapping - this is a
 * receipt slip, not a formatted document, and every field on it is already
 * short by construction (product names, a name, an amount).
 */
object ReceiptRenderer {

    // Baseline width all other measurements are proportioned against - see
    // draw()'s `scale`. 380 approximates an 80mm thermal receipt's usable
    // width in points, which is also a reasonable narrow-column look for a
    // phone-shared image.
    private const val BASE_WIDTH = 380f
    private const val MARGIN = 20f

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())

    private fun formatUgx(amount: Double): String = CurrencyUtils.formatUgx(amount)
    private fun formatNumber(amount: Double): String = CurrencyUtils.formatNumber(amount)

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    /**
     * Draws the full receipt starting at y=0 on [canvas], sized for the
     * given [width] (already scaled by the caller). Pass canvas = null to
     * run the exact same layout pass purely to measure the total height
     * needed - callers use that to size a Bitmap/PDF page before drawing
     * for real, so there's only ever one layout implementation.
     */
    private fun draw(canvas: Canvas?, width: Float, data: ReceiptData): Float {
        val scale = width / BASE_WIDTH
        val left = MARGIN * scale
        val right = width - MARGIN * scale
        val center = width / 2f
        var y = MARGIN * scale

        fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, mono: Boolean = true) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * scale
                isFakeBoldText = bold
                this.color = color
                // Monospace gives the printed-slip look the styling is going
                // for; titlePaint stays on the default typeface since a
                // business name in a fixed-width font tends to look cramped.
                if (mono) typeface = Typeface.MONOSPACE
            }

        val titlePaint = paint(18f, bold = true, mono = false)
        val bodyPaint = paint(12f)
        val headerPaint = paint(11f, bold = true, color = Color.DKGRAY)
        val smallPaint = paint(10f, color = Color.DKGRAY)
        val boldPaint = paint(13f, bold = true)
        val dividerPaint = paint(1f, color = Color.DKGRAY).apply {
            strokeWidth = 1f
            pathEffect = DashPathEffect(floatArrayOf(4f * scale, 3f * scale), 0f)
        }
        val boxPaint = paint(1f, color = Color.BLACK).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * scale }

        fun centerText(text: String, p: Paint) {
            canvas?.drawText(text, center - p.measureText(text) / 2f, y, p)
        }
        fun leftText(text: String, p: Paint, maxWidth: Float = right - left) {
            canvas?.drawText(ellipsize(text, maxWidth, p), left, y, p)
        }
        fun rightText(text: String, p: Paint, rightEdge: Float = right) {
            canvas?.drawText(text, rightEdge - p.measureText(text), y, p)
        }
        fun dashedDivider() {
            canvas?.drawLine(left, y, right, y, dividerPaint)
        }

        centerText(data.businessName, titlePaint)
        y += 22f * scale
        if (data.businessAddress.isNotBlank()) {
            centerText(data.businessAddress, smallPaint)
            y += 14f * scale
        }
        if (data.businessPhone.isNotBlank()) {
            centerText(data.businessPhone, smallPaint)
            y += 14f * scale
        }
        y += 10f * scale

        // Boxed "SALES RECEIPT" label, mirroring the boxed "TAX INVOICE"
        // header a printed till slip has - purely a visual echo, since this
        // app doesn't issue tax invoices (no fiscal/EFRIS data below).
        val boxLabel = "SALES RECEIPT"
        val boxLabelPaint = paint(13f, bold = true)
        val boxTextWidth = boxLabelPaint.measureText(boxLabel)
        val boxPadH = 14f * scale
        val boxPadV = 8f * scale
        val boxWidth = boxTextWidth + boxPadH * 2f
        val boxLeft = center - boxWidth / 2f
        val boxHeight = boxLabelPaint.textSize + boxPadV * 2f
        canvas?.drawRect(boxLeft, y, boxLeft + boxWidth, y + boxHeight, boxPaint)
        y += boxPadV + boxLabelPaint.textSize * 0.75f
        centerText(boxLabel, boxLabelPaint)
        y += boxHeight - (boxPadV + boxLabelPaint.textSize * 0.75f)
        y += 16f * scale

        val receiptLabel = "Receipt #: ${data.receiptNumber}" + if (data.isProvisional) " (provisional)" else ""
        leftText(receiptLabel, smallPaint)
        y += 14f * scale
        leftText("Date: ${dateFormat.format(Date(data.dateMillis))}", smallPaint)
        y += 14f * scale
        leftText("Customer: ${data.customerName}", smallPaint)
        y += 14f * scale
        leftText("Payment: ${data.paymentMethod.name.replace("_", " ")}", smallPaint)
        y += 16f * scale
        dashedDivider()
        y += 16f * scale

        // Column layout: Item (flexible) | Qty | Price | Amount - Qty/Price/
        // Amount get fixed right-anchored columns, same shape as the Ecomart/
        // Masters slips' item tables.
        val priceColWidth = 62f * scale
        val amountColWidth = 68f * scale
        val amountColRight = right
        val priceColRight = amountColRight - amountColWidth
        val qtyColRight = priceColRight - priceColWidth
        val itemMaxWidth = qtyColRight - 8f * scale - left

        leftText("Item", headerPaint, maxWidth = itemMaxWidth)
        rightText("Qty", headerPaint, rightEdge = qtyColRight)
        rightText("Price", headerPaint, rightEdge = priceColRight)
        rightText("Amount", headerPaint, rightEdge = amountColRight)
        y += 14f * scale
        dashedDivider()
        y += 16f * scale

        data.lines.forEach { line ->
            val unitPrice = if (line.quantity != 0) line.amount / line.quantity else line.amount
            leftText(line.description, bodyPaint, maxWidth = itemMaxWidth)
            rightText(line.quantity.toString(), bodyPaint, rightEdge = qtyColRight)
            rightText(formatNumber(unitPrice), bodyPaint, rightEdge = priceColRight)
            rightText(formatNumber(line.amount), bodyPaint, rightEdge = amountColRight)
            y += 18f * scale
        }

        y += 4f * scale
        dashedDivider()
        y += 20f * scale

        leftText("TOTAL", boldPaint)
        rightText(formatUgx(data.total), boldPaint)
        y += 24f * scale

        centerText("Thank you, please come again!", smallPaint)
        y += 14f * scale
        centerText("Powered by SME Tracker", smallPaint)
        y += 16f * scale

        return y
    }

    /** Higher pixel width than BASE_WIDTH so the shared/saved image is crisp on a phone screen. */
    fun buildBitmap(data: ReceiptData, pixelWidth: Int = 760): Bitmap {
        val width = pixelWidth.toFloat()
        val height = draw(null, width, data)
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas, width, data)
        return bitmap
    }

    private fun receiptsDir(context: Context): File =
        File(context.cacheDir, "receipts").apply { mkdirs() }

    fun buildImageFile(context: Context, data: ReceiptData, fileName: String): File {
        val bitmap = buildBitmap(data)
        val file = File(receiptsDir(context), "$fileName.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun buildPdfFile(context: Context, data: ReceiptData, fileName: String): File {
        val width = BASE_WIDTH
        val height = draw(null, width, data)
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(width.toInt(), height.toInt(), 1).create()
        val page = pdf.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        draw(page.canvas, width, data)
        pdf.finishPage(page)

        val file = File(receiptsDir(context), "$fileName.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    /** Opens the system share sheet for [file] via a FileProvider content:// Uri. */
    fun shareFile(context: Context, file: File, mimeType: String) =
        FileShareUtils.shareFile(context, file, mimeType, chooserTitle = "Share receipt")

    /** Copies [file] into the device's public Downloads folder - see FileShareUtils for details. */
    fun saveToDownloads(context: Context, file: File, displayName: String, mimeType: String): Boolean =
        FileShareUtils.saveToDownloads(context, file, displayName, mimeType)
}