package com.myshell.island.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myshell.island.R
import com.myshell.island.appContainer
import com.myshell.island.ui.MainActivity
import com.myshell.island.util.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the island alive as a foreground service and, in the normal case, hosts the island window above other
 * apps (TYPE_APPLICATION_OVERLAY). Android hides that kind of window on the lock screen, so when the user has
 * switched on the MyShell accessibility service, *it* hosts the window instead (see [IslandAccessibilityService])
 * and this service steps aside - never two islands at once.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: IslandWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNow()
        if (!Settings.canDrawOverlays(this) && IslandAccessibilityService.instance == null) {
            stopSelf()
            return
        }
        acquireWindow()
        applicationContext.appContainer.media.start()
        // Switching the island off in settings stops the service.
        scope.launch {
            applicationContext.appContainer.settings.flow.map { it.enabled }.distinctUntilChanged()
                .collect { if (!it) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY // Android restarts us if it kills the process
    }

    /** Shows the island in this service's own window (unless the accessibility service already does). */
    fun acquireWindow() {
        if (window != null || IslandAccessibilityService.instance != null) return
        if (!Settings.canDrawOverlays(this)) return
        window = IslandWindow(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.start() }
    }

    /** Removes this service's window (the accessibility service takes over). */
    fun releaseWindow() {
        window?.stop()
        window = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window?.onConfigurationChanged()
    }

    private fun startForegroundNow() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    override fun onDestroy() {
        window?.stop()
        window = null
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
        /** Keep in sync with IslandHost: distance from window top to the camera centre (capped by its real position). */
        const val ISLAND_LEAD_DP = 32f
        private const val ACTION_STOP = "com.myshell.island.STOP"

        @Volatile
        var instance: OverlayService? = null
            private set

        /** Starts the island when it can be shown (overlay permission or accessibility service); never throws. */
        fun startIfAllowed(context: Context) {
            if (!Settings.canDrawOverlays(context) && IslandAccessibilityService.instance == null) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
