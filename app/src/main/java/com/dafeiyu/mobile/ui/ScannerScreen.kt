package com.dafeiyu.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.LocalGlassBackdrop
import com.dafeiyu.mobile.ui.components.LocalGlassMaterialProfile
import com.dafeiyu.mobile.ui.components.LocalPageGlassBackdrop
import com.dafeiyu.mobile.ui.components.GlassMaterialProfile
import com.dafeiyu.mobile.ui.components.rememberGlassBackdropState
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import com.dafeiyu.mobile.ui.components.rememberGlassButtonDeformationState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Full-page live, local QR camera. Recognized content is shown, never opened or executed automatically. */
@Composable
fun ScannerScreen(onBack: () -> Unit, notify: (String) -> Unit, onDecoded: (String) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var cameraFailure by remember { mutableStateOf("") }
    var cameraEpoch by remember { mutableIntStateOf(0) }
    val camera = rememberCameraQrState()
    val cameraBackdrop = rememberGlassBackdropState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accepted ->
        requesting = false
        granted = accepted
        if (accepted) { cameraFailure = ""; cameraEpoch++ }
    }
    fun requestCamera() {
        if (requesting) return
        permissionRequested = true
        requesting = true
        try { permission.launch(Manifest.permission.CAMERA) }
        catch (_: Exception) { requesting = false; notify("无法请求相机权限，请在系统应用设置中授权") }
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (!granted && !permissionRequested) requestCamera() }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !reading) scope.launch {
            reading = true
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(Dispatchers.IO) { loadQrImage(context, uri) }
                val loaded = bitmap
                val decoded = if (loaded == null) "" else withContext(Dispatchers.Default) { readQr(loaded) }
                if (decoded.isNotBlank()) { result = decoded; onDecoded(decoded) }
                else notify("没有识别到二维码，请选择清晰、完整的图片")
            } catch (_: Exception) { notify("无法读取这张图片，请重试") }
            finally { bitmap?.recycle(); reading = false }
        }
    }
    fun choosePhoto() {
        if (reading) return
        try { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { notify("无法打开照片选择器，请稍后重试") }
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        surface = Color(0xFF202329), onSurface = Color.White, surfaceVariant = Color(0xFF30343C),
        onSurfaceVariant = Color(0xFFC7C9CF), background = Color(0xFF101114), onBackground = Color.White,
    )) {
    CompositionLocalProvider(
        LocalGlassBackdrop provides cameraBackdrop,
        // The camera leaf is independent of Compose page capture and uses the shared native lens.
        LocalPageGlassBackdrop provides null,
        LocalGlassMaterialProfile provides GlassMaterialProfile.Standard,
    ) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        if (granted && result.isBlank() && !reading && cameraFailure.isBlank()) {
            key(cameraEpoch) {
                CameraQrPreview(camera, onResult = { result = it; onDecoded(it) }, onFailure = { cameraFailure = it },
                    modifier = Modifier.matchParentSize(), backdrop = cameraBackdrop)
            }
        }
        // Camera content occupies the full page; glass controls float over it.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .1f)))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ScannerCircle("back", "返回", onClick = onBack)
                Text("扫码", modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.size(48.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    result.isNotBlank() -> GlassSurface(cornerRadius = 28.dp, tint = Color(0xFF15171C).copy(alpha = .8f)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("识别内容", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            SelectionContainer { Text(result, color = Color.White, style = MaterialTheme.typography.bodyLarge) }
                            Text("内容仅在本机显示，不会自动打开链接或执行操作。", color = Color(0xFFC7C9CF),
                                style = MaterialTheme.typography.bodySmall)
                            FishButton(onClick = { result = ""; cameraFailure = ""; cameraEpoch++ },
                                modifier = Modifier.fillMaxWidth()) { Text("继续扫描") }
                        }
                    }
                    !granted -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FishGlyph("viewfinder", Modifier.size(58.dp), Color.White)
                        Text("允许相机后即可实时扫描", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text("也可以从相册识别二维码。若系统不再显示授权弹窗，请打开应用设置授权。",
                            color = Color(0xFFC7C9CF), style = MaterialTheme.typography.bodyMedium)
                        FishButton(onClick = ::requestCamera, enabled = !requesting) { Text(if (requesting) "正在请求…" else "允许相机") }
                        FishButton(onClick = {
                            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"))) }
                            catch (_: Exception) { notify("请在系统设置中找到本应用并允许相机权限") }
                        }, accent = false) { Text("打开应用设置") }
                    }
                    cameraFailure.isNotBlank() -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(cameraFailure, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        FishButton(onClick = { cameraFailure = ""; cameraEpoch++ }) { Text("重试相机") }
                    }
                    reading -> Text("正在本机识别图片…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(26.dp)) {
                        ScanCorners(Modifier.fillMaxWidth(.84f).aspectRatio(1f))
                        Text(if (camera.ready) "将二维码放入取景框" else "正在打开相机…",
                            color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    ScannerCircle(if (camera.torchEnabled) "flashlightoff" else "flashlight", if (camera.torchEnabled) "关闭灯光" else "打开灯光",
                        enabled = camera.ready && camera.hasFlash && result.isBlank() && !reading,
                        selected = camera.torchEnabled,
                        onClick = { camera.toggleTorch { scope.launch { notify("无法切换灯光，请重试") } } })
                    Text(if (camera.ready && !camera.hasFlash) "无闪光灯" else "灯光", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    ScannerCircle("photo", "从相册识别二维码", enabled = !reading, onClick = ::choosePhoto)
                    Text("相册", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("在本机识别 · 不上传图片", color = Color(0xFFB3B5BC), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 16.dp))
        }
    }
    }
    }
}

@Composable
private fun ScannerCircle(glyph: String, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val deformation = rememberGlassButtonDeformationState()
    CompositionLocalProvider(LocalGlassMaterialProfile provides GlassMaterialProfile.MediaButton) {
        GlassSurface(buttonDeformation = deformation,
            modifier = Modifier.size(52.dp).applyButtonDeformation(enabled = enabled, state = deformation, circularHit = true).clip(CircleShape),
            cornerRadius = 26.dp, tint = if (selected) Color(0xFF447FB4).copy(alpha = .14f) else Color(0xFF25282D).copy(alpha = .08f)) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.matchParentSize().semantics {
                contentDescription = label
                this.selected = selected
            }) {
                FishGlyph(glyph, Modifier.size(24.dp), Color.White.copy(alpha = if (enabled) 1f else .35f))
            }
        }
    }
}

/** The viewfinder is a scanning guide, not a generated control icon. Four rounded open corners. */
@Composable
private fun ScanCorners(modifier: Modifier) {
    Canvas(modifier) {
        val arm = min(size.width, size.height) * .13f
        val margin = 5.dp.toPx()
        val stroke = 3.dp.toPx()
        val left = margin; val top = margin; val right = size.width - margin; val bottom = size.height - margin
        fun segment(a: Offset, b: Offset) = drawLine(Color.White.copy(alpha = .92f), a, b, strokeWidth = stroke, cap = StrokeCap.Round)
        segment(Offset(left, top + arm), Offset(left, top)); segment(Offset(left, top), Offset(left + arm, top))
        segment(Offset(right - arm, top), Offset(right, top)); segment(Offset(right, top), Offset(right, top + arm))
        segment(Offset(left, bottom - arm), Offset(left, bottom)); segment(Offset(left, bottom), Offset(left + arm, bottom))
        segment(Offset(right - arm, bottom), Offset(right, bottom)); segment(Offset(right, bottom), Offset(right, bottom - arm))
    }
}

private fun hasCameraPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun readQr(bitmap: Bitmap): String {
    val width = bitmap.width; val height = bitmap.height
    if (width <= 0 || height <= 0 || width.toLong() * height > 4_000_000) return ""
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val reader = MultiFormatReader().apply { setHints(mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true,
    )) }
    val source = RGBLuminanceSource(width, height, pixels)
    return try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text.take(16_384) }
    catch (_: com.google.zxing.ReaderException) {
        reader.reset()
        try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text.take(16_384) }
        catch (_: com.google.zxing.ReaderException) { "" }
    } finally { reader.reset() }
}

private fun loadQrImage(context: Context, uri: Uri): Bitmap? = try {
    if (uri.scheme != "content") null else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 100_000_000L)
        val scale = 1400f / max(info.size.width, info.size.height).coerceAtLeast(1400)
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (_: Exception) { null }



