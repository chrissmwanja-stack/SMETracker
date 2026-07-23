// ui/components/RecountStockDialog.kt
package com.vestateck.smetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared by two call sites: InventoryScreen's per-item "Recount" action, and
 * the Reconciliation screen's "Stock" tab (resolving an oversold item after
 * two offline devices both validly sold against stock that, combined,
 * wasn't enough). Both just need "enter the actual physical count, require
 * a reason, confirm" — extracted here rather than duplicated, same pattern
 * as SaleCostReviewDialog.
 *
 * Note this only accepts newQuantity >= 0 — a recount always corrects TO a
 * real physical count, which is never negative, whether it's fixing an
 * everyday miscount or resolving a genuine oversold item back to reality.
 */
@Composable
fun RecountStockDialog(
    itemName: String,
    currentQuantity: Int,
    onDismiss: () -> Unit,
    onConfirm: (newQuantity: Int, note: String) -> Unit
) {
    var newQuantity by remember { mutableStateOf(if (currentQuantity >= 0) currentQuantity.toString() else "") }
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
                Text(
                    "Currently on record: $currentQuantity",
                    fontSize = 13.sp,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
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