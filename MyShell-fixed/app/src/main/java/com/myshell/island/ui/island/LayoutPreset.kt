package com.myshell.island.ui.island

import com.myshell.island.data.ShellSettings

/** One-tap island sizes in the settings app. Idle size / pill height go back to "hug the camera". */
enum class LayoutPreset(val label: String, val width: Int, val panel: Float, val pill: Float) {
    Small("Small", 264, 0.9f, 0.85f),
    Default("Default", 300, 1f, 1f),
    Large("Large", 336, 1.1f, 1.15f);

    fun matches(s: ShellSettings): Boolean =
        s.widthDp == width && s.panelScale == panel && s.pillScale == pill
}
