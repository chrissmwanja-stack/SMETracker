// screens/components/ReportRow.kt
package com.example.smetracker.screens.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.sp

/**
 * A report row that shows a label + optional subtitle on the left,
 * and a primary value on the right.
 *
 * Optionally pass [secondaryValue] + [secondaryLabel] to show a second
 * line beneath the primary value (e.g. profit under revenue).
 * All existing callers that omit these two params are unaffected.
 */
@Composable
fun ReportRow(
    label: String,
    subtitle: String = "",
    value: String,
    color: Color = Color.Unspecified,
    isBold: Boolean = false,
    // Optional second value shown below the primary value (e.g. profit)
    secondaryLabel: String = "",
    secondaryValue: String = "",
    secondaryColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: label + subtitle
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

        // Right: primary value, and optionally a secondary value below it
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
                color = color
            )
            if (secondaryValue.isNotBlank()) {
                val prefix = if (secondaryLabel.isNotBlank()) "$secondaryLabel: " else ""
                Text(
                    "$prefix$secondaryValue",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = secondaryColor
                )
            }
        }
    }
}