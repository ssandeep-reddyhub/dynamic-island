package com.myshell.island.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colour tokens carried over from the HTML prototype. */
object Ink {
    val Black = Color(0xFF000000)
    val Card = Color(0xFF1C1C1E)
    val Card2 = Color(0xFF2C2C2E)
    val Dim = Color(0xFF8E8E93)
    val Orange = Color(0xFFFF9F0A)
    val Green = Color(0xFF30D158)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF453A)
    val Pink = Color(0xFFFF375F)
    val Yellow = Color(0xFFFFD60A)
    val Purple = Color(0xFFBF5AF2)
    val Cyan = Color(0xFF64D2FF)
    val GoBg = Color(0xFF0E3B1B)
    val WarnBg = Color(0xFF3D2A00)
    val PriBg = Color(0xFF06305F)
}

/** Theme for the settings app. The island itself is always pure black, like the prototype. */
@Composable
fun MyShellTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Ink.Blue, background = Color(0xFF000000), surface = Color(0xFF111113),
            surfaceVariant = Ink.Card, onBackground = Color.White, onSurface = Color.White,
            onSurfaceVariant = Ink.Dim, secondaryContainer = Color(0xFF16324F),
        )
    } else {
        lightColorScheme(primary = Ink.Blue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
