package com.myshell.island.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.myshell.island.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the island after reboot / app update when the user enabled "start on boot". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.applicationContext.appContainer
        container.scope.launch {
            try {
                val s = container.settings.flow.first()
                if (s.enabled && s.startOnBoot && Settings.canDrawOverlays(context)) {
                    OverlayService.startIfAllowed(context.applicationContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
