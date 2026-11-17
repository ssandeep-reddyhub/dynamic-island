package com.myshell.island.di

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.myshell.island.data.ClipboardRepository
import com.myshell.island.data.ClockTimerRepository
import com.myshell.island.data.NotificationRepository
import com.myshell.island.data.SettingsRepository
import com.myshell.island.data.ShortcutRepository
import com.myshell.island.media.MediaRepository
import com.myshell.island.system.SystemMonitor
import com.myshell.island.util.SoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual dependency injection: one lazily-built graph owned by the Application, handed to
 * ViewModels and services. No reflection, no code generation, nothing to keep in sync.
 */
class AppContainer(context: Context) {
    val app: Context = context.applicationContext

    /** Long-lived scope for work that must outlive any single screen (persistence, timers). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsRepository(app, scope) }
    val sounds by lazy { SoundPlayer(app) }
    val notifications by lazy { NotificationRepository(app, scope, settings) }
    /** Preview commands from the settings app to the island service. */
    val commands = kotlinx.coroutines.flow.MutableSharedFlow<com.myshell.island.data.IslandCommand>(extraBufferCapacity = 8)
    val clock by lazy { ClockTimerRepository() }
    val live by lazy { com.myshell.island.data.LiveActivityRepository() }
    val media by lazy { MediaRepository(app) }
    val shortcuts by lazy { ShortcutRepository(app, scope) }
    val clipboard by lazy { ClipboardRepository(app, scope) }
    val system by lazy { SystemMonitor(app) }
    val icons by lazy { AppIconCache(app) }
}

/** Decoded launcher icons, cached so scrolling rows never touch PackageManager twice. */
class AppIconCache(private val context: Context) {
    private val bitmaps = LruCache<String, ImageBitmap>(96)
    private val labels = ConcurrentHashMap<String, String>()

    fun peek(pkg: String): ImageBitmap? = bitmaps.get(pkg)

    suspend fun load(pkg: String): ImageBitmap? = bitmaps.get(pkg) ?: withContext(Dispatchers.Default) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(112, 112).asImageBitmap() }
            .getOrNull()
            ?.also { bitmaps.put(pkg, it) }
    }

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
