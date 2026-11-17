package com.myshell.island.ui.island

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.myshell.island.R
import com.myshell.island.ui.theme.Ink

/**
 * Everything that can be "active" in the island at the same time. Only one is shown in the pill; the others keep
 * running in the background (music keeps playing, the timer keeps counting) and are offered as small indicators.
 */
enum class ActivityKind(val label: String, @DrawableRes val icon: Int, val color: Color) {
    Music("Music", R.drawable.ic_music, Ink.Pink),
    Timer("Timer", R.drawable.ic_timer, Ink.Orange),
    Stopwatch("Stopwatch", R.drawable.ic_stopwatch, Ink.Orange),
    Notifications("Notifications", R.drawable.ic_bell, Ink.Blue),
    Charging("Charging", R.drawable.ic_bolt, Ink.Green),
    Hotspot("Hotspot", R.drawable.ic_hotspot, Ink.Green),
    Recording("Screen recording", R.drawable.ic_rec, Ink.Red),
    Navigation("Navigation", R.drawable.ic_nav, Ink.Blue);

    companion object {
        val DEFAULT_ORDER = entries.toList()

        /** Parses the saved priority string; unknown or missing kinds are appended so nothing is ever lost. */
        fun parseOrder(raw: String): List<ActivityKind> {
            val parsed = raw.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.distinct()
            return parsed + entries.filter { it !in parsed }
        }

        fun serialize(order: List<ActivityKind>): String = order.joinToString(",") { it.name }

        fun fromName(name: String): ActivityKind? = entries.firstOrNull { it.name == name }
    }
}
