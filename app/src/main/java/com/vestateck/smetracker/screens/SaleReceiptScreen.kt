// screens/SaleReceiptScreen.kt
package com.vestateck.smetracker.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.utils.ReceiptData
import com.vestateck.smetracker.utils.ReceiptRenderer
import com.vestateck.smetracker.utils.PrintUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shown right after AddSaleScreen saves a checkout - see MainActivity's
 * NavHost, which replaces AddSaleScreen with this screen in the back stack
 * (popUpTo .. inclusive), so the back arrow here goes straight to
 * Dashboard, not back into the sale form.
 *
 * Takes just the Sale ids (see Screen.SaleReceipt) and re-reads the actual
 * rows out of viewModel.sales, rather than being handed Sale objects
 * directly - navigation-compose can only carry simple route arguments, and
 * re-reading from the single source of truth (Room, via the ViewModel) is
 * safer than serializing a snapshot into the nav graph anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleReceiptScreen(
    saleIds: List<String>,
    viewModel: SMEViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sales by viewModel.sales.collectAsState()
    val business by viewModel.business.collectAsState()

    val orderedSales = remember(sales, saleIds) {
        val byId = sales.associateBy { it.id }
        saleIds.mapNotNull { byId[it] }
    }
    val receiptData = remember(orderedSales, business) { ReceiptData.from(business, orderedSales) }

    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // WRITE_EXTERNAL_STORAGE only matters on API 24-28 - see the manifest
    // comment and ReceiptRenderer.saveToDownloads (API 29+ uses MediaStore,
    // no permission needed, so this launcher just never gets used there).
    var pendingSaveAfterPermission by remember { mutableStateOf(false) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val data = receiptData
        if (granted && pendingSaveAfterPermission && data != null) {
            saveReceipt(context, scope, data, onWorking = { isWorking = it }, onStatus = { statusMessage = it })
        } else {
            statusMessage = "Storage permission is needed to save the receipt."
        }
        pendingSaveAfterPermission = false
    }

    fun requestSave() {
        val data = receiptData ?: return
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSaveAfterPermission = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveReceipt(context, scope, data, onWorking = { isWorking = it }, onStatus = { statusMessage = it })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done")
                    }
                },
                actions = {
                    receiptData?.let { data ->
                        IconButton(
                            onClick = { printReceipt(context, scope, data, onWorking = { isWorking = it }) },
                            enabled = !isWorking
                        ) {
                            Icon(Icons.Filled.Print, contentDescription = "Print receipt", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (receiptData == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (orderedSales.isEmpty() && sales.isEmpty()) {
                    CircularProgressIndicator()
                } else {
                    Text("Couldn't find this sale.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Sale saved. Share or save a receipt for the customer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReceiptPreviewCard(receiptData)
            }

            statusMessage?.let {
                Text(
                    it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { shareReceiptImage(context, scope, receiptData, onWorking = { isWorking = it }) },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                OutlinedButton(
                    onClick = { shareReceiptPdf(context, scope, receiptData, onWorking = { isWorking = it }) },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PDF")
                }
                Button(
                    onClick = { requestSave() },
                    enabled = !isWorking,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptPreviewCard(data: ReceiptData) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
    val mono = FontFamily.Monospace

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(data.businessName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (data.businessAddress.isNotBlank()) {
                Text(data.businessAddress, fontSize = 12.sp, fontFamily = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (data.businessPhone.isNotBlank()) {
                Text(data.businessPhone, fontSize = 12.sp, fontFamily = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(14.dp))
            // Boxed label - a visual echo of the boxed "TAX INVOICE" header on
            // a printed till slip. Not an actual tax invoice: no TIN/EFRIS
            // fiscal data anywhere on this receipt.
            Box(
                modifier = Modifier
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("SALES RECEIPT", fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = mono)
            }
            Spacer(Modifier.height(14.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReceiptDetailRow(
                    "Receipt #",
                    data.receiptNumber + if (data.isProvisional) " (provisional)" else ""
                )
                ReceiptDetailRow("Date", dateFormat.format(Date(data.dateMillis)))
                ReceiptDetailRow("Customer", data.customerName)
                ReceiptDetailRow("Payment", data.paymentMethod.name.replace("_", " "))
            }

            Spacer(Modifier.height(14.dp))
            DashedDivider()
            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Item", fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Qty", fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Price", fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Amount", fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Spacer(Modifier.height(6.dp))
            DashedDivider()
            Spacer(Modifier.height(10.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                data.lines.forEach { line ->
                    val unitPrice = if (line.quantity != 0) line.amount / line.quantity else line.amount
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(line.description, fontSize = 13.sp, fontFamily = mono, modifier = Modifier.weight(1f))
                        Text(line.quantity.toString(), fontSize = 13.sp, fontFamily = mono, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(CurrencyUtils.formatNumber(unitPrice), fontSize = 13.sp, fontFamily = mono, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(CurrencyUtils.formatNumber(line.amount), fontSize = 13.sp, fontFamily = mono, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            DashedDivider()
            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TOTAL", fontWeight = FontWeight.Bold, fontFamily = mono)
                Text(CurrencyUtils.formatUgx(data.total), fontWeight = FontWeight.Bold, fontFamily = mono)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Thank you, please come again!",
                fontSize = 12.sp,
                fontFamily = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Powered by SME Tracker",
                fontSize = 11.sp,
                fontFamily = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A thin dashed horizontal rule - the Compose-side counterpart of
 *  ReceiptRenderer's dashedDivider(), so the on-screen preview and the
 *  shared image/PDF read as the same receipt. */
@Composable
private fun DashedDivider() {
    Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = Color.DarkGray,
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
            strokeWidth = 2f
        )
    }
}

@Composable
private fun ReceiptDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── File generation + share/save actions ─────────────────────────────────
// Kept as plain functions (not composables) since they just kick off a
// coroutine on `scope` - rendering happens off the main thread (Dispatchers.IO),
// then the resulting file is handed to the share sheet / MediaStore on
// whichever thread is convenient (both are fine off-main here).

private fun receiptFileName(data: ReceiptData): String {
    val safeNumber = data.receiptNumber.ifBlank { "draft" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "receipt_$safeNumber"
}

private fun shareReceiptImage(
    context: Context,
    scope: CoroutineScope,
    data: ReceiptData,
    onWorking: (Boolean) -> Unit
) {
    scope.launch {
        onWorking(true)
        val file = withContext(Dispatchers.IO) {
            ReceiptRenderer.buildImageFile(context, data, receiptFileName(data))
        }
        ReceiptRenderer.shareFile(context, file, "image/png")
        onWorking(false)
    }
}

private fun shareReceiptPdf(
    context: Context,
    scope: CoroutineScope,
    data: ReceiptData,
    onWorking: (Boolean) -> Unit
) {
    scope.launch {
        onWorking(true)
        val file = withContext(Dispatchers.IO) {
            ReceiptRenderer.buildPdfFile(context, data, receiptFileName(data))
        }
        ReceiptRenderer.shareFile(context, file, "application/pdf")
        onWorking(false)
    }
}

private fun printReceipt(
    context: Context,
    scope: CoroutineScope,
    data: ReceiptData,
    onWorking: (Boolean) -> Unit
) {
    scope.launch {
        onWorking(true)
        val file = withContext(Dispatchers.IO) {
            ReceiptRenderer.buildPdfFile(context, data, receiptFileName(data))
        }
        PrintUtils.printPdf(context, file, "Receipt ${data.receiptNumber.ifBlank { "Draft" }}")
        onWorking(false)
    }
}

private fun saveReceipt(
    context: Context,
    scope: CoroutineScope,
    data: ReceiptData,
    onWorking: (Boolean) -> Unit,
    onStatus: (String?) -> Unit
) {
    scope.launch {
        onWorking(true)
        onStatus(null)
        val fileName = receiptFileName(data)
        val success = withContext(Dispatchers.IO) {
            val pdfFile = ReceiptRenderer.buildPdfFile(context, data, fileName)
            ReceiptRenderer.saveToDownloads(context, pdfFile, "$fileName.pdf", "application/pdf")
        }
        onWorking(false)
        onStatus(if (success) "Saved to Downloads." else "Couldn't save the receipt - please try again.")
    }
}