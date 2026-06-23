// ui/theme/Theme.kt
package com.example.smetracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006B5F),
    secondary = androidx.compose.ui.graphics.Color(0xFF4A635E),
    tertiary = androidx.compose.ui.graphics.Color(0xFF456179),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A)
)

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF82D5C6),
    secondary = androidx.compose.ui.graphics.Color(0xFFB2CCC5),
    tertiary = androidx.compose.ui.graphics.Color(0xFFAECBE6),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB)
)

@Composable
fun SMETrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}