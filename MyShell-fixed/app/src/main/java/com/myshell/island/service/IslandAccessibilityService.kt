package com.myshell.island.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.myshell.island.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Optional. Hosts the island in an accessibility overlay window, which Android keeps on screen above the lock
 * screen (a normal overlay is hidden there). It reads nothing from the screen: no events, no window content.
 * Without it the island works exactly as before, above apps but not on the lock screen.
 */
class IslandAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: IslandWindow? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OverlayService.instance?.releaseWindow() // never two islands at once
        val settings = applicationContext.appContainer.settings
        scope.launch {
            settings.flow.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    if (window == null) {
                        window = IslandWindow(this@IslandAccessibilityService, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                            .also { it.start() }
                    }
                    OverlayService.startIfAllowed(this@IslandAccessibilityService) // foreground notification keeps the process alive
                } else {
                    window?.stop()
                    window = null
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        window?.stop()
        window = null
        scope.cancel()
        if (instance === this) {
            instance = null
            // Fall back to the normal overlay when the user switches the accessibility service off.
            OverlayService.instance?.acquireWindow()
        }
    }

    companion object {
        @Volatile
        var instance: IslandAccessibilityService? = null
            private set
    }
}
