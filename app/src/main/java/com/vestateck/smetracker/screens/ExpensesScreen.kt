// screens/ExpensesScreen.kt
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Receipt
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
import androidx.navigation.NavController
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.utils.ImageUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EXPENSE_CATEGORIES = listOf("General", "Rent", "Utilities", "Transport", "Supplies", "Salaries", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(viewModel: SMEViewModel, navController: NavController) {
    val expenses by viewModel.expenses.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    val totalExpenses = expenses.sumOf { it.amount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                onClick = { showDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Expense")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Total Expenses", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        CurrencyUtils.formatUgx(totalExpenses),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (expenses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text("No expenses recorded yet", fontSize = 16.sp, color = androidx.compose.ui.graphics.Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(expenses, key = { it.id }) { expense ->
                        ExpenseListItem(
                            expense = expense,
                            onDelete = {
                                ImageUtils.deleteLocalCopy(expense.localReceiptPath)
                                viewModel.deleteExpense(expense)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        AddExpenseDialog(
            onDismiss = { showDialog = false },
            onConfirm = { description, amount, category, receiptNumber, localReceiptPath ->
                viewModel.addExpense(description, amount, category, receiptNumber, localReceiptPath)
                showDialog = false
            }
        )
    }
}

@Composable
private fun ExpenseListItem(expense: Expense, onDelete: () -> Unit) {
    var showConfirmDelete by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (expense.localReceiptPath != null || !expense.receiptUrl.isNullOrBlank()) {
                InventoryThumbnail(
                    localImagePath = expense.localReceiptPath,
                    imageUrl = expense.receiptUrl,
                    size = 44.dp
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(expense.description, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text(expense.category, fontSize = 11.sp) }, modifier = Modifier.height(24.dp))
                    Text(dateFormat.format(Date(expense.date)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                expense.receiptNumber?.let {
                    if (it.isNotBlank()) {
                        Text("Receipt: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
            Text(CurrencyUtils.formatUgx(expense.amount), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
            IconButton(onClick = { showConfirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete Expense") },
            text = { Text("Are you sure you want to delete this expense?") },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(
    onDismiss: () -> Unit,
    onConfirm: (description: String, amount: Double, category: String, receiptNumber: String?, localReceiptPath: String?) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(EXPENSE_CATEGORIES.first()) }
    var receiptNumber by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    var descError by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var localReceiptPath by remember { mutableStateOf<String?>(null) }
    var isProcessingPhoto by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isProcessingPhoto = true
        coroutineScope.launch {
            val newPath = withContext(Dispatchers.IO) { ImageUtils.copyToInternalStorage(context, uri) }
            if (newPath != null) {
                ImageUtils.deleteLocalCopy(localReceiptPath)
                localReceiptPath = newPath
            }
            isProcessingPhoto = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add Expense", fontWeight = FontWeight.Bold, fontSize = 20.sp)

                // Receipt photo - proof of the expense for tax/audit purposes.
                // Same picker pattern as InventoryItemDialog's item photo.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clickable {
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                    ) {
                        InventoryThumbnail(localImagePath = localReceiptPath, imageUrl = null, size = 64.dp)
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
                            Text(if (localReceiptPath == null) "Attach receipt photo" else "Change photo", fontSize = 13.sp)
                        }
                        if (localReceiptPath != null) {
                            TextButton(onClick = {
                                ImageUtils.deleteLocalCopy(localReceiptPath)
                                localReceiptPath = null
                            }) {
                                Text("Remove photo", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; descError = false },
                    label = { Text("Description *") },
                    isError = descError,
                    supportingText = { if (descError) Text("Description is required") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; amountError = false },
                    label = { Text("Amount (UGX) *") },
                    isError = amountError,
                    supportingText = { if (amountError) Text("Enter a valid amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        EXPENSE_CATEGORIES.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { category = option; expanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = receiptNumber,
                    onValueChange = { receiptNumber = it },
                    label = { Text("Receipt Number (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = !isProcessingPhoto,
                        onClick = {
                            descError = description.isBlank()
                            amountError = amount.toDoubleOrNull() == null || (amount.toDoubleOrNull() ?: 0.0) <= 0

                            if (!descError && !amountError) {
                                onConfirm(
                                    description.trim(),
                                    amount.toDouble(),
                                    category,
                                    receiptNumber.trim().ifBlank { null },
                                    localReceiptPath
                                )
                            }
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}