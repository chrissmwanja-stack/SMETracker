package com.vestateck.smetracker.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vestateck.smetracker.data.entities.Sale
import com.vestateck.smetracker.utils.CurrencyUtils

// Small composables shared across the Dashboard's own sections and files -
// split out of DashboardScreen.kt (was 705 lines) so each section file can
// stay focused. See DashboardScreen.kt, DashboardQuickActions.kt, and
// DashboardReports.kt for where these get used.

@Composable
internal fun SalesGrid(sales: List<Sale>, isOwner: Boolean, onEditCost: (Sale) -> Unit) {
    val chunked = sales.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chunked.forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { sale ->
                    SaleItem(
                        sale = sale,
                        editable = isOwner && sale.inventoryItemId != null,
                        onClick = { onEditCost(sale) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, isTablet: Boolean) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = if (isTablet) 18.sp else 16.sp)
}

@Composable
internal fun EmptyStateCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
internal fun SummaryCard(modifier: Modifier = Modifier, label: String, value: String, icon: ImageVector, containerColor: androidx.compose.ui.graphics.Color, contentColor: androidx.compose.ui.graphics.Color) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.sp, color = contentColor.copy(alpha = 0.8f))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = contentColor)
        }
    }
}

@Composable
internal fun SaleItem(sale: Sale, modifier: Modifier = Modifier, editable: Boolean = false, onClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (editable && onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sale.customerName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(sale.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(CurrencyUtils.formatUgx(sale.amount), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            // Small tap affordance so it's clear a reconciled sale's cost can
            // still be revised - only ever shown when it actually can be
            // (isOwner && a tracked item is linked, decided by the caller).
            if (editable) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit cost",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    modifier = Modifier.size(16.dp).padding(start = 8.dp)
                )
            }
        }
    }
}
