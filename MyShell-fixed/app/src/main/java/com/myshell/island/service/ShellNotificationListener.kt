package com.myshell.island.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.myshell.island.appContainer

/**
 * The heart of the app: receives *real* notifications from every other app once the user grants
 * Notification Access. Parsed items go into [com.myshell.island.data.NotificationRepository];
 * nothing is sent anywhere.
 */
class ShellNotificationListener : NotificationListenerService() {

    private val container get() = applicationContext.appContainer

    override fun onListenerConnected() {
        super.onListenerConnected()
        container.notifications.canceller = { key -> runCatching { cancelNotification(key) } }
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, initial = true) }
        // Notification Access is also what authorises the media-session list.
        container.media.start()
        if (container.settings.state.value.enabled) OverlayService.startIfAllowed(this)
    }

    override fun onListenerDisconnected() {
        container.notifications.canceller = null
        container.clock.clear()
        container.live.clear()
        container.media.stop()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, initial = false)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        container.notifications.onRemoved(sbn.key)
        container.clock.onRemoved(sbn.key)
        container.live.onRemoved(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification, initial: Boolean) {
        container.clock.onPosted(sbn) // Clock app timer / stopwatch -> shown live in the island
        container.live.onPosted(sbn)  // screen recording / navigation -> shown live in the island
        val mapped = NotificationMapper.map(this, sbn) ?: return
        container.notifications.onPosted(mapped, initial)
    }
}
