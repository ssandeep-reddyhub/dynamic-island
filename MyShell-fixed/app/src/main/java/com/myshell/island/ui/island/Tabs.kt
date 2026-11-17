package com.myshell.island.ui.island

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myshell.island.R
import com.myshell.island.data.AppInfo
import com.myshell.island.data.BatteryInfo
import com.myshell.island.data.MusicState
import com.myshell.island.ui.theme.Ink
import com.myshell.island.util.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Subtle = Color(0xFFD1D1D6)

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    tint: Color,
    title: String,
    hint: String,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon24(icon, tint, 28.dp)
        Spacer(Modifier.height(8.dp))
        T(title, 15.sp, Color.White, FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        T(hint, 12.sp, Ink.Dim, maxLines = 3, center = true)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            PillButton(action.first, Ink.PriBg, Ink.Blue, height = 30.dp, textSize = 12.sp, onClick = action.second)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Music - real MediaSession data
// ---------------------------------------------------------------------------------------------

@Suppress("UNUSED_PARAMETER")
private fun livePosition(s: MusicState, tick: Long): Long {
    val p = if (s.playing) s.positionMs + ((SystemClock.elapsedRealtime() - s.updateTime) * s.speed).toLong() else s.positionMs
    return if (s.durationMs > 0) p.coerceIn(0L, s.durationMs) else p.coerceAtLeast(0L)
}

@Composable
fun MusicTab(vm: IslandViewModel) {
    val m by vm.music.collectAsState()
    val access by vm.mediaAccess.collectAsState()
    val s = m
    val tick by rememberNow(s?.playing == true, 500)

    if (s == null) {
        EmptyState(
            icon = R.drawable.ic_music, tint = Ink.Pink, title = "Nothing playing",
            hint = if (access) "Start playback in Spotify, YouTube Music or any media app."
            else "Allow notification access so MyShell can see your media sessions.",
            action = if (access) null else "Enable access" to { vm.openNotificationAccess() },
        )
        return
    }

    val pos = livePosition(s, tick)
    val frac = if (s.durationMs > 0) pos.toFloat() / s.durationMs else 0f

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        // The whole header (art, title, waveform) is one tap target: it opens the music app.
        Row(Modifier.fillMaxWidth().tap { vm.openPlayer() }, verticalAlignment = Alignment.CenterVertically) {
            ArtThumb(s.art, 60)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(s.title, 16.sp, Color.White, FontWeight.SemiBold)
                if (s.artist.isNotBlank()) T(s.artist, 13.sp, Ink.Dim)
            }
            if (s.playing) {
                Spacer(Modifier.width(8.dp))
                Wave(Ink.Pink)
            }
        }
        Column {
            Box(
                Modifier.fillMaxWidth().height(16.dp).pointerInput(s.durationMs) {
                    detectTapGestures { o ->
                        if (s.durationMs > 0) vm.seek((o.x / size.width * s.durationMs).toLong())
                    }
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)))
                Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Color.White))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                T(formatClock(pos), 10.sp, Ink.Dim, tabular = true)
                if (s.durationMs > 0) T("-" + formatClock(s.durationMs - pos), 10.sp, Ink.Dim, tabular = true)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Plain white transport icons, like the reference player.
            CircleIconButton(R.drawable.ic_prev, { vm.previousTrack() }, bg = Color.Transparent, size = 44.dp, iconSize = 26.dp, enabled = s.canPrev)
            CircleIconButton(
                if (s.playing) R.drawable.ic_pause else R.drawable.ic_play, { vm.playPause() },
                bg = Color.Transparent, tint = Color.White, size = 48.dp, iconSize = 30.dp,
            )
            CircleIconButton(R.drawable.ic_next, { vm.nextTrack() }, bg = Color.Transparent, size = 44.dp, iconSize = 26.dp, enabled = s.canNext)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Apps - pinned shortcuts (custom order), recents, picker
// ---------------------------------------------------------------------------------------------

@Composable
fun AppsTab(vm: IslandViewModel) {
    val pinned by vm.pinned.collectAsState()
    val recents by vm.recents.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }

    if (picking) {
        AppPicker(vm, pinned) { picking = false }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Shortcuts", 12.sp, Ink.Dim, FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            CircleIconButton(R.drawable.ic_plus, { picking = true }, size = 28.dp, iconSize = 14.dp)
        }
        if (pinned.isEmpty()) {
            T("Tap + to add your favourite apps.", 12.sp, Ink.Dim)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pinned, key = { it }) { pkg ->
                    AppCell(vm, pkg, editing == pkg, onLong = { editing = pkg }, onDone = { editing = null })
                }
            }
        }
        val recent = recents.filter { it !in pinned }
        if (recent.isNotEmpty()) {
            T("Recent", 12.sp, Ink.Dim, FontWeight.Medium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent, key = { it }) { pkg -> AppIcon(pkg, 34.dp, Modifier.tap { vm.launch(pkg) }) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(vm: IslandViewModel, pkg: String, editing: Boolean, onLong: () -> Unit, onDone: () -> Unit) {
    val label = remember(pkg) { vm.icons.label(pkg) }
    Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(
            pkg, 46.dp,
            Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (editing) onDone() else vm.launch(pkg) },
                onLongClick = onLong,
            ),
        )
        Spacer(Modifier.height(4.dp))
        if (editing) {
            // Long-press: reorder or remove, without popup windows (which don't suit an overlay).
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, { vm.move(pkg, -1) }, size = 18.dp, iconSize = 10.dp)
                CircleIconButton(R.drawable.ic_close, { vm.unpin(pkg) }, bg = Ink.Red.copy(alpha = 0.25f), tint = Ink.Red, size = 18.dp, iconSize = 9.dp)
                CircleIconButton(R.drawable.ic_chevron_right, { vm.move(pkg, 1) }, size = 18.dp, iconSize = 10.dp)
            }
        } else {
            T(label, 10.sp, Subtle)
        }
    }
}

@Composable
private fun AppPicker(vm: IslandViewModel, pinned: List<String>, onDone: () -> Unit) {
    val apps by produceState(emptyList<AppInfo>()) { value = vm.allApps() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Choose apps", 13.sp, Color.White, FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            PillButton("Done", Ink.PriBg, Ink.Blue, height = 28.dp, textSize = 12.sp) { onDone() }
        }
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            GridCells.Fixed(5), Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(apps, key = { it.pkg }) { a ->
                Column(Modifier.tap { vm.togglePin(a.pkg) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        AppIcon(a.pkg, 40.dp)
                        if (a.pkg in pinned) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(Ink.Green),
                                contentAlignment = Alignment.Center,
                            ) { Icon24(R.drawable.ic_check, Color.Black, 11.dp) }
                        }
                    }
                    T(a.label, 9.sp, Ink.Dim)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Clipboard history
// ---------------------------------------------------------------------------------------------

@Composable
fun ClipboardTab(vm: IslandViewModel) {
    val clips by vm.clips.collectAsState()
    var copied by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1200)
            copied = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(if (copied != null) "Copied" else "Clipboard", 12.sp, if (copied != null) Ink.Green else Ink.Dim, FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            PillButton("Capture", Ink.PriBg, Ink.Blue, height = 26.dp, textSize = 11.sp) { vm.captureClipboard() }
            Spacer(Modifier.width(6.dp))
            PillButton("Clear", Ink.Card2, Color.White, height = 26.dp, textSize = 11.sp, enabled = clips.any { !it.pinned }) { vm.clearClips() }
        }
        Spacer(Modifier.height(8.dp))
        if (clips.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_clipboard, tint = Ink.Cyan, title = "No clips yet",
                hint = "Copy some text, then tap Capture. Android only lets an app read the clipboard while it is in front.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(clips, key = { it.id }) { clip ->
                    Row(
                        Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Card)
                            .tap { vm.copyAgain(clip.text); copied = clip.id }
                            .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(clip.text, 13.sp, Color.White, maxLines = 2, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        CircleIconButton(
                            R.drawable.ic_pin, { vm.togglePinClip(clip.id) },
                            bg = if (clip.pinned) Ink.Orange.copy(alpha = 0.25f) else Ink.Card2,
                            tint = if (clip.pinned) Ink.Orange else Ink.Dim, size = 28.dp, iconSize = 14.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        CircleIconButton(R.drawable.ic_trash, { vm.deleteClip(clip.id) }, tint = Ink.Dim, size = 28.dp, iconSize = 14.dp)
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Active - every running activity in one short list (Smart Event Management)
// ---------------------------------------------------------------------------------------------

@Composable
fun LiveTab(vm: IslandViewModel) {
    val kinds by vm.activeKinds.collectAsState()
    val shown by vm.shownKind.collectAsState()
    val music by vm.music.collectAsState()
    val unread by vm.unread.collectAsState()
    val clocks by vm.clockAll.collectAsState()
    val batt by vm.battery.collectAsState(BatteryInfo())
    val clients by vm.hotspotClients.collectAsState()
    val rec by vm.recording.collectAsState()
    val nav by vm.navigation.collectAsState()
    val ticking = kinds.any { it == ActivityKind.Timer || it == ActivityKind.Stopwatch || it == ActivityKind.Recording }
    val now by rememberNow(ticking, 1000)

    fun info(k: ActivityKind): String = when (k) {
        ActivityKind.Music -> music?.title.orEmpty()
        ActivityKind.Timer, ActivityKind.Stopwatch -> clocks.firstOrNull { it.isStopwatch == (k == ActivityKind.Stopwatch) }
            ?.let { formatClock(it.displayMs(now)) + if (it.paused) " paused" else "" }.orEmpty()
        ActivityKind.Notifications -> "$unread new"
        ActivityKind.Charging -> "${batt.percent}%"
        ActivityKind.Hotspot -> clients?.let { if (it == 1) "1 device" else "$it devices" } ?: "On"
        ActivityKind.Recording -> rec?.let { formatClock(now - it.startMs) }.orEmpty()
        ActivityKind.Navigation -> nav?.primary.orEmpty()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("Active now - tap to bring one forward", 12.sp, Ink.Dim, FontWeight.Medium)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(kinds, key = { it.name }) { k ->
                Row(
                    Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (k == shown) Ink.Card2 else Ink.Card)
                        .tap {
                            vm.pinKind(k)
                            if (k != ActivityKind.Charging && k != ActivityKind.Hotspot && k != ActivityKind.Recording) vm.openActivity(k)
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon24(k.icon, k.color, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    T(k.label, 14.sp, Color.White, FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    T(info(k), 12.sp, Ink.Dim, modifier = Modifier.weight(1f), tabular = true)
                }
            }
        }
    }
}
