// screens/components/ReportPdfExport.kt
package com.vestateck.smetracker.screens.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vestateck.smetracker.utils.FileShareUtils
import com.vestateck.smetracker.utils.PrintUtils
import com.vestateck.smetracker.utils.ReportPdfData
import com.vestateck.smetracker.utils.ReportPdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the working/status state and share/save callbacks for exporting a
 * report as PDF - same idea as SaleReceiptScreen's private helper functions,
 * but shared across all five report screens instead of duplicated five
 * times. [fileName] passed to share()/save() should be filesystem-safe (no
 * need to add ".pdf" - that's appended where needed).
 */
class ReportPdfExportState internal constructor(
    val isWorking: Boolean,
    val statusMessage: String?,
    val share: (ReportPdfData, String) -> Unit,
    val save: (ReportPdfData, String) -> Unit,
    val print: (ReportPdfData, String) -> Unit
)

@Composable
fun rememberReportPdfExportState(): ReportPdfExportState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<ReportPdfData, String>?>(null) }

    fun doSave(data: ReportPdfData, fileName: String) {
        scope.launch {
            isWorking = true
            statusMessage = null
            val success = withContext(Dispatchers.IO) {
                val file = ReportPdfRenderer.buildPdfFile(context, data, fileName)
                FileShareUtils.saveToDownloads(context, file, "$fileName.pdf", "application/pdf")
            }
            isWorking = false
            statusMessage = if (success) "Saved to Downloads." else "Couldn't save the report - please try again."
        }
    }

    // Mirrors SaleReceiptScreen's storage-permission flow: only needed below
    // API 29, where saveToDownloads can't use MediaStore/scoped storage.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingSave
        pendingSave = null
        if (granted && pending != null) {
            doSave(pending.first, pending.second)
        } else if (pending != null) {
            statusMessage = "Storage permission is needed to save the report."
        }
    }

    return ReportPdfExportState(
        isWorking = isWorking,
        statusMessage = statusMessage,
        share = { data, fileName ->
            scope.launch {
                isWorking = true
                val file = withContext(Dispatchers.IO) { ReportPdfRenderer.buildPdfFile(context, data, fileName) }
                FileShareUtils.shareFile(context, file, "application/pdf", chooserTitle = "Share report")
                isWorking = false
            }
        },
        save = { data, fileName ->
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                pendingSave = data to fileName
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                doSave(data, fileName)
            }
        },
        print = { data, fileName ->
            scope.launch {
                isWorking = true
                val file = withContext(Dispatchers.IO) { ReportPdfRenderer.buildPdfFile(context, data, fileName) }
                PrintUtils.printPdf(context, file, data.reportTitle)
                isWorking = false
            }
        }
    )
}

/** TopAppBar action pair for exporting a report: Share and Save-to-Downloads icons. */
@Composable
fun ReportPdfExportActions(
    export: ReportPdfExportState,
    data: ReportPdfData?,
    fileName: String
) {
    if (export.isWorking) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        return
    }
    IconButton(onClick = { data?.let { export.print(it, fileName) } }, enabled = data != null) {
        Icon(Icons.Filled.Print, contentDescription = "Print report")
    }
    IconButton(onClick = { data?.let { export.share(it, fileName) } }, enabled = data != null) {
        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Share report as PDF")
    }
    IconButton(onClick = { data?.let { export.save(it, fileName) } }, enabled = data != null) {
        Icon(Icons.Filled.Save, contentDescription = "Save report to Downloads")
    }
}