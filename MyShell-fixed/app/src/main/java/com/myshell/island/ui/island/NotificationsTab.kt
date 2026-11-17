package com.myshell.island.ui.island

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.derivedStateOf
import kotlin.math.abs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myshell.island.R
import com.myshell.island.data.ShellNotification
import com.myshell.island.data.ShellSettings
import com.myshell.island.ui.theme.Ink
import com.myshell.island.util.Permissions
import com.myshell.island.util.relativeTime
import kotlinx.coroutines.delay

private fun ShellNotification.headline(): String = when {
    hidden -> appName
    !sender.isNullOrBlank() && title.isNotBlank() -> "$sender · $title"
    !sender.isNullOrBlank() -> sender
    title.isNotBlank() -> title
    else -> appName
}

private fun ShellNotification.preview(hidePreviews: Boolean): String = when {
    hidden -> "Sensitive content hidden"
    hidePreviews -> "New notification"
    else -> text
}

/** Notifications grouped by app, with open / reply / source actions / dismiss. */
@Composable
fun NotificationsTab(vm: IslandViewModel, st: ShellSettings) {
    val list by vm.notifications.collectAsState()
    val ctx = LocalContext.current.applicationContext
    val overlay = LocalOverlay.current

    var selected by remember { mutableStateOf<String?>(null) }
    var replying by remember { mutableStateOf<String?>(null) }
    val openGroups = remember { mutableStateListOf<String>() }

    // Never leave the window focusable (keyboard-capable) behind when this tab goes away.
    DisposableEffect(Unit) { onDispose { overlay.setFocusable(false) } }

    // While the user is scrolling, new / changed notifications wait (the list would otherwise reshuffle and
    // re-compose under the finger). Removals by swipe are not scrolling, so they apply at once.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolling = listState.isScrollInProgress
    var shownList by remember { mutableStateOf(list) }
    LaunchedEffect(list, scrolling) { if (!scrolling) shownList = list }
    val groups = remember(shownList) { shownList.groupBy { it.pkg }.entries.toList() }
    val unread = list.count { it.unread }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            T(
                when {
                    unread > 0 -> "$unread new"
                    list.isEmpty() -> "Notifications"
                    else -> "${list.size} notifications"
                },
                12.sp, if (unread > 0) Ink.Blue else Ink.Dim, FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            if (list.isNotEmpty()) {
                PillButton("Clear all", Ink.Card2, Color.White, height = 24.dp, textSize = 11.sp) { vm.clearAll() }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (list.isEmpty()) {
            val access = remember { Permissions.notificationAccessGranted(ctx) }
            EmptyState(
                icon = R.drawable.ic_bell,
                tint = Ink.Blue,
                title = "All caught up",
                hint = if (access) "New notifications from your apps appear here." else "Allow notification access to see your real notifications.",
                action = if (access) null else "Enable access" to { vm.openNotificationAccess() },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (pkg, groupItems) ->
                    item(key = "h_$pkg", contentType = "header") {
                        GroupHeader(
                            modifier = Modifier,
                            pkg = pkg, name = groupItems.first().appName, count = groupItems.size,
                            onClear = { vm.dismissPackage(pkg) },
                        )
                    }
                    val open = pkg in openGroups
                    items(if (open) groupItems else groupItems.take(2), key = { it.key }, contentType = { "card" }) { n ->
                        SwipeToDelete(
                            modifier = Modifier.animateItem(),
                            onDismiss = { vm.dismiss(n.key) },
                        ) {
                        NotificationCard(
                            modifier = Modifier,
                            n = n,
                            hidePreviews = st.hidePreviews,
                            selected = selected == n.key,
                            replying = replying == n.key,
                            onToggle = {
                                selected = if (selected == n.key) null else n.key
                                if (replying != null) { replying = null; overlay.setFocusable(false) }
                            },
                            onOpen = { vm.open(n) },
                            onDismiss = { vm.dismiss(n.key) },
                            onAction = { vm.runAction(it) },
                            onReplyToggle = {
                                if (replying == n.key) { replying = null; overlay.setFocusable(false) }
                                else { overlay.setFocusable(true); replying = n.key }
                            },
                            onSend = { text ->
                                vm.reply(n, text)
                                replying = null
                                overlay.setFocusable(false)
                            },
                        )
                        }
                    }
                    if (groupItems.size > 2) {
                        item(key = "m_$pkg", contentType = "more") {
                            Box(
                                Modifier.fillMaxWidth().tap {
                                    if (open) openGroups.remove(pkg) else openGroups.add(pkg)
                                }.padding(vertical = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                T(if (open) "Show less" else "Show ${groupItems.size - 2} more", 11.sp, Ink.Blue, FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(modifier: Modifier, pkg: String, name: String, count: Int, onClear: () -> Unit) {
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, 18.dp)
        Spacer(Modifier.width(7.dp))
        T(name, 11.5.sp, Ink.Dim, FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
        if (count > 1) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.clip(CircleShape).background(Ink.Card2).padding(horizontal = 6.dp, vertical = 1.dp)) {
                T(count.toString(), 10.sp, Color.White, FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Swipe a notification sideways to clear it. A small round delete chip fades in behind the card and turns red
 * once the swipe is far enough; letting go before that springs the card back, so nothing is deleted by accident.
 * Uses the platform draggable: it follows the finger and never fights the list's vertical scrolling.
 */
@Composable
private fun SwipeToDelete(modifier: Modifier, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { dx -> offset += dx }

    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }) {
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(offset) / width).coerceIn(0f, 1f) * 0.5f }
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val dir = if (offset >= 0f) 1f else -1f
                        val far = abs(offset) >= width * CLEAR_FRACTION
                        // A quick flick also counts, but only after a real swipe (never from a tiny nudge).
                        val flung = abs(velocity) > 1400f && velocity * dir > 0f && abs(offset) > width * 0.2f
                        val remove = far || flung
                        val target = if (remove) dir * width else 0f
                        scope.launch {
                            animate(offset, target, initialVelocity = velocity, animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f)) { v, _ -> offset = v }
                            if (remove) onDismiss()
                        }
                    },
                )
        ) { content() }
    }
}

private const val CLEAR_FRACTION = 0.4f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationCard(
    modifier: Modifier,
    n: ShellNotification,
    hidePreviews: Boolean,
    selected: Boolean,
    replying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onAction: (com.myshell.island.data.NotifAction) -> Unit,
    onReplyToggle: () -> Unit,
    onSend: (String) -> Unit,
) {
    val preview = n.preview(hidePreviews)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Card)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,          // tap = open the notification
                onLongClick = onToggle,    // long press = actions / reply
            )
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(n.pkg, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(n.headline(), 13.5.sp, Color.White, FontWeight.SemiBold, maxLines = if (selected) 3 else 1)
                if (preview.isNotBlank()) {
                    T(preview, 12.5.sp, Color(0xFFAEAEB2), maxLines = if (selected) 8 else 2)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                T(relativeTime(n.postedAt), 11.sp, Ink.Dim)
                if (n.unread) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.Blue))
                }
            }
        }
        AnimatedVisibility(selected) {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton("Open", Ink.PriBg, Ink.Blue, height = 28.dp, textSize = 12.sp) { onOpen() }
                    // Only offered when the source app actually provides a reply action.
                    if (n.reply != null) {
                        PillButton(if (replying) "Cancel" else "Reply", Ink.GoBg, Ink.Green, height = 28.dp, textSize = 12.sp) { onReplyToggle() }
                    }
                    n.actions.forEach { a ->
                        PillButton(a.title, Ink.Card2, Color.White, height = 28.dp, textSize = 12.sp) { onAction(a) }
                    }
                    PillButton("Dismiss", Ink.Card2, Ink.Red, height = 28.dp, textSize = 12.sp) { onDismiss() }
                }
                if (replying) {
                    Spacer(Modifier.height(8.dp))
                    ReplyField(hint = "Reply to ${n.sender ?: n.title.ifBlank { n.appName }}", onSend = onSend)
                }
            }
        }
    }
}

@Composable
private fun ReplyField(hint: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The overlay window becomes focusable a moment after we ask, so wait before requesting focus.
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    fun send() {
        val t = text.trim()
        if (t.isNotEmpty()) onSend(t)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.Card2)
            .padding(start = 12.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) T(hint, 13.sp, Ink.Dim)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(Ink.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        CircleIconButton(R.drawable.ic_send, onClick = { send() }, bg = Ink.Blue, size = 28.dp, iconSize = 14.dp)
    }
}
