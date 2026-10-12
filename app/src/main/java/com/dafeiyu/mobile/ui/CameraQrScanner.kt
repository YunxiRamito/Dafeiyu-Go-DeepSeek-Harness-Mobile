package com.dafeiyu.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.util.Size
import android.util.Range
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalCameraInfo
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dafeiyu.mobile.ui.components.GlassBackdropState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

@Stable
class CameraQrState internal constructor() {
    var ready by mutableStateOf(false)
        internal set
    var hasFlash by mutableStateOf(false)
        internal set
    var torchEnabled by mutableStateOf(false)
        internal set
    private var camera: Camera? = null
    internal fun attach(value: Camera?) {
        camera = value
        ready = value != null
        hasFlash = value?.cameraInfo?.hasFlashUnit() == true
        if (value == null) torchEnabled = false
    }
    fun toggleTorch(onFailure: () -> Unit) {
        val active = camera ?: return
        if (!hasFlash) return
        val operation = try { active.cameraControl.enableTorch(!torchEnabled) }
            catch (_: RuntimeException) { onFailure(); return }
        operation.addListener({
            try { operation.get() } catch (_: Exception) { onFailure() }
        }, java.util.concurrent.Executor { it.run() })
        // UI uses cameraInfo.torchState, never an optimistic synthetic toggle.
    }
}

@Composable
fun rememberCameraQrState(): CameraQrState = remember { CameraQrState() }

/** Bind only this page's preview/analysis; lifecycle automatically suspends camera while backgrounded. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class, ExperimentalCameraInfo::class])
@Composable
fun CameraQrPreview(
    state: CameraQrState,
    onResult: (String) -> Unit,
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdropState? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val deliverResult by rememberUpdatedState(onResult)
    val deliverFailure by rememberUpdatedState(onFailure)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // Glass blur samples come from throttled ImageAnalysis thumbnails, never from preview-surface readback.
    val samplingLeaf = remember(context) { CameraBackdropLeaf(context) }
    val cameraContainer = remember(context, previewView, samplingLeaf) {
        FrameLayout(context).apply {
            addView(samplingLeaf, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }
    AndroidView(factory = { cameraContainer }, modifier = modifier)
    DisposableEffect(previewView, lifecycle, backdrop) {
        backdrop?.source = samplingLeaf
        val active = AtomicBoolean(true)
        val reported = AtomicBoolean(false)
        val snapshotQueued = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var boundCamera: Camera? = null
        val ownedPreviews = mutableListOf<Preview>()
        var analysis: ImageAnalysis? = null
        val torchObserver = Observer<Int> { value -> state.torchEnabled = value == TorchState.ON }
        future.addListener({
            if (active.get()) {
                try {
                    val acquired = future.get()
                    provider = acquired
                    if (!acquired.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        deliverFailure("设备没有可用的后置相机，可以从相册识别")
                    } else {
                        val supportedAeRanges = try {
                            Camera2CameraInfo.from(acquired.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA))
                                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                ?.toList()
                        } catch (error: Exception) {
                            Log.w("DafeiyuCameraMetrics", "Could not read supported AE FPS ranges; will try the 60 fps request", error)
                            null
                        }
                        val request60 = supportedAeRanges?.any { it.lower <= 60 && it.upper >= 60 } ?: true
                        val aeRangesLabel = supportedAeRanges?.joinToString(prefix = "[", postfix = "]") ?: "unknown"
                        val previewResolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(
                                Size(960, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ))
                            .build()
                        val analysisResolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ))
                            .build()
                        val initialPreviewBuilder = Preview.Builder()
                            .setResolutionSelector(previewResolutionSelector)
                        if (request60) initialPreviewBuilder.setTargetFrameRate(Range(60, 60))
                        var viewfinder = initialPreviewBuilder.build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        ownedPreviews += viewfinder
                        val frames = ImageAnalysis.Builder()
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(analysisResolutionSelector)
                            .build()
                        analysis = frames
                        val reader = MultiFormatReader().apply { setHints(mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true,
                        )) }
                        var previousFrameMillis = 0L
                        var previousSnapshotMillis = 0L
                        var analysisLogged = false
                        var thumbnailLogged = false
                        var fpsWindowFirstTimestampNs = 0L
                        var fpsWindowLastTimestampNs = 0L
                        var fpsWindowFrameCount = 0
                        val frameBuffers = QrFrameBuffers()
                        val thumbnailBuffer = CameraColorThumbnailBuffer()
                        frames.setAnalyzer(analysisExecutor) { image ->
                            try {
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (active.get() && !reported.get()) {
                                    val timestampNs = image.imageInfo.timestamp
                                    if (timestampNs > 0L) {
                                        if (fpsWindowFrameCount == 0 || timestampNs <= fpsWindowLastTimestampNs) {
                                            fpsWindowFirstTimestampNs = timestampNs
                                            fpsWindowLastTimestampNs = timestampNs
                                            fpsWindowFrameCount = 1
                                        } else {
                                            fpsWindowLastTimestampNs = timestampNs
                                            fpsWindowFrameCount++
                                            val spanNs = fpsWindowLastTimestampNs - fpsWindowFirstTimestampNs
                                            if (spanNs >= 2_000_000_000L) {
                                                val fpsTenths = ((fpsWindowFrameCount - 1).toLong() * 10_000_000_000L / spanNs).toInt()
                                                Log.i("DafeiyuCameraMetrics", "analysis-delivery-fps=${fpsTenths / 10}.${fpsTenths % 10} frames=$fpsWindowFrameCount timestampsNs=$fpsWindowFirstTimestampNs..$fpsWindowLastTimestampNs resolution=${image.width}x${image.height}; preview display fps is not measured here")
                                                fpsWindowFirstTimestampNs = timestampNs
                                                fpsWindowLastTimestampNs = timestampNs
                                                fpsWindowFrameCount = 1
                                            }
                                        }
                                    }
                                    // 30 Hz low-resolution camera thumbnail for genuinely live blur; QR stays at 5 Hz.
                                    if (backdrop != null && now - previousSnapshotMillis >= 33L &&
                                        snapshotQueued.compareAndSet(false, true)) {
                                        previousSnapshotMillis = now
                                        val thumbnailStarted = android.os.SystemClock.elapsedRealtimeNanos()
                                        if (thumbnailBuffer.updateFrom(image)) {
                                            val pixels = thumbnailBuffer.pixels
                                            val width = thumbnailBuffer.width
                                            val height = thumbnailBuffer.height
                                            if (!thumbnailLogged) {
                                                thumbnailLogged = true
                                                Log.i("DafeiyuCameraMetrics", "glass-thumbnail-first=${(android.os.SystemClock.elapsedRealtimeNanos() - thumbnailStarted) / 1_000_000}ms size=${width}x${height} target=30fps source=ImageAnalysis")
                                            }
                                            try {
                                                main.execute {
                                                    try {
                                                        if (active.get()) {
                                                            samplingLeaf.updateFromPixels(pixels, width, height)
                                                            backdrop.changed()
                                                        }
                                                    } catch (error: RuntimeException) {
                                                        Log.w("DafeiyuCameraMetrics", "camera blur thumbnail update failed", error)
                                                    } finally {
                                                        // Do not reuse the analyzer buffer until setPixels has copied it.
                                                        snapshotQueued.set(false)
                                                    }
                                                }
                                            } catch (error: RuntimeException) {
                                                snapshotQueued.set(false)
                                                Log.w("DafeiyuCameraMetrics", "camera blur thumbnail handoff failed", error)
                                            }
                                        } else {
                                            snapshotQueued.set(false)
                                        }
                                    }
                                    if (now - previousFrameMillis >= 200L) {
                                        previousFrameMillis = now
                                        val analysisStarted = android.os.SystemClock.elapsedRealtimeNanos()
                                        val payload = readCameraQr(image, reader, frameBuffers)
                                        if (!analysisLogged) {
                                            analysisLogged = true
                                            Log.i("DafeiyuCameraMetrics", "qr-analysis-first=${(android.os.SystemClock.elapsedRealtimeNanos() - analysisStarted) / 1_000_000}ms frame=${image.width}x${image.height}")
                                        }
                                        if (!payload.isNullOrBlank() && reported.compareAndSet(false, true)) {
                                            main.execute { if (active.get()) deliverResult(payload) }
                                        }
                                    }
                                }
                            } finally { image.close() }
                        }
                        var used60Request = request60
                        val camera = try {
                            acquired.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, viewfinder, frames)
                        } catch (fpsBindError: Exception) {
                            if (!request60) throw fpsBindError
                            Log.w("DafeiyuCameraMetrics", "60 fps preview bind failed; retrying default-rate preview", fpsBindError)
                            // Only release the use cases owned here; never unbind unrelated app cameras.
                            runCatching { acquired.unbind(viewfinder, frames) }
                            used60Request = false
                            viewfinder = Preview.Builder()
                                .setResolutionSelector(previewResolutionSelector)
                                .build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            ownedPreviews += viewfinder
                            acquired.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, viewfinder, frames)
                        }
                        boundCamera = camera
                        state.attach(camera)
                        camera.cameraInfo.torchState.observe(lifecycle, torchObserver)
                        val previewResolution = viewfinder.resolutionInfo?.resolution
                        val analysisResolution = frames.resolutionInfo?.resolution
                        Log.i("DafeiyuCameraMetrics", "camera-bound initial60Request=$request60 boundPreviewFps=${if (used60Request) "60-target" else "default"} supportedAeFps=$aeRangesLabel preview=${previewResolution?.let { "${it.width}x${it.height}" } ?: "unknown"} analysis=${analysisResolution?.let { "${it.width}x${it.height}" } ?: "unknown"} implementation=PERFORMANCE; FPS logs below measure analyzer delivery only")
                    }
                } catch (_: Exception) {
                    state.attach(null)
                    deliverFailure("相机暂时无法使用，请关闭其他占用相机的应用后重试，或从相册识别")
                }
            }
        }, main)
        onDispose {
            active.set(false)
            // A revoked permission or camera-service error must not skip the rest of page cleanup.
            runCatching { analysis?.clearAnalyzer() }
            runCatching { boundCamera?.cameraInfo?.torchState?.removeObserver(torchObserver) }
            runCatching { boundCamera?.cameraControl?.enableTorch(false) }
            val owned = ownedPreviews + listOfNotNull(analysis)
            runCatching { if (owned.isNotEmpty()) provider?.unbind(*owned.toTypedArray()) }
            state.attach(null)
            if (backdrop?.source === samplingLeaf || backdrop?.source === previewView) {
                backdrop?.source = null
                backdrop?.changed()
            }
            samplingLeaf.release()
            analysisExecutor.shutdown()
        }
    }
}

/** Camera-only low-resolution color sample. The main preview remains on its independent surface. */
private class CameraBackdropLeaf(context: Context) : View(context) {
    private var frame: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = Rect()
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }
    fun updateFromPixels(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height > pixels.size) return
        var target = frame
        if (target == null || target.width != width || target.height != height || target.isRecycled) {
            target?.recycle()
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frame = target
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val current = frame
        val viewWidth = width
        val viewHeight = height
        if (current == null || current.isRecycled || viewWidth <= 0 || viewHeight <= 0) {
            canvas.drawColor(0xFF202329.toInt())
            return
        }
        val sourceAspect = current.width.toFloat() / current.height
        val destinationAspect = viewWidth.toFloat() / viewHeight
        if (sourceAspect > destinationAspect) {
            val cropWidth = (current.height * destinationAspect).toInt().coerceIn(1, current.width)
            val left = (current.width - cropWidth) / 2
            source.set(left, 0, left + cropWidth, current.height)
        } else {
            val cropHeight = (current.width / destinationAspect).toInt().coerceIn(1, current.height)
            val top = (current.height - cropHeight) / 2
            source.set(0, top, current.width, top + cropHeight)
        }
        destination.set(0, 0, viewWidth, viewHeight)
        canvas.drawBitmap(current, source, destination, paint)
    }
    fun release() {
        frame?.recycle()
        frame = null
        invalidate()
    }
}

/** Downsampled camera-only RGB thumbnail derived from YUV_420_888 planes on the analyzer thread. */
private class CameraColorThumbnailBuffer {
    var pixels = IntArray(0)
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun updateFrom(image: ImageProxy): Boolean {
        return try {
            val crop = image.cropRect
            if (crop.left < 0 || crop.top < 0 || crop.right > image.width || crop.bottom > image.height ||
                crop.width() <= 0 || crop.height() <= 0) return false
            val planes = image.planes
            if (planes.size < 3) return false
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            if (yPlane.rowStride <= 0 || yPlane.pixelStride <= 0 ||
                uPlane.rowStride <= 0 || uPlane.pixelStride <= 0 ||
                vPlane.rowStride <= 0 || vPlane.pixelStride <= 0) return false
            val yBuffer = yPlane.buffer.duplicate()
            val uBuffer = uPlane.buffer.duplicate()
            val vBuffer = vPlane.buffer.duplicate()
            val yOrigin = yBuffer.position()
            val uOrigin = uBuffer.position()
            val vOrigin = vBuffer.position()
            val lastX = crop.right - 1
            val lastY = crop.bottom - 1
            val yLastIndex = yOrigin.toLong() + lastY.toLong() * yPlane.rowStride + lastX.toLong() * yPlane.pixelStride
            val uLastIndex = uOrigin.toLong() + (lastY / 2).toLong() * uPlane.rowStride + (lastX / 2).toLong() * uPlane.pixelStride
            val vLastIndex = vOrigin.toLong() + (lastY / 2).toLong() * vPlane.rowStride + (lastX / 2).toLong() * vPlane.pixelStride
            if (yLastIndex < yOrigin || yLastIndex >= yBuffer.limit() ||
                uLastIndex < uOrigin || uLastIndex >= uBuffer.limit() ||
                vLastIndex < vOrigin || vLastIndex >= vBuffer.limit()) return false

            val longest = max(crop.width(), crop.height())
            val sampleWidth = (crop.width().toLong() * MAX_EDGE / longest).toInt().coerceAtLeast(1)
            val sampleHeight = (crop.height().toLong() * MAX_EDGE / longest).toInt().coerceAtLeast(1)
            val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
            if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) return false
            val rotated = rotation == 90 || rotation == 270
            val outputWidth = if (rotated) sampleHeight else sampleWidth
            val outputHeight = if (rotated) sampleWidth else sampleHeight
            val required = outputWidth.toLong() * outputHeight
            if (required <= 0L || required > MAX_EDGE.toLong() * MAX_EDGE) return false
            if (pixels.size != required.toInt()) pixels = IntArray(required.toInt())
            width = outputWidth
            height = outputHeight

            for (y in 0 until sampleHeight) {
                val sourceY = crop.top + (y.toLong() * crop.height() / sampleHeight).toInt()
                for (x in 0 until sampleWidth) {
                    val sourceX = crop.left + (x.toLong() * crop.width() / sampleWidth).toInt()
                    val yIndex = yOrigin + sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride
                    val chromaX = sourceX / 2
                    val chromaY = sourceY / 2
                    val uIndex = uOrigin + chromaY * uPlane.rowStride + chromaX * uPlane.pixelStride
                    val vIndex = vOrigin + chromaY * vPlane.rowStride + chromaX * vPlane.pixelStride
                    val luminance = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                    val u = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                    val v = (vBuffer.get(vIndex).toInt() and 0xFF) - 128
                    val c = max(0, luminance)
                    val red = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                    val green = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                    val blue = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                    val destinationIndex = when (rotation) {
                        90 -> x * sampleHeight + (sampleHeight - y - 1)
                        180 -> (sampleHeight - y - 1) * sampleWidth + sampleWidth - x - 1
                        270 -> (sampleWidth - x - 1) * sampleHeight + y
                        else -> y * sampleWidth + x
                    }
                    pixels[destinationIndex] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private companion object { const val MAX_EDGE = 96 }
}

/** Analyzer-thread buffers; ZXing consumes synchronously before the next frame can mutate them. */
private class QrFrameBuffers {
    var pixels = ByteArray(0)
    var rotated = ByteArray(0)
    fun pixels(size: Int): ByteArray {
        if (pixels.size != size) pixels = ByteArray(size)
        return pixels
    }
    fun rotated(size: Int): ByteArray {
        if (rotated.size != size) rotated = ByteArray(size)
        return rotated
    }
}
/** Y plane only; honor crop, buffer offset, row/pixel stride and image rotation. No full-color allocation. */
private fun readCameraQr(image: ImageProxy, reader: MultiFormatReader, buffers: QrFrameBuffers): String? {
    return try {
        val crop = image.cropRect
        if (crop.left < 0 || crop.top < 0 || crop.right > image.width || crop.bottom > image.height) return null
        val step = max(1, (max(crop.width(), crop.height()) + 799) / 800)
        val width = crop.width() / step
        val height = crop.height() / step
        if (width <= 0 || height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val origin = buffer.position()
        if (plane.rowStride <= 0 || plane.pixelStride <= 0) return null
        val lastIndex = origin.toLong() + (crop.top + (height - 1) * step).toLong() * plane.rowStride +
            (crop.left + (width - 1) * step).toLong() * plane.pixelStride
        if (lastIndex >= buffer.limit() || lastIndex < origin) return null
        val data = buffers.pixels(width * height)
        for (y in 0 until height) {
            val row = origin + (crop.top + y * step) * plane.rowStride
            if (step == 1 && plane.pixelStride == 1) {
                buffer.position(row + crop.left)
                buffer.get(data, y * width, width)
            } else {
                for (x in 0 until width) {
                    data[y * width + x] = buffer.get(row + (crop.left + x * step) * plane.pixelStride)
                }
            }
        }
        val rotation = image.imageInfo.rotationDegrees
        val swapped = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapped) height else width
        val rotatedHeight = if (swapped) width else height
        val luminance = if (rotation == 0) data else buffers.rotated(data.size).also { target ->
            for (y in 0 until height) for (x in 0 until width) {
                val destination = when (rotation) {
                    90 -> x * height + (height - y - 1)
                    180 -> (height - y - 1) * width + width - x - 1
                    270 -> (width - x - 1) * height + y
                    else -> y * width + x
                }
                target[destination] = data[y * width + x]
            }
        }
        val source = PlanarYUVLuminanceSource(luminance, rotatedWidth, rotatedHeight,
            0, 0, rotatedWidth, rotatedHeight, false)
        try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text.take(16_384) }
        catch (_: com.google.zxing.ReaderException) {
            reader.reset()
            try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text.take(16_384) }
            catch (_: com.google.zxing.ReaderException) { null }
        } finally { reader.reset() }
    } catch (_: RuntimeException) { null }
}





