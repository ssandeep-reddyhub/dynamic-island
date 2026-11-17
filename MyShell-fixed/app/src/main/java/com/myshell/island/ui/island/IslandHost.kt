package com.myshell.island.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myshell.island.R
import com.myshell.island.data.BatteryInfo
import com.myshell.island.data.ShellNotification
import com.myshell.island.data.ShellSettings
import com.myshell.island.ui.theme.Ink
import com.myshell.island.util.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class Mode { Compact, Alert, Event, Expanded }

/** Entry point composed inside the overlay window. */
@Composable
fun IslandHost(vm: IslandViewModel, overlay: OverlayController) {
    val ui by vm.ui.collectAsState()
    val raw by vm.settings.collectAsState()
    val st = remember(raw) { raw.effective() }

    // Notifications seen while the panel is on the Notifications tab count as read.
    LaunchedEffect(ui.expanded, ui.tab) {
        if (ui.expanded && ui.tab == IslandTab.Notifications) {
            delay(1200)
            vm.markRead()
        }
    }

    CompositionLocalProvider(LocalOverlay provides overlay) {
        DynamicIsland(vm, ui, st)
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun DynamicIsland(vm: IslandViewModel, ui: IslandViewModel.Ui, st: ShellSettings) {
    val active by vm.notifications.collectAsState()
    val cutout by vm.cutout.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val shownKind by vm.shownKind.collectAsState()
    val secondary by vm.secondaryKinds.collectAsState()
    val overlay = LocalOverlay.current

    val mode = when {
        ui.expanded -> Mode.Expanded
        ui.alert != null -> Mode.Alert
        ui.event != null -> Mode.Event
        else -> Mode.Compact
    }
    val scale = st.widthDp / 300f
    val pill = st.pillScale.coerceIn(0.6f, 1.6f)
    val screenW = LocalConfiguration.current.screenWidthDp
    val expandedW = min(st.widthDp, screenW - 16).toFloat()
    val speed = st.animSpeed.coerceIn(0.4f, 2.5f)
    val busy = shownKind != null

    // --- sizes (dp). Idle = a small black shape hugging the camera hole / notch. -----------------
    val idleW = (((cutout?.widthDp?.plus(6f)) ?: 64f) + st.idleW).coerceAtLeast(10f)
    val idleH = (((cutout?.heightDp?.plus(4f)) ?: 22f) + st.idleH).coerceAtLeast(10f)
    val panelScale = st.panelScale.coerceIn(0.7f, 1.5f)
    val topInset = cutout?.let { it.heightDp + 6f } ?: 0f          // content starts below the camera
    // Width = camera + room for what the pill shows on each side of the camera. Sizing it from the content (not
    // from the open width) keeps charging, timers and recording from being squeezed on the smallest islands.
    val compactSide = when (shownKind) {
        ActivityKind.Music, ActivityKind.Notifications, ActivityKind.Hotspot -> 30f
        ActivityKind.Timer, ActivityKind.Stopwatch -> 44f
        ActivityKind.Charging -> 36f
        ActivityKind.Recording -> 52f
        ActivityKind.Navigation -> 66f
        null -> 0f
    }
    val sideScale = pill.coerceIn(0.6f, 1.6f)
    val compactSideDp = compactSide * sideScale
    val compactW = idleW + 2f * compactSideDp + if (busy && secondary.isNotEmpty()) 12f else 0f
    val eventH = max(30f + st.pillH, idleH)
    // Status pills hold one small icon on each side of the camera, so they only need a little extra width.
    val eventSide = when (ui.event) {
        IslandEvent.Charging, IslandEvent.LowBattery -> 36f
        IslandEvent.Hotspot -> 32f
        IslandEvent.Bluetooth -> 54f
        else -> 28f
    }
    val eventSideDp = eventSide * sideScale
    val eventW = idleW + 2f * eventSideDp
    val compactH = if (busy) max(32f + st.pillH, idleH) else idleH
    val alertW = max(expandedW - 24f, 200f)
    val alertH = topInset + 78f
    val maxTabH = tabs.maxOf { it.contentHeight.value } * panelScale
    val tabH = ui.tab.contentHeight.value * panelScale
    val expandedH = topInset + tabH + TAB_BAR_DP
    // The island is centred on the camera while it is a pill; panels hang down from the camera's top.
    val lead = min(com.myshell.island.service.OverlayService.ISLAND_LEAD_DP, cutout?.centerYDp ?: 18f)

    // Notch mode: the shape is flanked by small concave "ears" at the top edge, like the iPhone notch.
    val notch = st.isNotch
    val earT = if (notch) EAR_DP else 0f
    val bodyW = when (mode) {
        Mode.Expanded -> expandedW
        Mode.Alert -> alertW
        Mode.Event -> eventW
        Mode.Compact -> compactW
    }
    val targetW = bodyW + 2f * earT
    val targetH = when (mode) {
        Mode.Expanded -> expandedH
        Mode.Alert -> alertH
        Mode.Event -> eventH
        Mode.Compact -> compactH
    }
    // Bottom corner radius (and top corner radius: the pill is rounded all round, the notch is flat on top).
    val custom = if (notch) st.nRadius.toFloat() else st.iRadius.toFloat() // island: 0 = automatic
    val targetRadius = when (mode) {
        Mode.Expanded -> if (notch) custom + 18f else 34f
        Mode.Alert -> if (notch) custom + 12f else 28f
        Mode.Event -> if (notch || custom > 0f) custom else eventH / 2f
        Mode.Compact -> if (notch || custom > 0f) custom else compactH / 2f
    }
    val targetTopR = if (notch) 0f else targetRadius
    val padTop = when (mode) {
        Mode.Expanded, Mode.Alert -> lead - idleH / 2f
        Mode.Event -> lead - eventH / 2f
        Mode.Compact -> lead - compactH / 2f
    }.coerceAtLeast(0f).let { if (notch) 0f else it } // the notch hangs from the top edge of the screen

    // The overlay window is resized only on state changes (never per animation frame). It grows at once and is
    // cut down only AFTER the animation has really finished (see below). Expanded always reserves the tallest
    // tab so switching tabs never resizes the window.
    val windowW = targetW
    val windowH = padTop + when (mode) {
        Mode.Expanded -> topInset + maxTabH + TAB_BAR_DP
        Mode.Alert -> alertH
        Mode.Event -> eventH
        Mode.Compact -> compactH
    }

    // One spring for every dimension, so width, height, radius and position move as a single shape.
    // The values are read in the layout / draw phase below, so the animation never recomposes the UI.
    // Opening may bounce a little. Closing must NOT: a bouncy spring overshoots the tiny camera pill (a few dp
    // too small, then back), which looks like the corners jumping twice. So shrinking is critically damped.
    val bouncy = mode == Mode.Expanded || mode == Mode.Alert
    val spec = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = if (bouncy) 0.82f else 1f,
        stiffness = (if (bouncy) 650f else 800f) * speed,
    )
    val widthS = animateDpAsState(targetW.dp, spec, label = "w")
    val heightS = animateDpAsState(targetH.dp, spec, label = "h")
    val radiusS = animateDpAsState(targetRadius.dp, spec, label = "r")
    val topS = animateDpAsState(padTop.dp, spec, label = "top")
    val topRS = animateDpAsState(targetTopR.dp, spec, label = "rt")
    val earS = animateDpAsState(earT.dp, spec, label = "ear")
    val fill = Color(st.bgColor).copy(alpha = st.bgAlpha.coerceIn(0.2f, 1f))
    // Pill content is invisible until the island is (almost) at its final width. Content laid out while the shape
    // is still moving slides sideways towards the centre, which looked like the pill "moving left to centre twice".
    val reveal: () -> Float = { (1f - abs(widthS.value.value - targetW) / 40f).coerceIn(0f, 1f) }

    // Grow the window first, wait until the island has settled on its new size, then shrink the window.
    // (Shrinking on a fixed timer cut the island off mid-animation: that was the "second jump" on closing.)
    LaunchedEffect(mode, windowW, windowH, targetW, targetH, padTop) {
        overlay.setWindowSize(windowW, windowH)
        withTimeoutOrNull(2000) {
            snapshotFlow { widthS.value == targetW.dp && heightS.value == targetH.dp && topS.value == padTop.dp }.first { it }
        }
        overlay.commitWindowSize()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val w = widthS.value.roundToPx().coerceIn(0, constraints.maxWidth)
                    val h = heightS.value.roundToPx().coerceIn(0, constraints.maxHeight)
                    val top = topS.value.roundToPx()
                    val p = measurable.measure(Constraints.fixed(w, h))
                    layout(w, (h + top).coerceAtMost(constraints.maxHeight)) { p.place(0, top) }
                }
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    overlay.setIslandBounds(b.left, b.top, b.right, b.bottom)
                }
        ) {
            // The black shape lives in its own layer: scrolling a long notification list redraws the content
            // layer below, but never rebuilds / re-draws this shape (that was a big part of the scroll lag).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawBehind {
                        val path = islandPath(size.width, size.height, earS.value.toPx(), topRS.value.toPx(), radiusS.value.toPx())
                        drawPath(path, fill)
                        if (!notch) drawPath(path, Color.White.copy(alpha = 0.06f), style = Stroke(0.5.dp.toPx()))
                    }
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Clip with a plain rounded rectangle (hardware-fast path); a free-form path clip is slow.
                        shape = IslandShape(earS.value.toPx(), topRS.value.toPx(), radiusS.value.toPx())
                        clip = true
                    }
            ) {
            AnimatedContent(
                modifier = Modifier.layout { measurable, constraints ->
                    // Keep the content between the notch's ears (they animate in and out with the mode).
                    val e = earS.value.roundToPx()
                    val w = constraints.maxWidth
                    val p = measurable.measure(Constraints.fixed((w - 2 * e).coerceAtLeast(0), constraints.maxHeight))
                    layout(w, constraints.maxHeight) { p.place(e, 0) }
                },
                targetState = mode,
                transitionSpec = {
                    fadeIn(tween((170 / speed).toInt(), delayMillis = (50 / speed).toInt()))
                        .togetherWith(fadeOut(tween((70 / speed).toInt())))
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "mode",
            ) { m ->
                when (m) {
                    Mode.Compact -> CompactPill(vm, compactH, compactSideDp, if (busy && secondary.isNotEmpty()) 12f else 0f, reveal, st)
                    Mode.Event -> ui.event?.let { EventPill(vm, it, ui.eventText, eventH, eventSideDp, reveal, st) } ?: Spacer(Modifier.height(eventH.dp))
                    Mode.Alert -> ui.alert?.let { Box(Modifier.graphicsLayer { alpha = reveal() }) { AlertBanner(vm, it, ui.queued, st, topInset) } }
                        ?: Spacer(Modifier.height(compactH.dp))
                    Mode.Expanded -> ExpandedPanel(vm, ui, st, tabs, hasUnread = active.any { it.unread }, topInset = topInset)
                }
            }
            }
        }
    }
}

private const val TAB_BAR_DP = 52f
private const val EAR_DP = 14f

/**
 * The island outline: a body (rounded top corners [topR], bottom corners [botR]) plus, in notch mode, a concave
 * "ear" of size [ear] at each top corner so the shape flows into the top edge of the screen. All values animate,
 * which is how the pill morphs into the notch and back.
 */
internal fun islandPath(w: Float, h: Float, ear: Float, topR: Float, botR: Float): Path {
    val e = ear.coerceAtLeast(0f).coerceAtMost(w / 2f)
    val bw = (w - 2f * e).coerceAtLeast(0f)
    val tr = topR.coerceIn(0f, minOf(h / 2f, bw / 2f))
    val br = botR.coerceIn(0f, minOf(h - tr, bw / 2f))
    val path = Path()
    path.addRoundRect(
        RoundRect(
            left = e, top = 0f, right = w - e, bottom = h,
            topLeftCornerRadius = CornerRadius(tr), topRightCornerRadius = CornerRadius(tr),
            bottomRightCornerRadius = CornerRadius(br), bottomLeftCornerRadius = CornerRadius(br),
        )
    )
    if (e > 0.5f) {
        val ee = minOf(e, h)
        val left = Path().apply {
            moveTo(e, 0f); lineTo(e, ee)
            arcTo(Rect(e - 2 * ee, 0f, e, 2 * ee), 0f, -90f, false)
            lineTo(e, 0f); close()
        }
        val right = Path().apply {
            moveTo(w - e, 0f); lineTo(w - e, ee)
            arcTo(Rect(w - e, 0f, w - e + 2 * ee, 2 * ee), 180f, 90f, false)
            lineTo(w - e, 0f); close()
        }
        path.addPath(left)
        path.addPath(right)
    }
    return path
}

/** Clip shape: always a uniform rounded rectangle, which Android clips in hardware (no path clipping). */
internal class IslandShape(private val ear: Float, private val topR: Float, private val botR: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val e = ear.coerceIn(0f, size.width / 2f)
        val bw = (size.width - 2f * e).coerceAtLeast(0f)
        val tr = topR.coerceIn(0f, minOf(size.height / 2f, bw / 2f))
        val br = botR.coerceIn(0f, minOf(size.height - tr, bw / 2f))
        // Flat-topped (notch): the rectangle is extended above the window so its top corners are cut off by the bounds.
        return androidx.compose.ui.graphics.Outline.Rounded(
            RoundRect(left = e, top = -(br - tr), right = size.width - e, bottom = size.height, cornerRadius = CornerRadius(br))
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Compact: a black shape around the camera. When something is active it shows the best-ranked thing,
// plus tiny dots for the others. ONE tap target for the whole pill: it opens what is shown.
// ------------------------------------------------------------------------------------------------

@Composable
private fun CompactPill(vm: IslandViewModel, heightDp: Float, side: Float, dotsDp: Float, reveal: () -> Float, st: ShellSettings) {
    val kind by vm.shownKind.collectAsState()
    val secondary by vm.secondaryKinds.collectAsState()
    val music by vm.music.collectAsState()
    val unread by vm.unread.collectAsState()
    val list by vm.notifications.collectAsState()
    val batt by vm.battery.collectAsState(BatteryInfo())
    val clocks by vm.clockAll.collectAsState()
    val recording by vm.recording.collectAsState()
    val nav by vm.navigation.collectAsState()
    val clients by vm.hotspotClients.collectAsState()

    val shownClock = clocks.firstOrNull { it.isStopwatch == (kind == ActivityKind.Stopwatch) }
    // The one-second clock only runs while a time on screen is really counting.
    val ticking = (shownClock?.running == true && (kind == ActivityKind.Timer || kind == ActivityKind.Stopwatch)) ||
        kind == ActivityKind.Recording
    val now by rememberNow(ticking, 1000)

    Box(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .graphicsLayer { alpha = reveal() }
            // Swipe left / right: switch between active things. Swipe down: open the panel. Tap: open what is shown.
            .islandSwipe(
                st.gestureSensitivity, claim = true,
                onLeft = { vm.cycleActivity(1) }, onRight = { vm.cycleActivity(-1) },
                onDown = { vm.expandShown() },
            )
            .tap { vm.openShown() }
    ) {
        Crossfade(targetState = kind, animationSpec = tween(160), label = "activity") { k ->
            Row(
                Modifier.fillMaxSize().padding(end = dotsDp.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (k) {
                    ActivityKind.Music -> {
                        Region(side) { ArtThumb(music?.art, 22) }
                        Region(side) { Wave(Ink.Pink) }
                    }
                    ActivityKind.Timer, ActivityKind.Stopwatch -> {
                        val t = shownClock
                        if (t != null) {
                            val tint = if (t.paused) Ink.Dim else Ink.Orange
                            Region(side) { Icon24(if (t.isStopwatch) R.drawable.ic_stopwatch else R.drawable.ic_timer, tint, 20.dp) }
                            Region(side) { T(formatClock(t.displayMs(now)), 13.sp, tint, FontWeight.SemiBold, tabular = true) }
                        } else Spacer(Modifier.height(heightDp.dp))
                    }
                    ActivityKind.Notifications -> {
                        val latest = list.firstOrNull { it.unread } ?: list.firstOrNull()
                        Region(side) { if (latest != null) AppIcon(latest.pkg, 22.dp) else Spacer(Modifier.size(22.dp)) }
                        Region(side) { T(unread.toString(), 14.sp, Ink.Blue, FontWeight.SemiBold, tabular = true) }
                    }
                    ActivityKind.Charging -> {
                        // Bolt on one side of the camera, percentage on the other: nothing else, so it always fits.
                        Region(side) { Icon24(R.drawable.ic_bolt, Ink.Green, 18.dp) }
                        Region(side) { T("${batt.percent}%", 14.sp, Ink.Green, FontWeight.SemiBold, tabular = true) }
                    }
                    ActivityKind.Hotspot -> {
                        // Wi-Fi/hotspot icon and the number of connected devices ("3").
                        Region(side) { Icon24(R.drawable.ic_hotspot, Ink.Green, 18.dp) }
                        Region(side) { T(clients?.toString() ?: "On", 14.sp, Ink.Green, FontWeight.SemiBold, tabular = true) }
                    }
                    ActivityKind.Recording -> {
                        val rec = recording
                        if (rec != null) {
                            Region(side) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PulseDot(Ink.Red)
                                    Spacer(Modifier.width(6.dp))
                                    T(formatClock(now - rec.startMs), 14.sp, Ink.Red, FontWeight.SemiBold, tabular = true)
                                }
                            }
                            Region(side) {
                                if (rec.stop != null) {
                                    Box(
                                        Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).tap { vm.stopRecording() },
                                        contentAlignment = Alignment.Center,
                                    ) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Red)) }
                                }
                            }
                        }
                    }
                    ActivityKind.Navigation -> {
                        val n = nav
                        if (n != null) {
                            Region(side) { Icon24(R.drawable.ic_nav, Ink.Blue, 18.dp) }
                            Region(side) { T(n.primary, 13.sp, Color.White, FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp)) }
                        }
                    }
                    null -> Spacer(Modifier.height(heightDp.dp))
                }
            }
        }
        if (secondary.isNotEmpty()) {
            // Tiny dots for the other active things (they keep running; swipe the pill to bring one forward).
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                secondary.forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(it.color)) }
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotAlpha")
    Box(Modifier.size(9.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

// ------------------------------------------------------------------------------------------------
// Status pill: charging / low battery / silent / vibrate / ring / Do Not Disturb / Bluetooth / hotspot.
// Icons first, one short value at most - small enough for the smallest island.
// ------------------------------------------------------------------------------------------------

@Composable
private fun EventPill(vm: IslandViewModel, e: IslandEvent, text: String?, heightDp: Float, side: Float, reveal: () -> Float, st: ShellSettings) {
    val battery by vm.battery.collectAsState(BatteryInfo())
    val clients by vm.hotspotClients.collectAsState()
    var shown by remember(e) { mutableStateOf(false) }
    val rot = remember(e) { Animatable(0f) }
    LaunchedEffect(e) {
        delay(110)
        shown = true
        if (e == IslandEvent.Silent || e == IslandEvent.Vibrate || e == IslandEvent.Ring) {
            // little "ring" wiggle, like the iPhone mute switch
            repeat(2) {
                rot.animateTo(16f, tween(70))
                rot.animateTo(-16f, tween(110))
            }
            rot.animateTo(0f, tween(80))
        }
    }
    Box(
        Modifier.fillMaxWidth().height(heightDp.dp)
            .graphicsLayer { alpha = reveal() }
            .islandSwipe(st.gestureSensitivity, claim = true, onDown = { vm.expandShown() })
            .tap { vm.expandShown() }
    ) {
        Crossfade(targetState = e, animationSpec = tween(140), label = "event") { ev ->
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (ev) {
                    // [bolt] .... 75%
                    IslandEvent.Charging -> {
                        Region(side) { PopIcon(shown, R.drawable.ic_bolt, Ink.Green) }
                        Region(side) { FadeText(shown, "${battery.percent}%", Ink.Green) }
                    }
                    // [battery] .... 20%
                    IslandEvent.LowBattery -> {
                        Region(side) {
                            AnimatedVisibility(shown, enter = scaleIn(spring(0.5f, Spring.StiffnessMedium)) + fadeIn()) {
                                BatteryGlyph(battery.percent / 100f, Ink.Red)
                            }
                        }
                        Region(side) { FadeText(shown, "${battery.percent}%", Ink.Red) }
                    }
                    // [red bell] - icon only
                    IslandEvent.Silent, IslandEvent.Vibrate, IslandEvent.Ring -> {
                        val (icon, bg) = when (ev) {
                            IslandEvent.Silent -> R.drawable.ic_bell_off to Ink.Red
                            IslandEvent.Vibrate -> R.drawable.ic_vibrate to Ink.Orange
                            else -> R.drawable.ic_bell to Ink.Card2
                        }
                        Region(side) {
                            AnimatedVisibility(shown, enter = scaleIn(spring(0.5f, Spring.StiffnessMedium)) + fadeIn()) {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                                    Icon24(icon, Color.White, 14.dp, Modifier.graphicsLayer { rotationZ = rot.value })
                                }
                            }
                        }
                    }
                    IslandEvent.Dnd -> Region(side) { PopIcon(shown, R.drawable.ic_moon, Ink.Purple) }
                    IslandEvent.Bluetooth -> {
                        Region(side) { PopIcon(shown, R.drawable.ic_bluetooth, Ink.Blue) }
                        Region(side) { text?.takeIf { it.isNotBlank() }?.let { FadeText(shown, it.take(8), Ink.Blue) } }
                    }
                    // Hotspot icon and the number of connected devices.
                    IslandEvent.Hotspot -> {
                        Region(side) { PopIcon(shown, R.drawable.ic_hotspot, Ink.Green) }
                        Region(side) { clients?.let { FadeText(shown, it.toString(), Ink.Green) } }
                    }
                }
            }
        }
    }
}

/** One side of a pill: a fixed-width area (one on each side of the camera) whose content is centred inside it. */
@Composable
private fun Region(widthDp: Float, content: @Composable () -> Unit) {
    Box(Modifier.width(widthDp.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PopIcon(shown: Boolean, @androidx.annotation.DrawableRes icon: Int, tint: Color) {
    AnimatedVisibility(shown, enter = scaleIn(spring(0.5f, Spring.StiffnessMedium)) + fadeIn()) {
        Icon24(icon, tint, 18.dp)
    }
}

@Composable
private fun FadeText(shown: Boolean, text: String, tint: Color) {
    AnimatedVisibility(shown, enter = fadeIn(tween(200))) {
        T(text, 13.sp, tint, FontWeight.SemiBold, tabular = true)
    }
}

@Composable
private fun BatteryGlyph(fraction: Float, color: Color) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fraction) { target = fraction.coerceIn(0f, 1f) }
    val f by animateFloatAsState(target, tween(800), label = "batt")
    Canvas(Modifier.size(width = 22.dp, height = 11.dp)) {
        val nub = 2.dp.toPx()
        val w = size.width - nub
        val line = Color.White.copy(alpha = 0.38f)
        drawRoundRect(line, Offset.Zero, Size(w, size.height), CornerRadius(4.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        val pad = 2.dp.toPx()
        drawRoundRect(color, Offset(pad, pad), Size((w - 2 * pad) * f, size.height - 2 * pad), CornerRadius(2.dp.toPx()))
        drawRoundRect(line, Offset(w + 0.5.dp.toPx(), size.height * 0.3f), Size(nub - 0.5.dp.toPx(), size.height * 0.4f), CornerRadius(1.dp.toPx()))
    }
}

@Composable
internal fun ArtThumb(art: android.graphics.Bitmap?, size: Int) {
    val shape = RoundedCornerShape((size * 0.27f).dp)
    val image = remember(art) { art?.asImageBitmap() }
    if (image != null) {
        Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(shape))
    } else {
        Box(
            Modifier.size(size.dp).clip(shape)
                .background(Brush.linearGradient(listOf(Ink.Pink, Ink.Orange))),
            contentAlignment = Alignment.Center,
        ) { Icon24(R.drawable.ic_note, Color.White, (size * 0.6f).dp) }
    }
}

/** Equaliser bars; the phase is read in the draw phase so it never recomposes. */
@Composable
internal fun Wave(color: Color) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "phase"
    )
    Canvas(Modifier.size(18.dp)) {
        val bw = 3.dp.toPx()
        val gap = 2.dp.toPx()
        for (i in 0 until 4) {
            val p = (phase + i * 0.25f) % 1f
            val h = (6.dp.toPx() + 12.dp.toPx() * p).coerceAtMost(size.height)
            drawRoundRect(
                color, Offset(i * (bw + gap), (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2)
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Alert banner: shown when a real notification arrives, then returns to the compact state.
// ------------------------------------------------------------------------------------------------

@Composable
private fun AlertBanner(vm: IslandViewModel, n: ShellNotification, queued: Int, st: ShellSettings, topInset: Float) {
    var shown by remember(n.key) { mutableStateOf(false) }
    LaunchedEffect(n.key) {
        delay(110)
        shown = true
    }
    val showText = !st.hidePreviews && !n.hidden
    val headline = when {
        n.hidden -> n.appName
        !n.sender.isNullOrBlank() && n.title.isNotBlank() -> "${n.sender} · ${n.title}"
        !n.sender.isNullOrBlank() -> n.sender
        n.title.isNotBlank() -> n.title
        else -> n.appName
    }
    val preview = when {
        n.hidden -> "Sensitive content hidden"
        !showText -> "New notification"
        else -> n.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height((topInset + 78f).dp)
            .islandSwipe(
                st.gestureSensitivity, claim = true,
                onUp = { vm.dismissAlert() }, onLeft = { vm.dismissAlert() }, onRight = { vm.dismissAlert() },
                onDown = { vm.expand() },
            )
            .tap { vm.open(n); vm.dismissAlert() }
            .padding(start = 14.dp, end = 14.dp, top = topInset.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(shown, enter = scaleIn(spring(0.6f, Spring.StiffnessMedium)) + fadeIn()) {
            AppIcon(n.pkg, 40.dp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(n.appName, 11.sp, Ink.Dim, FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                if (queued > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(CircleShape).background(Ink.Card2).padding(horizontal = 6.dp, vertical = 1.dp)
                    ) { T("+$queued", 10.sp, Color.White, FontWeight.SemiBold) }
                }
            }
            AnimatedVisibility(shown, enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 }) {
                Column {
                    T(headline, 14.sp, Color.White, FontWeight.SemiBold)
                    if (preview.isNotBlank()) T(preview, 12.5.sp, Color(0xFFD1D1D6))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.Blue))
    }
}

// ------------------------------------------------------------------------------------------------
// Expanded panel: swipe left/right = next/previous feature, swipe up = collapse.
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun ExpandedPanel(
    vm: IslandViewModel,
    ui: IslandViewModel.Ui,
    st: ShellSettings,
    tabs: List<IslandTab>,
    hasUnread: Boolean,
    topInset: Float,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Watches the whole panel (also the strip under the camera). Lists, notification cards and the
            // app row keep their own scroll / swipe: when they use a drag this detector stays out of it.
            .islandSwipe(
                st.gestureSensitivity, claim = false,
                onLeft = { vm.next() }, onRight = { vm.previous() }, onUp = { vm.collapse() },
            )
            .padding(top = topInset.dp) // keep everything below the camera hole
    ) {
        AnimatedContent(
            targetState = ui.tab,
            transitionSpec = {
                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(280)) { it / 5 * dir } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 5 * dir } + fadeOut(tween(120)))
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "tab",
        ) { tab ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(tab.contentHeight * st.panelScale.coerceIn(0.7f, 1.5f))
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp)
            ) {
                when (tab) {
                    IslandTab.Notifications -> NotificationsTab(vm, st)
                    IslandTab.Music -> MusicTab(vm)
                    IslandTab.Apps -> AppsTab(vm)
                    IslandTab.Clipboard -> ClipboardTab(vm)
                    IslandTab.Live -> LiveTab(vm)
                }
            }
        }
        TabBar(tabs, ui.tab, hasUnread) { vm.selectTab(it) }
    }
}

@Composable
private fun TabBar(tabs: List<IslandTab>, selected: IslandTab, hasUnread: Boolean, onSelect: (IslandTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val on = tab == selected
            Box(
                Modifier
                    .size(width = 38.dp, height = 34.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) tab.color.copy(alpha = 0.24f) else Color.Transparent)
                    .tap { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Icon24(tab.icon, if (on) tab.color else Ink.Dim, 18.dp)
                if (tab == IslandTab.Notifications && hasUnread && !on) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 7.dp)
                            .size(8.dp).clip(CircleShape).background(Ink.Red)
                    )
                }
            }
        }
    }
}
