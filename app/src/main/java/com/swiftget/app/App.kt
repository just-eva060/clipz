package com.swiftget.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The crash-report screen runs in its own process; it must not start the download engine.
        if (Application.getProcessName() != packageName) return
        installCrashReporter()
        Torrents.init(this)
        Rights.init(this)
        Clips.init(this)
        Trends.init(this)
        Trends.schedule(this)
        Downloader.init(this)
        Vault.init(this)
    }

    private fun installCrashReporter() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val info = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT})\n\n$sw"
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("trace", info.take(20_000)),
                )
            } catch (ignored: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}

/** Boot and app-update: creating the process runs Downloader.init, which resumes unfinished downloads. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Downloader.init(context.applicationContext)
    }
}

/**
 * Quick Settings tile. Lit = downloads running. Tap while lit = pause everything;
 * tap while dim = resume paused downloads, or open the app if there is nothing to resume.
 */
class QuickTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        Downloader.init(applicationContext)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch { Downloader.ui.collect { refresh(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val list = Downloader.ui.value
        when {
            list.any { it.active } -> Downloader.pauseAll()
            list.any { it.status == Status.PAUSED || it.status == Status.FAILED } -> Downloader.resumeAll()
            else -> openApp()
        }
        refresh(Downloader.ui.value)
    }

    private fun refresh(list: List<DownloadUi>) {
        val tile = qsTile ?: return
        val active = list.count { it.active }
        tile.label = "Clipz"
        tile.state = if (active > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            active > 0 -> "$active downloading"
            list.any { it.status == Status.PAUSED } -> "Paused"
            else -> "Idle"
        }
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            startActivityAndCollapse(i)
        }
    }
}
