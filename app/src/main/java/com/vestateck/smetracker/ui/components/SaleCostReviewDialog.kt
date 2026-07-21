// ui/components/SaleCostReviewDialog.kt
package com.vestateck.smetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.utils.CurrencyUtils

/**
 * Shared by two call sites that both need "enter this sale's per-unit cost,
 * see the projected profit update live, confirm":
 *   - ReconciliationScreen's Sales tab, for a sale whose cost is still
 *     genuinely unknown (SMEViewModel.reconcileSale).
 *   - DashboardScreen's "Edit cost" action on an already-reconciled sale,
 *     for the rarer case where that particular unit's cost really was
 *     different from the item's normal price (SMEViewModel.editSaleCost).
 *
 * Only the framing text and the starting field value differ between the two
 * - the review/confirm mechanics are identical, so this is the one place
 * that logic (and its live profit projection) lives.
 */
@Composable
fun SaleCostReviewDialog(
    title: String,
    sale: Sale,
    // Pre-filled into the field, editable from there - null/blank means
    // "nothing to suggest, owner has to type a number". Callers decide what
    // counts as a suggestion (e.g. a genuine positive item cost, or this
    // sale's own previously-recorded cost when editing).
    initialCostPricePerUnit: Double?,
    // Small helper line under the field explaining where initialCostPricePerUnit
    // came from (or that there's nothing to suggest yet). Null hides it.
    supportingText: String?,
    confirmLabel: String = "Confirm",
    onDismiss: () -> Unit,
    onConfirm: (costPricePerUnit: Double) -> Unit
) {
    var costPriceInput by remember {
        mutableStateOf(initialCostPricePerUnit?.takeIf { it > 0.0 }?.toString() ?: "")
    }
    val costPricePerUnit = costPriceInput.toDoubleOrNull()
    val projectedProfit = costPricePerUnit?.let { sale.amount - (it * sale.quantity) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(sale.customerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(sale.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Sale price: ${CurrencyUtils.formatUgx(sale.amount)}", fontSize = 14.sp)
                    Text("Qty: ${sale.quantity}", fontSize = 14.sp)
                }

                OutlinedTextField(
                    value = costPriceInput,
                    onValueChange = { costPriceInput = it },
                    label = { Text("Cost Price per Unit") },
                    supportingText = supportingText?.let { text -> { Text(text) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                // Live projection so the owner can sanity-check the number
                // before committing - updates on every keystroke.
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Projected profit", fontSize = 14.sp)
                        Text(
                            text = projectedProfit?.let { CurrencyUtils.formatUgx(it) } ?: "—",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = when {
                                projectedProfit == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                projectedProfit < 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { costPricePerUnit?.let { onConfirm(it) } },
                        enabled = costPricePerUnit != null
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}