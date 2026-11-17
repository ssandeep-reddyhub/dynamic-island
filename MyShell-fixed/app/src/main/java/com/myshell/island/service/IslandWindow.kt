package com.myshell.island.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.myshell.island.R
import com.myshell.island.appContainer
import com.myshell.island.ui.MainActivity
import com.myshell.island.ui.VMFactory
import com.myshell.island.ui.island.IslandHost
import com.myshell.island.ui.island.IslandViewModel
import com.myshell.island.ui.island.OverlayController
import com.myshell.island.ui.island.CutoutDp
import com.myshell.island.util.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating island window: a Compose view added to the WindowManager of [ctx].
 *
 *  - [OverlayService] hosts it as TYPE_APPLICATION_OVERLAY (above apps, hidden on the lock screen).
 *  - [IslandAccessibilityService] hosts it as TYPE_ACCESSIBILITY_OVERLAY, which Android also draws on the lock screen.
 *
 * The window has a FIXED size per state and Compose animates the island inside it. The window grows before an
 * animation and shrinks after it, so it is never resized frame-by-frame. It is positioned over the camera cut-out.
 */
class IslandWindow(private val ctx: Context, private val windowType: Int) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val resources get() = ctx.resources
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: OverlayRoot? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var vm: IslandViewModel

    private class CutoutPx(val centerX: Float, val top: Float, val width: Float, val height: Float)
    private var cutout: CutoutPx? = null
    private var offX = 0
    private var offY = 0
    private var notch = false
    private var sizeSet = false
    private var targetW = 0
    private var targetH = 0
    private var shrinkJob: Job? = null
    private val islandBounds = RectF()

    private fun addIsland() {
        val container = ctx.applicationContext.appContainer
        vm = ViewModelProvider(owner, VMFactory(container, ctx.applicationContext))[IslandViewModel::class.java]
        offX = container.settings.state.value.effective().offsetX
        offY = container.settings.state.value.effective().offsetY
        notch = container.settings.state.value.isNotch
        readCutout()

        val view = OverlayRoot(ctx).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            onOutsideTouch = { vm.collapse() }
            isExpanded = { vm.ui.value.expanded }
            bounds = islandBounds
            onBack = { if (vm.ui.value.expanded) { vm.collapse(); true } else false }
        }
        val compose = ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                IslandHost(
                    vm,
                    OverlayController(
                        setFocusable = { focusable -> setFocusable(focusable) },
                        setWindowSize = { w, h -> setWindowSize(w, h) },
                        commitWindowSize = { commitWindowSize() },
                        setIslandBounds = { l, t, r, b -> islandBounds.set(l, t, r, b) },
                    ),
                )
            }
        }
        view.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val d = resources.displayMetrics.density
        params = WindowManager.LayoutParams(
            (120 * d).roundToInt(),
            (48 * d).roundToInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Overlay windows are NOT hardware-accelerated unless asked: without this every frame of
                // the island animation is drawn on the CPU, which is what made it stutter.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            windowAnimations = 0 // never let the system animate this window itself
            // Hidden PRIVATE_FLAG_NO_MOVE_ANIMATION: stops some ROMs animating the window when it is resized.
            // Best effort only: ignored where Android does not allow it.
            runCatching {
                val f = WindowManager.LayoutParams::class.java.getField("privateFlags")
                f.setInt(this, f.getInt(this) or 0x00000040)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                // Let the island sit next to / inside the camera cut-out.
                // ALWAYS (API 30+) keeps the island drawable in landscape too; SHORT_EDGES elsewhere.
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyPosition()
        wm.addView(view, params)
        root = view
        applyVisibility()
        runCatching { wm.updateViewLayout(view, params) }
        owner.resume()
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        // Live settings: position (switching the island off is handled by whoever hosts this window).
        scope.launch {
            container.settings.flow.map { val e = it.effective(); listOf(e.offsetX, e.offsetY, if (it.isNotch) 1 else 0) }.distinctUntilChanged()
                .collect { v ->
                    val x = v[0]
                    val y = v[1]
                    offX = x
                    offY = y
                    notch = v[2] == 1
                    applyPosition()
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                }
        }
        // Hidden while a phone call is ringing or in progress.
        scope.launch {
            vm.call.map { it != null }.distinctUntilChanged().collect {
                callActive = it
                applyVisibility()
                root?.let { r -> runCatching { wm.updateViewLayout(r, params) } }
            }
        }
        // Keyboard focus is only granted while the panel is open (needed for inline reply).
        scope.launch {
            vm.ui.map { it.expanded }.distinctUntilChanged().collect { if (!it) setFocusable(false) }
        }
    }

    private fun screenWidthPx(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels

    /** Finds the camera hole / notch (portrait only) and tells the UI its size in dp. */
    private fun readCutout() {
        val d = resources.displayMetrics.density
        var rect: android.graphics.Rect? = null
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            if (Build.VERSION.SDK_INT >= 30) {
                val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                rect = display?.cutout?.boundingRectTop
            } else if (Build.VERSION.SDK_INT >= 28) {
                rect = root?.rootWindowInsets?.displayCutout?.boundingRects
                    ?.filter { it.top <= 2 }?.maxByOrNull { it.width() }
            }
        }
        if (rect == null || rect.isEmpty) {
            cutout = null
            vm.setCutout(null)
        } else {
            cutout = CutoutPx(rect.exactCenterX(), rect.top.toFloat(), rect.width().toFloat(), rect.height().toFloat())
            vm.setCutout(CutoutDp(rect.width() / d, rect.height() / d, rect.exactCenterY() / d))
        }
    }

    /** Centres the window on the camera hole (if any) and applies the user's manual adjustment. */
    private fun applyPosition() {
        val d = resources.displayMetrics.density
        val c = cutout
        if (c != null) {
            val half = screenWidthPx() / 2f
            val margin = (params.width / 2f).coerceAtMost(half)
            // Centred on the camera, but never pushed past the screen edge (punch-hole phones with an off-centre camera).
            params.x = (c.centerX - half + offX * d).coerceIn(-(half - margin), half - margin).roundToInt()
            // The window's top sits `lead` above the camera centre; the island is centred on the camera.
            // The notch hangs from the very top edge of the screen instead.
            val centerY = c.top + c.height / 2f
            val lead = min(OverlayService.ISLAND_LEAD_DP * d, centerY)
            params.y = if (notch) max(0f, offY * d).roundToInt() else max(0f, centerY - lead + offY * d).roundToInt()
        } else {
            params.x = (offX * d).roundToInt()
            params.y = max(0f, offY * d).roundToInt() // no cut-out: camera centre assumed 18 dp, lead 18 dp
        }
    }

    /** Grows the window at once (before the island animates open). Shrinking waits for [commitWindowSize]. */
    private fun setWindowSize(wDp: Float, hDp: Float) {
        val r = root ?: return
        val d = resources.displayMetrics.density
        // Slack around the island so the spring's tiny overshoot is never clipped by the window edge.
        val w = (wDp * d).roundToInt() + (SLACK_DP * d).roundToInt()
        val h = (hDp * d).roundToInt() + (SLACK_DP * d).roundToInt()
        targetW = w
        targetH = h
        if (!sizeSet) {
            sizeSet = true
            params.width = w
            params.height = h
        } else if (w > params.width || h > params.height) {
            params.width = max(params.width, w)
            params.height = max(params.height, h)
        } else {
            return
        }
        applyPosition()
        runCatching { wm.updateViewLayout(r, params) }
    }

    /**
     * Called by the UI once the island has finished animating: only now is the window cut down to the exact
     * size. Shrinking on a timer used to clip the island mid-animation, which looked like a second jump.
     */
    private fun commitWindowSize() {
        val r = root ?: return
        if (!sizeSet || (params.width == targetW && params.height == targetH)) return
        params.width = targetW
        params.height = targetH
        applyPosition()
        runCatching { wm.updateViewLayout(r, params) }
    }

    private val displays get() = ctx.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    // Rotation is detected here as well (not only through the host service's onConfigurationChanged, which some
    // ROMs deliver late or not at all): the display listener fires on every rotation / fold.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onConfigurationChanged()
        }
    }

    /** Call when the screen configuration changes (rotation, fold): re-reads the camera cut-out and re-places the window. */
    fun onConfigurationChanged() {
        if (root == null) return
        reapply()
        // The system finishes the rotation a moment later; apply again so the window ends up on the final geometry.
        handler.removeCallbacks(reapplyLater)
        handler.postDelayed(reapplyLater, 150)
        handler.postDelayed(reapplyLater, 500)
    }

    private val reapplyLater = Runnable { reapply() }

    @Volatile private var callActive = false

    /** The island is hidden while a call is ringing / in progress, and while the screen is rotated to landscape, and comes back in portrait. */
    private fun applyVisibility() {
        val r = root ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || callActive
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (landscape) {
            vm.collapse()
            params.flags = params.flags or flag
            r.visibility = View.GONE
        } else {
            params.flags = params.flags and flag.inv()
            r.visibility = View.VISIBLE
        }
    }

    private fun reapply() {
        val r = root ?: return
        readCutout()
        applyPosition()
        applyVisibility()
        runCatching { wm.updateViewLayout(r, params) }
        r.requestLayout()
        r.invalidate()
    }

    /** Toggles FLAG_NOT_FOCUSABLE so a text field can open the keyboard, then restores it. */
    private fun setFocusable(focusable: Boolean) {
        val r = root ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val has = params.flags and flag != 0
        if (focusable != !has) {
            params.flags = if (focusable) params.flags and flag.inv() else params.flags or flag
            runCatching { wm.updateViewLayout(r, params) }
        }
    }

    fun start() {
        owner.create()
        addIsland()
        ctx.applicationContext.appContainer.media.start()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { displays.unregisterDisplayListener(displayListener) }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        owner.destroy()
        scope.cancel()
    }

    private companion object {
        const val SLACK_DP = 12f
    }
}

/** Lifecycle + ViewModel + SavedState owner for a Compose view that lives outside any Activity. */
private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun create() {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

/** Root of the overlay window: reports taps outside the island and the Back key. */
private class OverlayRoot(context: Context) : FrameLayout(context) {
    var onOutsideTouch: (() -> Unit)? = null
    var onBack: (() -> Boolean)? = null
    var isExpanded: (() -> Boolean)? = null
    var bounds: RectF? = null

    /** While the panel is open, a touch outside the island itself (but inside the window) collapses it. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isExpanded?.invoke() == true) {
            val b = bounds
            if (b != null && !b.isEmpty && !b.contains(event.x, event.y)) {
                onOutsideTouch?.invoke()
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && onBack?.invoke() == true) return true
        return super.dispatchKeyEvent(event)
    }
}
