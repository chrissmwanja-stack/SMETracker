package com.vestateck.smetracker.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.utils.IdGenerator
import com.vestateck.smetracker.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Add/Edit Item form dialog - split out of InventoryScreen.kt (was
// 497 lines). Only ever opened from InventoryScreen.kt's main
// composable, so internal is enough.

@Composable
internal fun InventoryItemDialog(
    item: InventoryItem?,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (InventoryItem) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var sku by remember { mutableStateOf(item?.sku ?: "") }
    // Quantity is only a free-typed field when creating a brand-new item (no
    // sale history to protect yet). Editing an EXISTING item never lets
    // anyone — owner or worker — type a new quantity here: that was the
    // loophole (edit dialog → decrement to match a pocketed cash sale, no
    // record left behind). Existing items now change quantity only through
    // Incoming Stock or Recount, both logged. See StockAdjustment.kt.
    val isNewItem = item == null
    var quantity by remember { mutableStateOf(item?.quantity?.toString() ?: "0") }
    var reorderLevel by remember { mutableStateOf(item?.reorderLevel?.toString() ?: "5") }
    var costPrice by remember { mutableStateOf(item?.costPrice?.toString() ?: "0") }
    var sellingPrice by remember { mutableStateOf(item?.sellingPrice?.toString() ?: "0") }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var localImagePath by remember { mutableStateOf(item?.localImagePath) }
    var imageUrl by remember { mutableStateOf(item?.imageUrl) }
    var imagePendingUpload by remember { mutableStateOf(item?.imagePendingUpload ?: false) }
    var isProcessingPhoto by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isProcessingPhoto = true
        coroutineScope.launch {
            val newPath = withContext(Dispatchers.IO) { ImageUtils.copyToInternalStorage(context, uri) }
            if (newPath != null) {
                // Replacing an existing photo — the old resized copy is this
                // app's own file (never the original picked one), safe to
                // delete now that nothing will point to it.
                ImageUtils.deleteLocalCopy(localImagePath)
                localImagePath = newPath
                imageUrl = null // stale until the new photo re-uploads
                imagePendingUpload = true
            }
            isProcessingPhoto = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isNewItem) "Add Item" else "Edit Item", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clickable {
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                    ) {
                        InventoryThumbnail(localImagePath = localImagePath, imageUrl = imageUrl, size = 64.dp)
                        if (isProcessingPhoto) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Text(if (localImagePath == null && imageUrl.isNullOrBlank()) "Add photo" else "Change photo", fontSize = 13.sp)
                        }
                        if (localImagePath != null || !imageUrl.isNullOrBlank()) {
                            TextButton(onClick = {
                                ImageUtils.deleteLocalCopy(localImagePath)
                                localImagePath = null
                                imageUrl = null
                                imagePendingUpload = false
                            }) {
                                Text("Remove photo", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Item Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                // Optional: assign a barcode by scanning it here (same
                // scanner used at checkout), or type one in by hand. Blank
                // is fine - the item still works via the normal picker.
                if (sku.isBlank()) {
                    BarcodeScanField(
                        label = "Barcode / SKU (optional)",
                        modifier = Modifier.fillMaxWidth(),
                        onScan = { code -> sku = code }
                    )
                } else {
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it },
                        label = { Text("Barcode / SKU") },
                        trailingIcon = {
                            TextButton(onClick = { sku = "" }) { Text("Clear") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isNewItem) {
                        OutlinedTextField(value = quantity, onValueChange = { quantity = it }, label = { Text("Initial Qty") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    } else {
                        OutlinedTextField(
                            value = quantity,
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("Qty (use Incoming Stock / Recount)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(value = reorderLevel, onValueChange = { reorderLevel = it }, label = { Text("Reorder") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                // Cost is owner-only (see AddInventoryScreen's comment on the
                // same field). costPrice's remembered state still carries
                // whatever value was already loaded from `item`, so hiding
                // this field for a worker leaves it untouched on save rather
                // than resetting it.
                if (isOwner) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = costPrice, onValueChange = { costPrice = it }, label = { Text("Cost") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = sellingPrice, onValueChange = { sellingPrice = it }, label = { Text("Selling") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                } else {
                    OutlinedTextField(value = sellingPrice, onValueChange = { sellingPrice = it }, label = { Text("Selling Price") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = !isProcessingPhoto,
                        onClick = {
                            onConfirm(
                                InventoryItem(
                                    id = item?.id ?: IdGenerator.newId(),
                                    name = name,
                                    category = category,
                                    quantity = quantity.toIntOrNull() ?: 0,
                                    reorderLevel = reorderLevel.toIntOrNull() ?: 0,
                                    costPrice = costPrice.toDoubleOrNull() ?: 0.0,
                                    sellingPrice = sellingPrice.toDoubleOrNull() ?: 0.0,
                                    updatedAt = System.currentTimeMillis(),
                                    recordedBy = item?.recordedBy ?: "",
                                    costReconciled = item?.costReconciled ?: true,
                                    localImagePath = localImagePath,
                                    imageUrl = imageUrl,
                                    imagePendingUpload = imagePendingUpload,
                                    sku = sku.trim().ifBlank { null }
                                )
                            )
                        }) { Text("Save") }
                }
            }
        }
    }
}