package com.example.smetracker.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

enum class WindowSize { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWindowSize(): WindowSize {
    val configuration = LocalConfiguration.current
    return when {
        configuration.screenWidthDp < 600  -> WindowSize.COMPACT   // phone
        configuration.screenWidthDp < 840  -> WindowSize.MEDIUM    // small tablet
        else                               -> WindowSize.EXPANDED  // large tablet
    }
}