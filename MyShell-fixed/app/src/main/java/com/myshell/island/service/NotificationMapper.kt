package com.myshell.island.service

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.myshell.island.data.NotifAction
import com.myshell.island.data.ShellNotification

/** Turns a raw [StatusBarNotification] into what the island shows, dropping noise. */
object NotificationMapper {

    fun map(ctx: Context, sbn: StatusBarNotification): ShellNotification? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == ctx.packageName) return null

        val isCall = n.category == Notification.CATEGORY_CALL
        if (isCall) return null // phone calls have their own call UI in the island
        val f = n.flags
        if (f and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (!isCall && (f and Notification.FLAG_ONGOING_EVENT != 0 || f and Notification.FLAG_FOREGROUND_SERVICE != 0)) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null
        // YouTube playback (media controls / now-playing) is never shown in the island.
        if (sbn.packageName == "com.google.android.youtube" && (f and Notification.FLAG_ONGOING_EVENT != 0 || n.extras.containsKey("android.mediaSession"))) return null

        val extras = n.extras
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val last = style?.messages?.lastOrNull()

        var title = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        var text = (last?.text ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        var sender = last?.person?.name?.toString()?.takeIf { it.isNotBlank() && it != title }
        if (title.isBlank() && text.isBlank()) return null

        // Respect the source's privacy marking: secret always, private while the phone is locked.
        val locked = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val hidden = n.visibility == Notification.VISIBILITY_SECRET ||
            (locked && n.visibility == Notification.VISIBILITY_PRIVATE)
        if (hidden) {
            title = ""
            text = ""
            sender = null
        }

        val actions = n.actions.orEmpty().mapNotNull { a ->
            val label = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NotifAction(label, a.actionIntent, a.remoteInputs?.firstOrNull { it.allowFreeFormInput })
        }

        return ShellNotification(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appLabel(ctx, sbn.packageName),
            title = title,
            sender = sender,
            text = text,
            postedAt = sbn.postTime,
            hidden = hidden,
            contentIntent = n.contentIntent,
            // Not every app offers replies or actions - only what the source provides is shown.
            actions = actions.filter { it.remoteInput == null }.take(2),
            reply = actions.firstOrNull { it.remoteInput != null },
        )
    }

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** Fires the PendingIntents supplied by the source app. */
object NotificationActions {

    private fun options(): Bundle? =
        if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null

    private fun send(ctx: Context, pi: PendingIntent?, fill: Intent? = null): Boolean {
        if (pi == null) return false
        return try {
            pi.send(ctx, 0, fill, null, null, null, options())
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    fun open(ctx: Context, n: ShellNotification): Boolean {
        if (send(ctx, n.contentIntent)) return true
        val launch = ctx.packageManager.getLaunchIntentForPackage(n.pkg) ?: return false
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun run(ctx: Context, action: com.myshell.island.data.NotifAction): Boolean = send(ctx, action.intent)

    /** Inline reply through the source app's own RemoteInput - only called when it offered one. */
    fun reply(ctx: Context, n: ShellNotification, text: String): Boolean {
        val action = n.reply ?: return false
        val input = action.remoteInput ?: return false
        val fill = Intent()
        val results = Bundle().apply { putCharSequence(input.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(input), fill, results)
        return send(ctx, action.intent, fill)
    }
}
