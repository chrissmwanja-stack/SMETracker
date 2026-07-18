package com.vestateck.smetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// Defense-in-depth for owner-only routes (Reports and its sub-screens). The
// actual entry points into these routes are already hidden from workers in
// DashboardScreen, so reaching here as a worker means something else
// navigated here directly — bounce straight back rather than render.
@Composable
fun OwnerOnlyGate(
    isOwner: Boolean,
    navController: NavController,
    content: @Composable () -> Unit
) {
    if (isOwner) {
        content()
    } else {
        LaunchedEffect(Unit) { navController.popBackStack() }
    }
}

@Composable
fun ReportRow(
    label: String,
    subtitle: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Normal
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor
        )
    }
}