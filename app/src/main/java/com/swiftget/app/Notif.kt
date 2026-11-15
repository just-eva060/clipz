package com.swiftget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import java.util.Locale

const val EXTRA_RESUME = "swiftget.resume"
const val ACTION_PAUSE_ALL = "swiftget.pause_all"

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext == "ts") return "video/mp2t"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

object Notif {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    const val ID_PROGRESS = 1001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(ctx: Context, resume: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_RESUME, resume)
        return PendingIntent.getActivity(ctx, if (resume) 2 else 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(ctx: Context, list: List<DownloadUi>) = run {
        ensureChannels(ctx)
        val active = list.filter { it.active }
        val speed = active.filter { it.status == Status.DOWNLOADING }.sumOf { it.speed }
        val total = active.sumOf { if (it.total > 0) it.total else 0L }
        val done = active.sumOf { if (it.total > 0) it.downloaded else 0L }
        val waiting = active.isNotEmpty() && active.all { it.status == Status.WAITING_NETWORK }
        val pauseAll = PendingIntent.getService(
            ctx, 1, Intent(ctx, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (active.isEmpty()) "Clipz" else "Clipz · ${active.size} downloading")
            .setContentText(
                when {
                    waiting -> "Waiting for network. Will resume automatically."
                    active.isEmpty() -> "Idle"
                    else -> "${fmtBytes(speed)}/s" + if (total > 0) " · ${done * 100 / total}%" else ""
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, false))
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .addAction(0, "Pause all", pauseAll)
            .build()
    }

    fun completed(ctx: Context, name: String, uri: String) {
        ensureChannels(ctx)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), mimeOf(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, name.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Download complete")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(name.hashCode(), n) }
    }

    fun tapToResume(ctx: Context, text: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Clipz paused")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, true))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(1002, n) }
    }
}

fun fmtBytes(b: Long): String {
    if (b < 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fmtEta(seconds: Long): String = when {
    seconds < 0 -> "--"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
