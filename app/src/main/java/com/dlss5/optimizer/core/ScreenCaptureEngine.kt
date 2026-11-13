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
 * Live capture pipeline. Two real consumers share one MediaProjection:
 *
 *  * **HDR mode** — every captured frame goes through [HdrCompositor] (tone-map + bloom + vibrance +
 *    unsharp, optional Lanczos upscale) and is returned full-screen for the overlay surface. This is the
 *    "real graphics" mode: what you see on screen is the graded frame.
 *  * **UPSCALE / TRUE DLSS** — the frame is downscaled, upscaled with bicubic/Lanczos and sharpened by
 *    [RenderPipeline], then shown as a preview at a lower cadence.
 *
 * Original doc: TRUE DLSS path: a real screen-capture → downscale → upscale → sharpen loop.
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
    private val preferLanczos: Boolean = true,
    private val maxFps: Int = 30,
    private val hdrEnabled: Boolean = false,
    private val hdrPreset: HdrPreset = HdrPreset.EXTREME_HDR,
    private val hdrBlend: Float = 1.0f,
    private val captureScaleOverride: Float = -1f,
    private val useFeedbackNeutralisation: Boolean = true,
    private val upscaleOutput: Boolean = true
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
    private val compositor = HdrCompositor()
    private val softwareGrader = SoftwareGrader()
    private var hdrReady = false
    private var hdrUnavailableReported = false
    private var pipelineRetries = 0

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
    fun hdrActive(): Boolean = hdrEnabled && (hdrReady && compositor.isHealthy() || softwareActive())
    fun softwareActive(): Boolean = softwareGrader.isEnabled()
    fun hdrTier(): String = when {
        hdrReady && compositor.isHealthy() -> compositor.tier()
        softwareGrader.isEnabled() -> "software_cpu"
        else -> "none"
    }
    fun upscaleTier(): String = pipeline.upscaleTier()
    fun compositorBloom(): Boolean = if (hdrReady) compositor.hasBloom() else false
    fun compositorCostMs(): Long = compositor.lastFrameCostMs()
    fun setHdrPreset(preset: HdrPreset) {
        compositor.setPreset(preset)
        softwareGrader.setPreset(preset)
    }

    fun setHdrBlend(blend: Float) {
        compositor.setBlend(blend)
        softwareGrader.setBlend(blend)
    }
    fun setHdrEnabled(enabled: Boolean) = compositor.setEnabled(enabled)

    /** Starts capture. Returns false (and calls [onFailure]) instead of throwing. */
    fun start(): Boolean {
        if (stopped) return false
        return try {
            val metrics = context.resources.displayMetrics
            // Capture at 70% of native — that is the "render at reduced resolution" half of DRS.
            // HDR mode captures at (near) full resolution: downscaling would destroy the very detail
            // the grade exists to show. Upscale modes keep the DRS capture for the cheaper path.
            val scale = when {
                captureScaleOverride > 0f -> captureScaleOverride.coerceIn(0.4f, 1.0f)
                hdrEnabled -> hdrPreset.captureScale.coerceIn(0.5f, 1.0f)
                else -> profile.defaultRenderScale.coerceIn(0.5f, 0.85f)
            }
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

            // HDR mode owns the frame; the upscale pipeline is only needed for the DLSS modes.
            if (hdrEnabled) {
                hdrReady = compositor.init(captureWidth, captureHeight, hdrPreset, hdrBlend)
                if (!hdrReady) {
                    // Every GLSL tier was rejected. Do not give up: grade on the CPU so the user still
                    // gets the colour work, just at a lower cadence.
                    Logger.w(TAG, "GPU HDR unavailable, enabling CPU software grader")
                    softwareGrader.configure(
                        preset = hdrPreset,
                        blend = hdrBlend,
                        alpha = 0.34f,
                        step = if (profile.tier == SocTier.ENTRY) 2 else 1
                    )
                }
            }
            if (upscaleOutput && !hdrEnabled) {
                val lanczos = preferLanczos && FilterKernels.canAffordLanczos(profile.tier, metrics.widthPixels)
                val ok = pipeline.init(metrics.widthPixels and 0xFFFFFFFE.toInt(), metrics.heightPixels, lanczos)
                if (!ok) throw IllegalStateException("upscale pipeline unavailable on this GPU")
            }

            Logger.i(
                TAG,
                "capture ${captureWidth}x$captureHeight hdr=$hdrEnabled(${hdrPreset.id}) " +
                    "upscale=$upscaleOutput tier=${profile.tier.key}"
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

            // HDR mode — software path (CPU grade) when the GPU rejected every shader tier.
            if (hdrEnabled && softwareGrader.isEnabled()) {
                neutraliseCorrection(bitmap, 0.34f)
                val graded = softwareGrader.process(bitmap)
                if (graded != null) {
                    lastCorrection = graded
                    framesEmitted++
                    onFrame(graded, softwareGrader.lastCostMs())
                    return
                }
                Logger.w(TAG, "software grader failed, disabling")
                softwareGrader.release()
                onFailure("hdr_unavailable")
                return
            }

            // HDR mode: build the correction layer and flatten our previous layer out of the capture so
            // the feedback loop converges on the requested grade instead of compounding.
            if (hdrEnabled && hdrReady && compositor.isHealthy()) {
                if (useFeedbackNeutralisation) {
                    neutraliseCorrection(bitmap, compositor.correctionAlpha())
                }
                val graded = compositor.process(bitmap)
                if (graded != null) lastCorrection = graded
                if (graded != null) {
                    framesEmitted++
                    onFrame(graded, (System.nanoTime() - started) / 1_000_000L)
                    return
                }
                // Compositor died mid-session: keep showing raw frames instead of going black.
                Logger.w(TAG, "HDR compositor failed, falling back to raw frames")
                hdrReady = false
                framesEmitted++
                onFrame(bitmap, (System.nanoTime() - started) / 1_000_000L)
                return
            }
            if (hdrEnabled && !hdrReady) {
                // HDR requested but no shader tier could start. Never push raw frames to the HDR layer:
                // an opaque copy of the game would be captured again and compound. Report the *tier*
                // ladder outcome so the UI can explain what actually happened.
                if (!hdrUnavailableReported) {
                    hdrUnavailableReported = true
                    onFailure("hdr_unavailable")
                }
                return
            }

            val processed = pipeline.process(bitmap, 1.0f)
            val costMs = (System.nanoTime() - started) / 1_000_000L
            if (processed == null) {
                // One retry: the pipeline may have lost its EGL context while the app was backgrounded.
                if (pipelineRetries < 2) {
                    pipelineRetries++
                    Logger.w(TAG, "upscale frame failed, rebuilding pipeline (retry $pipelineRetries)")
                    pipeline.release()
                    pipeline.init(
                        captureWidth, captureHeight,
                        preferLanczos && FilterKernels.canAffordLanczos(profile.tier, captureWidth)
                    )
                    return
                }
                shutdown("pipeline failure")
                onFailure("upscale_unavailable")
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

    /** Last correction layer we pushed to the overlay, used by [neutraliseCorrection]. */
    @Volatile
    private var lastCorrection: Bitmap? = null

    /** Reusable scratch buffers so per-frame neutralisation allocates nothing after warm-up. */
    private var observedPixels = IntArray(0)
    private var layerPixels = IntArray(0)

    /**
     * The compositor needs the *uncorrected* game frame. The captured buffer already contains our own
     * correction layer (unavoidable with MediaProjection), so we flatten it out:
     * `game ≈ (observed − α·layer) / (1 − α)` for every pixel the layer actually covers. Applying that
     * inverse is what gives the loop its converge-to-target behaviour instead of a colour runaway.
     */
    private fun neutraliseCorrection(buffer: Bitmap, alpha: Float) {
        try {
            if (alpha <= 0.01f) return
            val correction = lastCorrection ?: return
            if (correction.isRecycled) return
            if (correction.width != buffer.width || correction.height != buffer.height) return

            val count = buffer.width * buffer.height
            if (observedPixels.size != count) observedPixels = IntArray(count)
            if (layerPixels.size != count) layerPixels = IntArray(count)

            buffer.getPixels(observedPixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
            correction.getPixels(layerPixels, 0, correction.width, 0, 0, correction.width, correction.height)

            val gain = 1f / (1f - alpha)
            for (i in 0 until count) {
                val layerColor = layerPixels[i]
                val layerAlpha = ((layerColor ushr 24) and 0xFF) / 255f
                if (layerAlpha < 0.01f) continue
                val observed = observedPixels[i]
                val r = (((observed shr 16) and 0xFF) - layerAlpha * ((layerColor shr 16) and 0xFF)) * gain
                val g = (((observed shr 8) and 0xFF) - layerAlpha * ((layerColor shr 8) and 0xFF)) * gain
                val b = ((observed and 0xFF) - layerAlpha * (layerColor and 0xFF)) * gain
                observedPixels[i] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
            buffer.setPixels(observedPixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        } catch (t: Throwable) {
            Logger.w(TAG, "correction neutralise failed: ${t.message}")
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
            compositor.release()
        } catch (t: Throwable) {
            Logger.w(TAG, "compositor release: ${t.message}")
        }
        try {
            softwareGrader.release()
        } catch (t: Throwable) {
            Logger.w(TAG, "software grader release: ${t.message}")
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
            lastCorrection = null
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
