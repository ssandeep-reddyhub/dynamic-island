package com.myshell.island.ui.island

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.myshell.island.appContainer
import com.myshell.island.ui.theme.Ink
import kotlinx.coroutines.delay

/**
 * Bridge from the island UI to the overlay service:
 *  - [setFocusable]: ask for keyboard focus (inline reply),
 *  - [setWindowSize]: the window size (dp) the current state needs - the service grows it before an
 *    animation and shrinks it afterwards, so the window is never resized frame-by-frame,
 *  - [setIslandBounds]: where the island currently is, so taps outside it can collapse it.
 */
class OverlayController(
    val setFocusable: (Boolean) -> Unit,
    val setWindowSize: (widthDp: Float, heightDp: Float) -> Unit = { _, _ -> },
    val setIslandBounds: (left: Float, top: Float, right: Float, bottom: Float) -> Unit = { _, _, _, _ -> },
    /** Called once the island has finished animating: the window may now shrink to the exact size. */
    val commitWindowSize: () -> Unit = {},
)

val LocalOverlay = staticCompositionLocalOf { OverlayController({ }) }

/** Click without the Material ripple: the island has its own press feedback. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = MutableInteractionSource(), indication = null, enabled = enabled, onClick = onClick)

@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    tabular: Boolean = false,
    center: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFeatureSettings = if (tabular) "tnum" else null,
            textAlign = if (center) TextAlign.Center else TextAlign.Unspecified,
        ),
    )
}

@Composable
fun Icon24(@DrawableRes id: Int, tint: Color = Color.White, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

@Composable
fun PillButton(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    textSize: TextUnit = 14.sp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .tap(enabled, onClick)
            .padding(horizontal = height * 0.48f),
        contentAlignment = Alignment.Center,
    ) {
        T(label, textSize, if (enabled) fg else fg.copy(alpha = 0.4f), FontWeight.SemiBold)
    }
}

@Composable
fun CircleIconButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = Ink.Card2,
    tint: Color = Color.White,
    size: Dp = 30.dp,
    iconSize: Dp = 15.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon24(icon, if (enabled) tint else tint.copy(alpha = 0.35f), iconSize)
    }
}

/** Ticks `System.currentTimeMillis()` every [periodMs] while [active]; costs nothing when idle. */
@Composable
fun rememberNow(active: Boolean, periodMs: Long): State<Long> =
    produceState(System.currentTimeMillis(), active, periodMs) {
        value = System.currentTimeMillis()
        while (active) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }

@Composable
fun AppIcon(pkg: String, size: Dp, modifier: Modifier = Modifier) {
    val icons = LocalContext.current.applicationContext.appContainer.icons
    val bmp: ImageBitmap? by produceState(icons.peek(pkg), pkg) { value = icons.load(pkg) }
    val shape = RoundedCornerShape(size * 0.24f)
    val m = modifier.size(size).clip(shape)
    if (bmp != null) {
        Image(bitmap = bmp!!, contentDescription = null, contentScale = ContentScale.Fit, modifier = m)
    } else {
        Box(m.background(Ink.Card2))
    }
}

/** Progress ring used by the timer and the system tiles. */
@Composable
fun Ring(
    progress: Float,
    color: Color,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            val topLeft = Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}
