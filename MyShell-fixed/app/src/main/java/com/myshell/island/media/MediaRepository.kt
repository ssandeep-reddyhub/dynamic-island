package com.myshell.island.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.myshell.island.data.MusicState
import com.myshell.island.service.ShellNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real media-session integration. Android only lets an app list other apps' active sessions if it
 * holds Notification Access, so the listener component is what authorises [MediaSessionManager].
 * All calls here must happen on the main thread.
 */
class MediaRepository(private val context: Context) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, ShellNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<MusicState?>(null)
    val state: StateFlow<MusicState?> = _state.asStateFlow()

    /** False while Notification Access is missing, so the UI can explain why nothing shows. */
    private val _hasAccess = MutableStateFlow(true)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var registered = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            pickCurrent()
            publish()
        }
        override fun onSessionDestroyed() = refresh()
    }

    /** Safe to call repeatedly (service start, listener connect, app resume). */
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start() }
            return
        }
        try {
            if (registered) manager.removeOnActiveSessionsChangedListener(sessionsListener)
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            registered = true
            _hasAccess.value = true
            setControllers(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            registered = false
            _hasAccess.value = false
            setControllers(emptyList())
        }
    }

    fun stop() {
        if (registered) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        registered = false
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        current = null
        _state.value = null
    }

    private fun refresh() = start()

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list.filter { it.packageName !in IGNORED_PACKAGES }
        controllers.forEach { it.registerCallback(callback, main) }
        pickCurrent(force = true)
        publish()
    }

    private fun pickCurrent(force: Boolean = false) {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        current = when {
            playing != null -> playing
            !force && current != null && controllers.any { it.sessionToken == current?.sessionToken } -> current
            else -> controllers.firstOrNull { it.metadata != null } ?: controllers.firstOrNull()
        }
    }

    private fun publish() {
        val c = current
        val md = c?.metadata
        if (c == null || md == null) {
            _state.value = null
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) {
            _state.value = null
            return
        }
        val ps = c.playbackState
        val actions = ps?.actions ?: 0L
        _state.value = MusicState(
            pkg = c.packageName,
            appName = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrDefault(c.packageName),
            title = title,
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            art = artwork(md),
            playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING,
            positionMs = ps?.position?.coerceAtLeast(0L) ?: 0L,
            updateTime = ps?.lastPositionUpdateTime ?: 0L,
            speed = ps?.playbackSpeed ?: 1f,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    private fun artwork(md: MediaMetadata): Bitmap? =
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: runCatching {
                val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                if (uri != null && uri.startsWith("content://")) {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                } else null
            }.getOrNull()

    // --- transport controls -------------------------------------------------------------------

    fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = current?.transportControls?.skipToNext()
    fun previous() = current?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = current?.transportControls?.seekTo(ms)

    /** Brings the playing app to the front (its own session activity if it offers one). */
    fun openPlayer() {
        val c = current ?: return
        val pi = c.sessionActivity
        if (pi != null) {
            runCatching { pi.send() }
            return
        }
        context.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Apps whose playback is never shown in the island. */
private val IGNORED_PACKAGES = setOf("com.google.android.youtube")
