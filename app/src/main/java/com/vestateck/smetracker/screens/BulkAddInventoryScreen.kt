// screens/BulkAddInventoryScreen.kt
package com.vestateck.smetracker.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.vestateck.smetracker.utils.BulkInventoryParseResult
import com.vestateck.smetracker.utils.InventoryCsvImporter
import com.vestateck.smetracker.viewmodel.SMEViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkAddInventoryScreen(viewModel: SMEViewModel, navController: NavController, isOwner: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var parseResult by remember { mutableStateOf<BulkInventoryParseResult?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    var isReading by remember { mutableStateOf(false) }

    // "*/*" rather than a text/csv mime type — a lot of file managers and
    // cloud-storage pickers don't tag .csv exports consistently (some send
    // text/comma-separated-values, some application/vnd.ms-excel, some
    // nothing at all). Filtering by extension after the pick is more
    // reliable than trusting the mime type here.
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        readError = null
        parseResult = null
        isReading = true
        coroutineScope.launch {
            val name = queryFileName(context, uri)
            val text = withContext(Dispatchers.IO) { readTextFromUri(context, uri) }
            if (text == null) {
                readError = "Couldn't read that file. Please pick a .csv file exported from Excel, Google Sheets, or Numbers."
            } else {
                pickedFileName = name
                parseResult = InventoryCsvImporter.parse(text)
            }
            isReading = false
        }
    }

    val result = parseResult
    val canImport = result != null && result.headerError == null && result.validRows.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bulk Import Inventory") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How it works", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "Upload a CSV with columns: name, category, quantity, sellingPrice, costPrice, reorderLevel. " +
                                    "name, quantity and sellingPrice are required" +
                                    if (isOwner) " — leave costPrice blank on any row and it'll show up in Reconciliation later, same as adding one item by hand."
                                    else ".",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(InventoryCsvImporter.templateCsv())) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy CSV Template")
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { pickFile.launch("*/*") },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (pickedFileName == null) "Select CSV File" else "Choose a Different File")
                }
            }

            if (isReading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading file…", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }

            pickedFileName?.let { fileName ->
                if (!isReading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(fileName, fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }

            readError?.let { error ->
                item {
                    ErrorCard(error)
                }
            }

            result?.headerError?.let { headerError ->
                item {
                    ErrorCard(headerError)
                }
            }

            if (result != null && result.headerError == null) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.validRows.isNotEmpty())
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else
                                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (result.validRows.isNotEmpty()) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (result.validRows.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "${result.validRows.size} item(s) ready to import",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                if (result.issues.isNotEmpty()) {
                                    Text(
                                        "${result.issues.size} row(s) skipped — see below",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }

                if (result.issues.isNotEmpty()) {
                    item {
                        Text("Skipped Rows", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    items(result.issues) { issue ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Row ${issue.rowNumber}: ${issue.message}", fontSize = 13.sp)
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        enabled = canImport,
                        onClick = {
                            viewModel.addInventoryItemsBulk(result.validRows)
                            navController.popBackStack()
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            if (result.validRows.isEmpty()) "No valid rows to import"
                            else "Import ${result.validRows.size} Item(s)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Cancel")
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

// Reads the picked file as UTF-8 text. Returns null on any failure (unreadable
// stream, binary garbage, permission revoked, etc.) rather than throwing, so
// the caller can show one consistent "couldn't read that file" message.
private fun readTextFromUri(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
    }
} catch (e: Exception) {
    null
}

// Best-effort display name for the picked file (shown under the picker
// button so the user can confirm they chose the right one) — falls back to
// null rather than the raw content:// uri, which is meaningless to a user.
private fun queryFileName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
} catch (e: Exception) {
    null
}