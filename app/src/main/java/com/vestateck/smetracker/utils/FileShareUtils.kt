// utils/FileShareUtils.kt
package com.vestateck.smetracker.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Generic "hand a File to the outside world" helpers - originally lived
 * inside ReceiptRenderer, pulled out here so ReportPdfRenderer can share the
 * same FileProvider authority string and MediaStore/legacy-storage branch
 * instead of duplicating it. Neither caller needs to know how the file gets
 * shared/saved, just that it can be.
 */
object FileShareUtils {

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Opens the system share sheet for [file] via a FileProvider content:// Uri. */
    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String = "Share") {
        val uri = uriFor(context, file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
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
     * the caller is responsible for requesting it on those OS versions
     * before calling this (see SaleReceiptScreen for the request flow).
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