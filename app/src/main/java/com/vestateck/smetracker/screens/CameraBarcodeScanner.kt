// screens/CameraBarcodeScanner.kt
package com.vestateck.smetracker.screens

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera-based fallback for BarcodeScanField, for the majority of
 * SMETracker's target users who don't own a dedicated HID scanner gun -
 * see BarcodeScanField's doc comment for the primary (hardware-scanner /
 * typed) input path this supplements, not replaces.
 *
 * Shown as a full-screen Dialog rather than a nav destination, since it's
 * a momentary "scan one code and come straight back" action invoked
 * identically from three call sites (AddSaleScreen, AddInventoryScreen,
 * InventoryItemDialog) - a real destination would need a result-passing
 * mechanism (SavedStateHandle round-trip) for no benefit over just calling
 * back through a lambda here.
 *
 * On-device ML Kit decoding (no network round-trip, matters for
 * intermittent-connectivity use in the field, same reasoning as the app's
 * offline-first sync design elsewhere). Stops analyzing and dismisses
 * itself on the *first* successfully decoded barcode, so callers get
 * exactly one code per invocation - the same contract as a hardware
 * scanner's single Enter-terminated scan.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraBarcodeScanner(
    onResult: (code: String) -> Unit,
    onDismiss: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Guards against the async ML Kit callback firing onResult more than
    // once (e.g. two in-flight frames both decode successfully) before
    // recomposition tears this dialog down.
    var hasScanned by remember { mutableStateOf(false) }
    // Held so the DisposableEffect below can unbind on dismiss. AndroidView's
    // factory runs once and binds asynchronously (ProcessCameraProvider is a
    // ListenableFuture) - without this explicit unbind, the camera stays
    // bound to lifecycleOwner (the host Activity, which doesn't itself get
    // destroyed when this dialog closes) and keeps the camera LED on.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var analysisExecutor by remember { mutableStateOf<ExecutorService?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor?.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val scanner = BarcodeScanning.getClient()
                    val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get().also { cameraProvider = it }

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analysis = ImageAnalysis.Builder()
                            // Only the newest frame matters for a live scan -
                            // no reason to queue stale ones under load.
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || hasScanned) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val code = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                    if (code != null && !hasScanned) {
                                        hasScanned = true
                                        onResult(code)
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.w("CameraBarcodeScanner", "Decode attempt failed", e)
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        } catch (e: Exception) {
                            Log.e("CameraBarcodeScanner", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White)
            }

            Text(
                "Point the camera at a barcode",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}