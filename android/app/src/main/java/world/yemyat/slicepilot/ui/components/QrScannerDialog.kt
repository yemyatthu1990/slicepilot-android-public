package world.yemyat.slicepilot.ui.components

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Space
import java.util.concurrent.Executors

/**
 * Camera half of pairing: the printer's screen shows a QR holding its pairing code, and Matrix
 * scans it (its Add-device screen offers a scanner next to "Enter PIN Code").
 *
 * ML Kit decodes the frames, and the first code that decodes wins: a preview keeps producing
 * frames, so without a one-shot flag the callback would fire on every frame after the first hit
 * and each one would re-issue the pairing request.
 */
@Composable
fun QrScannerDialog(
    onCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var granted by
        remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        }
    val requester =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
        }
    LaunchedEffect(Unit) {
        if (!granted) requester.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (granted) {
                CameraPreview(onCode = onCode)
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(Space.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(Space.xl))
                    Text(
                        "SlicePilot needs the camera to read the printer's QR code.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.md))
                    Text(
                        "Allow",
                        color = LanternGold,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .padding(Space.xs)
                                .clickable { requester.launch(android.Manifest.permission.CAMERA) },
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.md)
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
            Text(
                "Line up the QR code shown on the printer's screen",
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.xl),
            )
        }
    }
}

/** The camera preview plus the frame analyzer that reads the code. */
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    val main = remember { Handler(Looper.getMainLooper()) }
    var cameraError by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(previewView) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        provider = future.get()
                    } catch (e: Exception) {
                        Log.e("QrScanner", "camera provider unavailable", e)
                        cameraError = true
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        } catch (e: Exception) {
            Log.e("QrScanner", "camera setup failed", e)
            cameraError = true
        }
    }

    DisposableEffect(provider) {
        onDispose { runCatching { provider?.unbindAll() } }
    }

    LaunchedEffect(provider) {
        val cameraProvider = provider ?: return@LaunchedEffect
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val options =
            BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE or
                        Barcode.FORMAT_DATA_MATRIX or
                        Barcode.FORMAT_PDF417 or
                        Barcode.FORMAT_AZTEC,
                ).build()
        val scanner = BarcodeScanning.getClient(options)
        var consumed = false
        val analysis =
            ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image: ImageProxy ->
                        if (consumed) {
                            image.close()
                        } else {
                            val media = image.image
                            val frame =
                                media?.let { runCatching { InputImage.fromMediaImage(it, image.imageInfo.rotationDegrees) }.getOrNull() }
                            if (frame == null) {
                                image.close()
                            } else {
                                scanner
                                    .process(frame)
                                    .addOnSuccessListener { codes ->
                                        val raw =
                                            codes.firstOrNull()?.let {
                                                it.rawValue ?: it.rawBytes?.toString(Charsets.UTF_8)
                                            }
                                        if (!raw.isNullOrBlank() && !consumed) {
                                            consumed = true
                                            main.post { onCode(raw) }
                                        }
                                    }.addOnFailureListener { e -> Log.w("QrScanner", "frame decode failed", e) }
                                    .addOnCompleteListener { image.close() }
                            }
                        }
                    }
                }
        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }.onFailure {
            Log.e("QrScanner", "bind failed", it)
            cameraError = true
        }
    }

    if (cameraError) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No camera available on this device.", color = Color.White)
        }
    } else {
        SharedAndroidView(view = previewView, modifier = Modifier.fillMaxSize())
    }
}
