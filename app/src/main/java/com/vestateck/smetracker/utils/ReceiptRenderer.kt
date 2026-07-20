// utils/ReceiptRenderer.kt
package com.vestateck.smetracker.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
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
 * SMEViewModel.addSaleLines). Those rows share one customer, date, and
 * payment method by construction, but each gets its OWN provisional/final
 * receipt number (see Sale's class doc, and SaleSync.pushPending). This
 * surfaces the FIRST line's number as "the" receipt number for the whole
 * transaction - a customer reads one slip, even though the backend books
 * each product as its own row.
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

        fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * scale
                isFakeBoldText = bold
                this.color = color
            }

        val titlePaint = paint(18f, bold = true)
        val bodyPaint = paint(12f)
        val smallPaint = paint(10f, color = Color.DKGRAY)
        val boldPaint = paint(13f, bold = true)
        val dividerPaint = paint(1f, color = Color.LTGRAY).apply { strokeWidth = 1f }

        fun centerText(text: String, p: Paint) {
            canvas?.drawText(text, center - p.measureText(text) / 2f, y, p)
        }
        fun leftText(text: String, p: Paint, maxWidth: Float = right - left) {
            canvas?.drawText(ellipsize(text, maxWidth, p), left, y, p)
        }
        fun divider() {
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
        divider()
        y += 18f * scale

        val receiptLabel = "Receipt #: ${data.receiptNumber}" + if (data.isProvisional) " (provisional)" else ""
        leftText(receiptLabel, smallPaint)
        y += 14f * scale
        leftText("Date: ${dateFormat.format(Date(data.dateMillis))}", smallPaint)
        y += 14f * scale
        leftText("Customer: ${data.customerName}", smallPaint)
        y += 14f * scale
        leftText("Payment: ${data.paymentMethod.name.replace("_", " ")}", smallPaint)
        y += 16f * scale
        divider()
        y += 18f * scale

        data.lines.forEach { line ->
            val amountText = formatUgx(line.amount)
            val amountWidth = bodyPaint.measureText(amountText)
            val qtySuffix = if (line.quantity > 1) " x${line.quantity}" else ""
            leftText(line.description + qtySuffix, bodyPaint, maxWidth = (right - left) - amountWidth - 12f * scale)
            canvas?.drawText(amountText, right - amountWidth, y, bodyPaint)
            y += 18f * scale
        }

        y += 6f * scale
        divider()
        y += 20f * scale

        leftText("TOTAL", boldPaint)
        val totalText = formatUgx(data.total)
        canvas?.drawText(totalText, right - boldPaint.measureText(totalText), y, boldPaint)
        y += 24f * scale

        centerText("Thank you for your business!", smallPaint)
        y += 20f * scale

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

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Opens the system share sheet for [file] via a FileProvider content:// Uri. */
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = uriFor(context, file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Share receipt").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Copies [file] into the device's public Downloads folder so it's
     * visible outside the app (Files app, etc.) - separate from shareFile,
     * which only hands a content:// Uri to whichever app the user picks
     * from the share sheet.
     *
     * API 29+ (Q+) goes through MediaStore/scoped storage - no permission
     * needed. Below that, this needs WRITE_EXTERNAL_STORAGE granted first;
     * the caller (SaleReceiptScreen) is responsible for requesting it on
     * those OS versions before calling this.
     */
    fun saveToDownloads(context: Context, file: File, displayName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val dest = File(downloadsDir, displayName)
                file.inputStream().use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}