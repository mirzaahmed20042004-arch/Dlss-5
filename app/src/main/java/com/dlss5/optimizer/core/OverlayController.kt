package com.dlss5.optimizer.core

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dlss5.optimizer.ui.FloatingPuckView
import com.dlss5.optimizer.ui.ColorLayerView
import com.dlss5.optimizer.ui.HdrSurfaceView
import com.dlss5.optimizer.ui.StatsHudView
import com.dlss5.optimizer.util.Haptics
import com.dlss5.optimizer.util.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the two overlay windows:
 *
 *  1. **HUD window** — full-screen, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`. It is physically unable
 *     to consume a touch, so PUBG keeps every input.
 *  2. **Puck window** — a 72dp square that only contains the draggable puck. Small surface = small
 *     touch-capture area = no accidental blocks over the fire button.
 *
 * Keeping the interactive view in its own window (instead of one full-screen touchable window) is what
 * makes the "never blocks touch input" requirement literally true on every OEM.
 */
class OverlayController(
    private val context: Context,
    private val onPuckTap: () -> Unit,
    private val onPuckLongPress: () -> Unit,
    private val onMenuAction: (MenuAction) -> Unit
) {

    enum class MenuAction { STOP, TOGGLE_HUD, NEXT_MODE, TOGGLE_CAPTURE, TOGGLE_HDR, NEXT_HDR_PRESET, SNAPSHOT, NEXT_LOOK, PREV_LOOK, TOGGLE_NIGHT, LOOK_STRENGTH }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var hudView: StatsHudView? = null
    private var hdrView: HdrSurfaceView? = null
    private var colorView: ColorLayerView? = null
    private var colorParams: WindowManager.LayoutParams? = null
    private var hdrParams: WindowManager.LayoutParams? = null
    private var puckView: FloatingPuckView? = null
    private var menuView: LinearLayout? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var puckParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var attached = false

    fun hud(): StatsHudView? = hudView
    fun hdr(): HdrSurfaceView? = hdrView
    fun colorLayer(): ColorLayerView? = colorView
    fun puck(): FloatingPuckView? = puckView
    fun isAttached(): Boolean = attached

    fun attach(): Boolean {
        if (attached) return true
        if (!Settings.canDrawOverlays(context)) {
            Logger.w(TAG, "overlay permission missing, cannot attach")
            return false
        }
        return try {
            attachColorLayer()
            attachHdrLayer()
            attachHud()
            attachPuck()
            attached = true
            Logger.i(TAG, "overlay attached")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "attach failed", t)
            detach()
            false
        }
    }

    /**
     * The **always-visible** colour layer. Added first (bottom of our stack, directly above the game) and
     * it only needs the overlay permission — so the screen changes colour on every device, regardless of
     * whether MediaProjection or the GPU shader path is available.
     */
    private fun attachColorLayer() {
        val view = ColorLayerView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Dimming the panel is honoured by the system without WRITE_SETTINGS and is what sells
            // NIGHT MODE even on a bright room/outdoor display.
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        windowManager.addView(view, params)
        colorView = view
        colorParams = params
    }

    /** Paints [look] at [strength]; also applies the look's brightness to the panel. */
    fun applyColorLook(look: ColorLook, strength: Float = 1f, brightness: Float = -1f) {
        try {
            val view = colorView ?: return
            view.applyLook(look, strength)
            view.setShowing(true)
            val effective = ColorLook.scaled(look, strength)
            val target = if (brightness > 0f) brightness else effective.brightness
            val params = colorParams
            if (params != null) {
                params.screenBrightness = if (target >= 0.999f) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    target.coerceIn(0.3f, 1f)
                }
                windowManager.updateViewLayout(view, params)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "applyColorLook failed: ${t.message}")
        }
    }

    fun setColorLayerStrength(strength: Float) {
        try {
            colorView?.setStrength(strength)
        } catch (t: Throwable) {
            Logger.w(TAG, "colour strength failed: ${t.message}")
        }
    }

    fun setColorLayerVisible(visible: Boolean) {
        try {
            colorView?.setShowing(visible)
        } catch (t: Throwable) {
            Logger.w(TAG, "colour visibility failed: ${t.message}")
        }
    }

    /** Exposed for the diagnostics panel: what is actually painted right now. */
    fun colorState(): String {
        return try {
            val view = colorView ?: return "none"
            val effective = view.currentLook()
            if (!view.isShowing()) "hidden" else "${effective.label} (${(effective.scrimAlpha * 100).toInt()}% scrim)"
        } catch (t: Throwable) {
            "none"
        }
    }

    /**
     * The live-HDR correction layer: full-screen, TRANSLUCENT, NOT touchable, added before the HUD so it
     * sits underneath the stats panel. Hidden until the capture engine delivers its first graded frame.
     * Translucency is mandatory: an opaque layer would be captured by MediaProjection and compound.
     */
    private fun attachHdrLayer() {
        val view = HdrSurfaceView(context)
        view.setVisible(false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        hdrView = view
        hdrParams = params
    }

    /** Shows/hides the graded frame layer (only meaningful once capture is delivering frames). */
    fun setHdrVisible(visible: Boolean) {
        try {
            hdrView?.setVisible(visible)
        } catch (t: Throwable) {
            Logger.w(TAG, "HDR visibility failed: ${t.message}")
        }
    }

    fun submitHdrFrame(bitmap: android.graphics.Bitmap, costMs: Long) {
        try {
            hdrView?.submitFrame(bitmap, costMs)
        } catch (t: Throwable) {
            Logger.w(TAG, "HDR frame push failed: ${t.message}")
        }
    }

    fun setHdrPresetLabel(label: String) {
        try {
            hdrView?.setPresetLabel(label)
        } catch (t: Throwable) {
            Logger.w(TAG, "HDR label failed: ${t.message}")
        }
    }

    private fun attachHud() {
        val view = StatsHudView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        hudView = view
        hudParams = params
    }

    private fun attachPuck() {
        val size = (72 * context.resources.displayMetrics.density).toInt()
        val view = FloatingPuckView(context)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - size - (14 * context.resources.displayMetrics.density).toInt())
                .coerceAtLeast(0)
            y = (72 * context.resources.displayMetrics.density).toInt()
        }
        view.onTap = { safely(onPuckTap) }
        view.onLongPress = { safely { openMenu() } }
        view.onDrag = { dx, dy -> movePuck(dx, dy) }
        view.onDragEnd = { snapLeft -> snapPuck(snapLeft) }
        windowManager.addView(view, params)
        puckView = view
        puckParams = params
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun movePuck(dx: Float, dy: Float) {
        try {
            val params = puckParams ?: return
            val view = puckView ?: return
            val metrics = context.resources.displayMetrics
            params.x = (params.x + dx.toInt()).coerceIn(0, max(0, metrics.widthPixels - view.width))
            params.y = (params.y + dy.toInt()).coerceIn(0, max(0, metrics.heightPixels - view.height))
            windowManager.updateViewLayout(view, params)
            repositionMenu()
        } catch (t: Throwable) {
            Logger.w(TAG, "puck move failed: ${t.message}")
        }
    }

    private fun snapPuck(snapLeft: Boolean) {
        try {
            val params = puckParams ?: return
            val view = puckView ?: return
            val metrics = context.resources.displayMetrics
            val margin = FloatingPuckView.snapOffset(context)
            params.x = if (snapLeft) margin else max(0, metrics.widthPixels - view.width - margin)
            // keep the puck vertically reachable (never under the system gesture bar)
            params.y = params.y.coerceIn(margin, max(margin, metrics.heightPixels - view.height - margin * 6))
            windowManager.updateViewLayout(view, params)
            repositionMenu()
        } catch (t: Throwable) {
            Logger.w(TAG, "puck snap failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ menu

    fun openMenu() {
        if (!attached) return
        try {
            closeMenu()
            val menu = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(com.dlss5.optimizer.R.drawable.bg_puck_menu)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            menu.addView(menuRow("STOP DLSS-5", MenuAction.STOP, Color.parseColor("#FF2D55")))
            menu.addView(menuRow("TOGGLE HUD", MenuAction.TOGGLE_HUD, Color.parseColor("#39FF14")))
            menu.addView(menuRow("NEXT MODE", MenuAction.NEXT_MODE, Color.parseColor("#00FFC6")))
            menu.addView(menuRow("CAPTURE DLSS", MenuAction.TOGGLE_CAPTURE, Color.parseColor("#FFC400")))
            menu.addView(menuRow("HDR LAYER", MenuAction.TOGGLE_HDR, Color.parseColor("#39FF14")))
            menu.addView(menuRow("HDR PRESET", MenuAction.NEXT_HDR_PRESET, Color.parseColor("#00FFC6")))
            menu.addView(menuRow("COLOUR LOOK ▸", MenuAction.NEXT_LOOK, Color.parseColor("#39FF14")))
            menu.addView(menuRow("◂ COLOUR LOOK", MenuAction.PREV_LOOK, Color.parseColor("#39FF14")))
            menu.addView(menuRow("NIGHT MODE", MenuAction.TOGGLE_NIGHT, Color.parseColor("#00FFC6")))
            menu.addView(menuRow("LOOK STRENGTH", MenuAction.LOOK_STRENGTH, Color.parseColor("#FFC400")))
            menu.addView(menuRow("SNAPSHOT", MenuAction.SNAPSHOT, Color.parseColor("#FFFFFF")))

            val params = WindowManager.LayoutParams(
                dp(168),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            windowManager.addView(menu, params)
            menuView = menu
            menuParams = params
            repositionMenu()
            Haptics.confirm(context)
        } catch (t: Throwable) {
            Logger.e(TAG, "openMenu failed", t)
            closeMenu()
        }
    }

    private fun menuRow(label: String, action: MenuAction, accent: Int): TextView =
        TextView(context).apply {
            text = label
            setTextColor(accent)
            textSize = 11.5f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = false
            setOnClickListener {
                safely {
                    closeMenu()
                    onMenuAction(action)
                }
            }
        }

    private fun repositionMenu() {
        try {
            val menu = menuView ?: return
            val params = menuParams ?: return
            val puck = puckParams ?: return
            val puckViewRef = puckView ?: return
            val metrics = context.resources.displayMetrics
            val menuWidth = params.width.takeIf { it > 0 } ?: dp(168)
            val placeRight = puck.x + puckViewRef.width / 2 < metrics.widthPixels / 2
            params.x = if (placeRight) {
                min(puck.x + puckViewRef.width + dp(4), metrics.widthPixels - menuWidth)
            } else {
                max(dp(4), puck.x - menuWidth - dp(4))
            }
            params.y = puck.y.coerceIn(dp(4), max(dp(4), metrics.heightPixels - dp(430)))
            windowManager.updateViewLayout(menu, params)
        } catch (t: Throwable) {
            Logger.w(TAG, "menu reposition failed: ${t.message}")
        }
    }

    fun closeMenu() {
        try {
            menuView?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Logger.w(TAG, "menu remove failed: ${t.message}")
        } finally {
            menuView = null
            menuParams = null
        }
    }

    /**
     * Applies a preferred refresh rate to the HUD window (the surface that owns the display mode while
     * we're on top). `0f` clears the preference and hands control back to the system.
     */
    fun applyPreferredRefreshRate(targetHz: Float, tier: SocTier): Boolean {
        return try {
            val view = hudView ?: hdrView ?: return false
            val params = hudParams ?: hdrParams ?: return false
            if (targetHz <= 0f) {
                params.preferredRefreshRate = 0f
                params.preferredDisplayModeId = 0
            } else if (!perfTuner.applyPreferredRate(params, targetHz, tier)) {
                return false
            }
            windowManager.updateViewLayout(view, params)
            true
        } catch (t: Throwable) {
            Logger.w(TAG, "preferred refresh rate failed: ${t.message}")
            false
        }
    }

    private val perfTuner by lazy { PerfTuner(context) }

    fun toggleHud() {
        val view = hudView ?: return
        view.setPanelVisible(!view.isPanelVisible())
    }

    fun setStatus(text: String, color: Int) {
        mainHandler.post {
            try {
                hudView?.setStatus(text, color)
            } catch (t: Throwable) {
                Logger.w(TAG, "status push failed: ${t.message}")
            }
        }
    }

    fun updateStats(stats: EngineController.LiveStats) {
        try {
            hudView?.update(stats)
            puckView?.setThermalWarning(stats.thermal >= 3)
        } catch (t: Throwable) {
            Logger.w(TAG, "stats push failed: ${t.message}")
        }
    }

    fun setPreview(frame: android.graphics.Bitmap?, alpha: Float) {
        try {
            hudView?.setPreview(frame, alpha)
        } catch (t: Throwable) {
            Logger.w(TAG, "preview push failed: ${t.message}")
        }
    }

    /** Must be called on rotation so the HUD matches the new viewport. */
    fun onConfigurationChanged() {
        try {
            colorView?.let { view ->
                colorParams?.let { params ->
                    val metrics = context.resources.displayMetrics
                    params.width = metrics.widthPixels
                    params.height = metrics.heightPixels
                    windowManager.updateViewLayout(view, params)
                    // force gradient rebuild for the new size
                    view.applyLook(view.currentLook(), 1f)
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "colour config change failed: ${t.message}")
        }
        try {
            hdrView?.let { view ->
                hdrParams?.let { params ->
                    val metrics = context.resources.displayMetrics
                    params.width = metrics.widthPixels
                    params.height = metrics.heightPixels
                    windowManager.updateViewLayout(view, params)
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "HDR config change failed: ${t.message}")
        }
        try {
            val hud = hudView ?: return
            val params = hudParams ?: return
            val metrics = context.resources.displayMetrics
            params.width = metrics.widthPixels
            params.height = metrics.heightPixels
            windowManager.updateViewLayout(hud, params)
            val puck = puckView ?: return
            val puckParamsRef = puckParams ?: return
            puckParamsRef.x = puckParamsRef.x.coerceIn(0, max(0, metrics.widthPixels - puck.width))
            puckParamsRef.y = puckParamsRef.y.coerceIn(0, max(0, metrics.heightPixels - puck.height))
            windowManager.updateViewLayout(puck, puckParamsRef)
        } catch (t: Throwable) {
            Logger.w(TAG, "config change failed: ${t.message}")
        }
    }

    fun detach() {
        attached = false
        closeMenu()
        try {
            colorView?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Logger.w(TAG, "colour detach failed: ${t.message}")
        }
        try {
            hdrView?.clearFrame()
            hdrView?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Logger.w(TAG, "hdr detach failed: ${t.message}")
        }
        try {
            hudView?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Logger.w(TAG, "hud detach failed: ${t.message}")
        }
        try {
            puckView?.let { windowManager.removeView(it) }
        } catch (t: Throwable) {
            Logger.w(TAG, "puck detach failed: ${t.message}")
        } finally {
            hudView = null
            colorView = null
            colorParams = null
            hdrView = null
            hdrParams = null
            puckView = null
            hudParams = null
            puckParams = null
            Logger.i(TAG, "overlay detached")
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logger.e(TAG, "overlay action failed", t)
        }
    }

    companion object {
        private const val TAG = "Overlay"
    }
}
