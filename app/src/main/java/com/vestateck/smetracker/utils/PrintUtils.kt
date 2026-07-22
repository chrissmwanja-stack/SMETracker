// utils/PrintUtils.kt
package com.vestateck.smetracker.utils

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hands an already-rendered PDF file (from ReceiptRenderer or
 * ReportPdfRenderer - both already produce a real PDF on disk for
 * share/save) to Android's system print dialog, which lets the user pick
 * any printer the device already knows about (Wi-Fi/Bluetooth receipt
 * printer, PDF-to-file "printer", etc.) - same file, one more destination
 * alongside Share and Save.
 *
 * Deliberately NOT a Composable/UI concern: this is a thin wrapper around
 * PrintManager, callable from anywhere a Context and a built PDF File are
 * available, mirroring FileShareUtils.shareFile's plain-function shape.
 */
object PrintUtils {

    /**
     * [jobName] is shown to the user in the system print UI (e.g. the
     * printer queue) - use something identifying, like a receipt number or
     * report title.
     */
    fun printPdf(context: Context, file: File, jobName: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(
            jobName,
            PdfDocumentAdapter(file),
            PrintAttributes.Builder().build()
        )
    }

    /**
     * Streams the bytes of an already-finished PDF straight through to
     * whatever destination the print framework hands us - no re-rendering,
     * since ReceiptRenderer/ReportPdfRenderer already did that work. This
     * mirrors the standard "print a PDF I already have" recipe (the
     * alternative - rendering a PrintedPdfDocument page-by-page - is only
     * needed when there's no PDF yet, which isn't our case here).
     */
    private class PdfDocumentAdapter(private val file: File) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination?.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }
}