package com.vestateck.smetracker.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.vestateck.smetracker.navigation.Screen
import com.vestateck.smetracker.data.entities.InventoryItem
import com.vestateck.smetracker.utils.IdGenerator
import com.vestateck.smetracker.utils.ImageUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(viewModel: SMEViewModel, navController: NavController, isOwner: Boolean = false) {
    val inventoryItems by viewModel.inventoryItems.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<InventoryItem?>(null) }
    val ugx = NumberFormat.getNumberInstance(Locale.US)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Bulk CSV import — sits alongside the FAB's single-item
                    // quick add rather than replacing it (see
                    // BulkAddInventoryScreen / InventoryCsvImporter).
                    IconButton(onClick = { navController.navigate(Screen.BulkAddInventory.route) }) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = "Bulk Import from CSV",
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectedItem = null
                    showDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Item")
            }
        }
    ) { padding ->
        if (inventoryItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Text("No inventory items", fontSize = 18.sp, color = androidx.compose.ui.graphics.Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(inventoryItems, key = { it.id }) { item ->
                    InventoryListItem(
                        item = item,
                        ugx = ugx,
                        isOwner = isOwner,
                        onEdit = {
                            selectedItem = item
                            showDialog = true
                        },
                        onDelete = {
                            ImageUtils.deleteLocalCopy(item.localImagePath)
                            viewModel.deleteInventoryItem(item)
                        },
                        onReceiveStock = { qty, note -> viewModel.receiveStock(item.id, qty, note) },
                        onRecount = { newQty, note -> viewModel.recountStock(item.id, newQty, note) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showDialog) {
        InventoryItemDialog(
            item = selectedItem,
            isOwner = isOwner,
            onDismiss = { showDialog = false },
            onConfirm = { item ->
                viewModel.upsertInventoryItem(item)
                showDialog = false
            }
        )
    }
}

@Composable
fun InventoryThumbnail(
    localImagePath: String?,
    imageUrl: String?,
    outOfStock: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    // localImagePath (this device's own resized copy) is always preferred —
    // no network round-trip, works offline. imageUrl (Firebase Storage) is
    // the fallback for a device that's never had the local file, e.g. one
    // that only ever pulled this item from Firestore. See InventoryItem's
    // doc comment for the full reasoning.
    val model: Any? = localImagePath ?: imageUrl?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.5f)
            )
        }
        // Out-of-stock is already surfaced via the red "Qty: 0" text next to
        // this thumbnail — this dims the photo itself so it reads at a
        // glance in a scrolling list, without adding another text label.
        if (outOfStock) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            )
        }
    }
}

@Composable
private fun InventoryListItem(
    item: InventoryItem,
    ugx: NumberFormat,
    isOwner: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReceiveStock: (quantity: Int, note: String?) -> Unit,
    onRecount: (newQuantity: Int, note: String) -> Unit
) {
    var showConfirmDelete by remember { mutableStateOf(false) }
    var showReceiveDialog by remember { mutableStateOf(false) }
    var showRecountDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InventoryThumbnail(
                    localImagePath = item.localImagePath,
                    imageUrl = item.imageUrl,
                    outOfStock = item.quantity == 0,
                    size = 56.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(item.category, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Qty: ${item.quantity}", fontSize = 14.sp,
                            color = if (item.quantity <= item.reorderLevel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Price: UGX ${ugx.format(item.sellingPrice.toLong())}", fontSize = 14.sp)
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showConfirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            // Quantity no longer changes through Edit — see InventoryItemDialog.
            // These are the only two paths that can move it: Incoming Stock
            // (additive, anyone) and Recount (either direction, owner-only,
            // requires a reason). Both are logged via StockAdjustment.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { showReceiveDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Incoming Stock", fontSize = 13.sp)
                }
                if (isOwner) {
                    OutlinedButton(onClick = { showRecountDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Recount", fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete Item") },
            text = { Text("Are you sure you want to delete ${item.name}?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (showReceiveDialog) {
        ReceiveStockDialog(
            itemName = item.name,
            onDismiss = { showReceiveDialog = false },
            onConfirm = { qty, note ->
                onReceiveStock(qty, note)
                showReceiveDialog = false
            }
        )
    }

    if (showRecountDialog) {
        RecountStockDialog(
            itemName = item.name,
            currentQuantity = item.quantity,
            onDismiss = { showRecountDialog = false },
            onConfirm = { newQty, note ->
                onRecount(newQty, note)
                showRecountDialog = false
            }
        )
    }
}

@Composable
private fun ReceiveStockDialog(
    itemName: String,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, note: String?) -> Unit
) {
    var quantity by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val qtyValue = quantity.toIntOrNull()
    val isValid = qtyValue != null && qtyValue > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Incoming Stock — $itemName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("How many units arrived?", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.Gray)
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity received") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(qtyValue!!, note.ifBlank { null }) }
            ) { Text("Add to Stock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RecountStockDialog(
    itemName: String,
    currentQuantity: Int,
    onDismiss: () -> Unit,
    onConfirm: (newQuantity: Int, note: String) -> Unit
) {
    var newQuantity by remember { mutableStateOf(currentQuantity.toString()) }
    var note by remember { mutableStateOf("") }
    val newQtyValue = newQuantity.toIntOrNull()
    // A reason is required so a recount always leaves a trail explaining the
    // discrepancy, rather than a bare number with no context.
    val isValid = newQtyValue != null && newQtyValue >= 0 && note.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recount — $itemName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Currently on record: $currentQuantity", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.Gray)
                OutlinedTextField(
                    value = newQuantity,
                    onValueChange = { newQuantity = it },
                    label = { Text("Actual physical count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Reason for discrepancy") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirm(newQtyValue!!, note) }
            ) { Text("Save Recount") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun InventoryItemDialog(
    item: InventoryItem?,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (InventoryItem) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
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
                                    imagePendingUpload = imagePendingUpload
                                )
                            )
                        }) { Text("Save") }
                }
            }
        }
    }
}