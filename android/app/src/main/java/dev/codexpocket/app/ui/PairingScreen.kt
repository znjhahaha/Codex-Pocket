@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codexpocket.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CenterCropStrategy
import dev.codexpocket.app.R
import dev.codexpocket.app.data.PairingPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream

@Composable
internal fun EmbeddedPairingScreen(busy: Boolean, onPair: (String, String) -> Any, phase: PairingPhase,
    error: String?, onDismiss: (() -> Unit)?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val gate = remember { PairingScanGate() }
    // Deliberately not saveable: scanner/manual secrets never enter saved instance state.
    var payload by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(Build.MODEL) }
    var paused by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    var permission by remember { mutableStateOf(context.cameraGranted()) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val phaseBusy = phase in setOf(PairingPhase.Validating, PairingPhase.Connecting, PairingPhase.Authenticating, PairingPhase.Synchronizing)
    val working = busy || submitting || decoding || phaseBusy
    val active = scannerShouldRun(permission, foreground, paused, manual, working)
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        issue = if (it) null else "未获得相机权限，可以从相册选择或手动粘贴"
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            permission = context.cameraGranted()
            if (!foreground) torch = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(busy, phase) {
        if (!busy && (phase == PairingPhase.Failed || phase == PairingPhase.Idle)) submitting = false
    }
    fun received(value: String) {
        paused = true
        torch = false
        when (gate.accept(value)) {
            ScanDecision.Accepted -> { payload = value; reviewed = true; issue = null }
            ScanDecision.Expired -> { payload = ""; issue = "配对码已过期，请在电脑上生成新的配对码" }
            ScanDecision.Invalid -> { payload = ""; issue = "不是有效的 Codex Pocket 配对码" }
            ScanDecision.Duplicate -> Unit
        }
    }
    fun retry() { payload = ""; reviewed = false; issue = null; gate.retry(); paused = false; torch = false }
    val album = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) { if (payload.isEmpty() && issue == null) paused = false }
        else scope.launch {
            decoding = true
            try {
                val value = decodePairingAlbum(context, uri)
                if (value == null) { paused = true; issue = "图片中没有可读取的二维码，请选择清晰的配对码" }
                else received(value)
            } finally { decoding = false }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onDismiss != null) CircleAction(PocketIcons.ArrowBack, "返回", onDismiss)
            else Spacer(Modifier.size(48.dp))
            Text(stringResource(R.string.pair_title), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(48.dp))
        }
        Text("扫描电脑上的配对二维码", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(30.dp))
            .background(Color(0xFF17171A)).testTag("pair-preview"), contentAlignment = Alignment.Center) {
            if (permission) PairingCamera(active, torch,
                onResult = ::received,
                onError = { paused = true; torch = false; issue = "相机暂不可用，请重试、选择相册或手动粘贴" })
            if (!active) Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (payload.isNotBlank()) PocketIcons.CheckCircle else PocketIcons.QrCodeScanner, null, Modifier.size(42.dp), tint = Color.White)
                Text(when {
                    payload.isNotBlank() -> "已读取配对内容"
                    manual -> "正在手动配对"
                    !permission -> "允许相机权限后开始扫描"
                    working -> "正在处理"
                    else -> "扫描已暂停"
                }, color = Color.White, textAlign = TextAlign.Center)
                if (!permission) PocketButton("允许使用相机", { permissionRequest.launch(Manifest.permission.CAMERA) },
                    enabled = !working, modifier = Modifier.testTag("pair-scan"))
            }
            ScannerCorners(Modifier.matchParentSize())
            if (active) Box(Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
                // Status animation is tied to active work; reduced motion uses a static glyph.
                PocketProgressStatus("正在扫描")
            }
        }
        if (permission) PocketTextButton({ retry(); manual = false }, enabled = !working, modifier = Modifier.testTag("pair-scan")) {
            Icon(PocketIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("重新扫描")
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PocketTextButton({ torch = !torch }, enabled = active && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                Icon(PocketIcons.Sun, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (torch) "关闭补光" else "补光灯")
            }
            PocketTextButton({ payload = ""; reviewed = false; issue = null; paused = true; torch = false; gate.retry(); album.launch("image/*") }, enabled = !working) {
                Icon(PocketIcons.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("相册")
            }
            PocketTextButton({ manual = !manual; torch = false }, enabled = !working, modifier = Modifier.testTag("pair-manual-toggle")) {
                Text(stringResource(if (manual) R.string.hide_manual else R.string.manual_pairing))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("在电脑上打开 Codex Pocket", "选择连接手机，生成配对二维码", "扫描二维码，确认后安全连接").forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        issue?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        if (phase == PairingPhase.Failed) Text(error ?: "连接失败，请检查电脑后重试", color = MaterialTheme.colorScheme.error)
        if (working) {
            PocketProgressStatus(when {
                decoding -> "正在读取图片"
                phase == PairingPhase.Connecting -> "正在连接电脑"
                phase == PairingPhase.Authenticating -> "正在验证配对身份"
                phase == PairingPhase.Synchronizing -> "正在同步会话"
                phase == PairingPhase.Paired -> "配对完成"
                else -> "正在验证配对内容"
            })
        }
        if (manual) PocketTextField(payload, { payload = it.take(65_536); reviewed = false; issue = null; gate.retry() },
            label = stringResource(R.string.paste_pairing), modifier = Modifier.testTag("pair-payload"), minLines = 2, maxLines = 4,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !working)
        if (!manual && payload.isNotBlank()) Text(if (reviewed) stringResource(R.string.pair_ready) else "配对内容待验证")
        if (manual || payload.isNotBlank()) {
            PocketTextField(name, { name = it.take(80) }, label = stringResource(R.string.device_name), singleLine = true, enabled = !working)
            PocketButton(stringResource(R.string.pair_securely), onClick = {
                // Revalidate at confirmation: a reviewed QR can expire while the user reads it.
                when (validatePairingScan(payload)) {
                    ScanDecision.Accepted -> {
                        val value = payload
                        payload = ""; paused = true; submitting = true; torch = false
                        onPair(value, name.trim())
                    }
                    ScanDecision.Expired -> { payload = ""; issue = "配对码已过期，请在电脑上生成新的配对码" }
                    else -> { issue = "不是有效的 Codex Pocket 配对码" }
                }
            }, enabled = payload.isNotBlank() && name.isNotBlank() && !working, modifier = Modifier.fillMaxWidth().testTag("pair-connect"))
        }
        Text(stringResource(R.string.pair_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Context.cameraGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ScannerCorners(modifier: Modifier) {
    Canvas(modifier) {
        val inset = 18.dp.toPx(); val arm = 24.dp.toPx(); val stroke = 3.dp.toPx()
        listOf(Offset(inset, inset), Offset(size.width - inset, inset), Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset)).forEachIndexed { i, corner ->
            drawLine(Color.White.copy(alpha = .9f), corner, corner + Offset(if (i % 2 == 0) arm else -arm, 0f), stroke, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = .9f), corner, corner + Offset(0f, if (i < 2) arm else -arm), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun PairingCamera(active: Boolean, torch: Boolean, onResult: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val failure by rememberUpdatedState(onError)
    val enabled by rememberUpdatedState(active)
    val preview = remember(context) {
        BarcodeView(context).apply {
            setUseTextureView(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            previewScalingStrategy = CenterCropStrategy()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 30 * context.resources.displayMetrics.density)
                }
            }
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() = Unit
                override fun previewStarted() = Unit
                override fun previewStopped() = Unit
                override fun cameraClosed() = Unit
                override fun cameraError(error: Exception) { if (enabled) failure() }
            })
        }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
    DisposableEffect(preview, active) {
        if (active) {
            preview.decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(value: BarcodeResult) {
                    if (enabled) {
                        preview.stopDecoding(); preview.setTorch(false); preview.pause()
                        value.text?.let { result(it) }
                    }
                }
                override fun possibleResultPoints(points: MutableList<ResultPoint>?) = Unit
            })
            runCatching { preview.resume() }.onFailure { failure() }
        } else { preview.stopDecoding(); preview.setTorch(false); preview.pause() }
        onDispose { preview.stopDecoding(); preview.setTorch(false); preview.pause() }
    }
    SideEffect { preview.setTorch(active && torch) }
}

/** Read at most 12 MiB and decode at most ~1 megapixel. Work and rotations stay off the UI thread. */
internal suspend fun decodePairingAlbum(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    val limit = 12 * 1024 * 1024
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (output.size() <= limit) {
                ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: return@withContext null
        ensureActive()
        if (bytes.size > limit) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 1_000_000L || maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return@withContext null
        try {
            repeat(4) { rotation ->
                coroutineContext.ensureActive()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val luminance = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                for (source in listOf(luminance, luminance.invert())) {
                    val reader = QRCodeReader()
                    try {
                        val text = reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
                        return@withContext text.takeIf { it.length <= 65_536 }
                    } catch (_: ReaderException) { /* Try orientation/inversion without retaining decoder data. */ }
                    finally { reader.reset() }
                }
                if (rotation < 3) {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, false)
                    if (rotated !== bitmap) bitmap.recycle()
                    bitmap = rotated
                }
            }
            null
        } finally { bitmap.recycle() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
