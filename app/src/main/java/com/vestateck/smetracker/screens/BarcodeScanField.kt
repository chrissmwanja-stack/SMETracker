// screens/BarcodeScanField.kt
package com.vestateck.smetracker.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.KeyEventType
import androidx.core.content.ContextCompat

// Almost every USB/Bluetooth barcode scanner is a HID keyboard emulator: it
// "types" the code into whatever field has focus, then sends an Enter
// keypress - no special scanner SDK or permission needed on either Android
// or desktop. This field just needs to (a) stay focused during a scan
// session and (b) treat Enter as "submit this code", which also makes it
// work identically for someone typing a code in by hand and pressing the
// keyboard's Done/search action - same commit path either way, so there's
// nothing scanner-specific to test separately from manual entry.
//
// Deliberately does NOT try to distinguish "fast scanner input" from "slow
// manual typing" by keystroke timing - there's no risk of a premature
// submit either way, since commit only ever fires on an explicit
// Enter/Done, never mid-keystroke. Trying to add a timing heuristic here
// would add complexity without closing a real gap.
//
// That HID/typed path assumes the business already owns a scanner gun, or
// is willing to type SKUs by hand. Most of SMETracker's actual target
// users - solo shopkeepers whose only device is their phone - have
// neither, so the trailing camera icon below opens CameraBarcodeScanner as
// a second, equally-first-class way to fill the same field. Both paths
// converge on the same onScan(code) callback, so callers (AddSaleScreen,
// AddInventoryScreen, InventoryItemDialog) don't need to know or care
// which input method produced the code.
@Composable
fun BarcodeScanField(
    label: String,
    modifier: Modifier = Modifier,
    onScan: (code: String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    var showCameraScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun commit() {
        val code = value.trim()
        value = ""
        if (code.isNotEmpty()) onScan(code)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showCameraScanner = true }

    fun openCameraScanner() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            showCameraScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        placeholder = { Text("Scan, or type a code and press Enter") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { openCameraScanner() }) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Scan with camera")
            }
        },
        modifier = modifier
            // Primary path: a hardware scanner's Enter keypress. Software
            // keyboards' Done/search action doesn't always surface as this
            // same KeyEvent, which is what keyboardActions below is for.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    commit()
                    true
                } else {
                    false
                }
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() })
    )

    if (showCameraScanner) {
        CameraBarcodeScanner(
            onResult = { code ->
                showCameraScanner = false
                val trimmed = code.trim()
                if (trimmed.isNotEmpty()) onScan(trimmed)
            },
            onDismiss = { showCameraScanner = false }
        )
    }
}