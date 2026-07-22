package com.vestateck.smetracker.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.navigation.Screen
import com.vestateck.smetracker.utils.CurrencyUtils
import com.vestateck.smetracker.viewmodel.SMEViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesReceiptsListScreen(viewModel: SMEViewModel, navController: NavController) {
    val sales by viewModel.sales.collectAsState()
    
    // Group sales by receipt number. We use provisionalReceiptNumber as the key 
    // because it's always present and stable for a checkout group.
    val receipts = remember(sales) {
        sales.filter { !it.isDeleted }
            .groupBy { it.provisionalReceiptNumber }
            .map { (provisionalId, saleGroup) ->
                val firstSale = saleGroup.first()
                val finalNo = saleGroup.firstNotNullOfOrNull { it.finalReceiptNumber }
                ReceiptSummary(
                    displayNumber = finalNo ?: provisionalId,
                    date = firstSale.date,
                    customerName = firstSale.customerName,
                    totalAmount = saleGroup.sumOf { it.amount },
                    saleIds = saleGroup.map { it.id }
                )
            }
            .sortedByDescending { it.date }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Receipts") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (receipts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Receipt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No receipts found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(receipts) { receipt ->
                    ReceiptCard(
                        receipt = receipt,
                        onClick = {
                            navController.navigate(Screen.SaleReceipt.createRoute(receipt.saleIds))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptCard(receipt: ReceiptSummary, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Receipt #${receipt.displayNumber}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = CurrencyUtils.formatUgx(receipt.totalAmount),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = receipt.customerName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = dateFormat.format(Date(receipt.date)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class ReceiptSummary(
    val displayNumber: String,
    val date: Long,
    val customerName: String,
    val totalAmount: Double,
    val saleIds: List<String>
)
