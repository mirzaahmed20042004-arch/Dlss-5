package com.dlss5.optimizer.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.dlss5.optimizer.util.Logger

/**
 * TRUE DLSS path: a real screen-capture → downscale → upscale → sharpen loop.
 *
 * Flow:
 *   MediaProjection.virtualDisplay → ImageReader (RGBA_8888 at `captureScale` of native)
 *     → Bitmap (padded-row aware) → RenderPipeline.process(scale=1.0 since capture is already small)
 *     → callback with an upscaled, sharpened Bitmap.
 *
 * The output is displayed by the overlay HUD (see OverlayService). Capture runs at a capped rate
 * (default 12 Hz) because this preview exists to demonstrate/measure the filter chain; driving a
 * full-rate screen copy would itself cost more frames than it saves.
 *
 * Everything is guarded: if the projection dies, the reader misbehaves, or the pipeline goes unhealthy,
 * we shut the capture down and report it — the caller falls back to GOVERNOR mode.
 */
class ScreenCaptureEngine(
    private val context: Context,
    private val resultCode: Int,
    private val projectionData: android.content.Intent,
    private val profile: DeviceProfile,
    private val onFrame: (Bitmap, Long) -> Unit,
    private val onFailure: (String) -> Unit,
    private val maxFps: Int = 12
) {

    companion object {
        private const val TAG = "Capture"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val pipeline = RenderPipeline()

    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 320
    private var lastFrameAt = 0L
    private var framesEmitted = 0L
    private var droppedFrames = 0L
    private var stopped = false
    private var reusable: Bitmap? = null
    private var rowBuffer = ByteArray(0)

    fun isRunning(): Boolean = projection != null && !stopped
    fun framesEmitted(): Long = framesEmitted

    /** Starts capture. Returns false (and calls [onFailure]) instead of throwing. */
    fun start(): Boolean {
        if (stopped) return false
        return try {
            val metrics = context.resources.displayMetrics
            // Capture at 70% of native — that is the "render at reduced resolution" half of DRS.
            val scale = profile.defaultRenderScale.coerceIn(0.5f, 0.85f)
            captureWidth = ((metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(64)
            captureHeight = ((metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(64)
            captureDensity = metrics.densityDpi

            val t = HandlerThread("dlss-capture")
            t.start()
            thread = t
            val h = Handler(t.looper)
            handler = h

            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManagerCompat
                ?: MediaProjectionManagerCompat(context)
            projection = mgr.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("projection denied")
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logger.w(TAG, "projection stopped by system/user")
                    shutdown("projection stopped")
                }
            }, h)

            val r = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3)
            reader = r
            r.setOnImageAvailableListener({ readerRef -> onImageAvailable(readerRef) }, h)

            virtualDisplay = projection?.createVirtualDisplay(
                "dlss5-capture",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                h
            )
            if (virtualDisplay == null) throw IllegalStateException("virtual display rejected")

            val lanczos = FilterKernels.canAffordLanczos(profile.tier, metrics.widthPixels)
            val ok = pipeline.init(metrics.widthPixels and 0xFFFFFFFE.toInt(), metrics.heightPixels, lanczos)
            if (!ok) throw IllegalStateException("upscale pipeline unavailable on this GPU")

            Logger.i(
                TAG,
                "capture ${captureWidth}x$captureHeight -> ${metrics.widthPixels}x${metrics.heightPixels} " +
                    "lanczos=$lanczos tier=${profile.tier.key}"
            )
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "start failed: ${t.message}", t)
            onFailure(t.message ?: "capture failed")
            shutdown("start failure")
            false
        }
    }

    private fun onImageAvailable(readerRef: ImageReader) {
        var image: Image? = null
        try {
            if (stopped) return
            val now = System.currentTimeMillis()
            val minInterval = 1000L / maxFps.coerceAtLeast(1)
            image = readerRef.acquireLatestImage() ?: return
            if (now - lastFrameAt < minInterval) {
                droppedFrames++
                return
            }
            lastFrameAt = now

            val bitmap = imageToBitmap(image) ?: return
            val started = System.nanoTime()
            val processed = pipeline.process(bitmap, 1.0f)
            val costMs = (System.nanoTime() - started) / 1_000_000L
            if (processed == null) {
                shutdown("pipeline failure")
                onFailure("upscale pipeline failed")
                return
            }
            framesEmitted++
            onFrame(processed, costMs)
        } catch (t: Throwable) {
            Logger.w(TAG, "frame handling failed: ${t.message}")
        } finally {
            try {
                image?.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    /** Copies an RGBA_8888 [Image] into a reusable Bitmap, handling row padding correctly. */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val widthWithPadding = image.width + rowPadding / pixelStride

        val target = reusable?.takeIf {
            it.width == widthWithPadding && it.height == image.height
        } ?: Bitmap.createBitmap(widthWithPadding, image.height, Bitmap.Config.ARGB_8888).also {
            reusable = it
        }
        buffer.rewind()
        target.copyPixelsFromBuffer(buffer)
        if (widthWithPadding == image.width) return target

        // Slice off the padding into a second reusable bitmap of the real width.
        val cropped = croppedBuffer?.takeIf {
            it.width == image.width && it.height == image.height
        } ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
            croppedBuffer = it
        }
        val canvas = android.graphics.Canvas(cropped)
        canvas.drawBitmap(
            target,
            android.graphics.Rect(0, 0, image.width, image.height),
            android.graphics.Rect(0, 0, image.width, image.height),
            null
        )
        return cropped
    }

    private var croppedBuffer: Bitmap? = null

    fun shutdown(reason: String) {
        if (stopped) return
        stopped = true
        Logger.i(TAG, "shutting down: $reason (frames=$framesEmitted dropped=$droppedFrames)")
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (t: Throwable) {
            Logger.w(TAG, "reader close: ${t.message}")
        }
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Logger.w(TAG, "display release: ${t.message}")
        }
        try {
            projection?.stop()
        } catch (t: Throwable) {
            Logger.w(TAG, "projection stop: ${t.message}")
        }
        try {
            pipeline.release()
        } catch (t: Throwable) {
            Logger.w(TAG, "pipeline release: ${t.message}")
        }
        try {
            handler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
        } catch (t: Throwable) {
            // ignore
        } finally {
            reader = null
            virtualDisplay = null
            projection = null
            handler = null
            thread = null
            reusable = null
            croppedBuffer = null
        }
    }
}

/**
 * Tiny indirection so this file compiles against the platform MediaProjectionManager without a
 * hard dependency on API-specific casts; keeps all reflection-free on API 26+.
 */
class MediaProjectionManagerCompat(private val context: Context) {

    fun getMediaProjection(resultCode: Int, data: android.content.Intent): MediaProjection? = try {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mgr.getMediaProjection(resultCode, data)
    } catch (t: Throwable) {
        Logger.e("Capture", "getMediaProjection failed: ${t.message}", t)
        null
    }
}
