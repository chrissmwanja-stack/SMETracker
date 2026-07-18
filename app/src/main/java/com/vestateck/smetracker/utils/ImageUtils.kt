package com.vestateck.smetracker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageUtils {

    /**
     * Copies a picked image from a Uri into the app's internal storage, resizing it
     * to a maximum dimension to save space and bandwidth. Returns the absolute
     * path to the new file, or null if it fails.
     */
    fun copyToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) return null

            // Resize if too large - e.g. max 1024px on the longest side
            val maxDimension = 1024
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val (width, height) = if (ratio > 1) {
                    maxDimension to (maxDimension / ratio).toInt()
                } else {
                    (maxDimension * ratio).toInt() to maxDimension
                }
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } else {
                bitmap
            }

            val fileName = "img_${UUID.randomUUID()}.jpg"
            val file = File(context.filesDir, "inventory_photos")
            if (!file.exists()) file.mkdirs()
            
            val outFile = File(file, fileName)
            val outStream = FileOutputStream(outFile)
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
            outStream.flush()
            outStream.close()

            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            outFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Deletes a local photo file if it exists.
     */
    fun deleteLocalCopy(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
