package com.myshell.island.ui

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import com.myshell.island.appContainer

/**
 * Android 10+ only lets the focused app read the clipboard. This invisible activity takes focus for
 * a split second, stores what was copied, and closes - the supported way to read it, no workaround.
 */
class ClipboardCaptureActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        if (clip != null && clip.itemCount > 0 && !sensitive && clip.description.hasMimeType("text/*")) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            applicationContext.appContainer.clipboard.add(text)
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
