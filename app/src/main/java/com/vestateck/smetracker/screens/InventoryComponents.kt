package com.vestateck.smetracker.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

// Small pieces shared by InventoryScreen.kt - split out (was 497 lines).
// InventoryThumbnail is also used outside this package's Inventory
// screen itself (AddSaleScreen.kt, ExpensesScreen.kt), so it stays public
// as before. ReceiveStockDialog is only ever opened from
// InventoryScreen.kt's InventoryListItem, so internal is enough.

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
internal fun ReceiveStockDialog(
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