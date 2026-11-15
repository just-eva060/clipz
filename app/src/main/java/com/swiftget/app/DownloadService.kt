package com.swiftget.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.app.NotificationManager

/** Keeps the process alive and the CPU/Wi-Fi awake while anything is downloading. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var checker: Job? = null
    private var wake: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        Notif.ensureChannels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clipz:download").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Clipz:wifi").apply {
            setReferenceCounted(false); acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloader.init(applicationContext)
        if (intent?.action == ACTION_PAUSE_ALL) Downloader.pauseAll()
        ServiceCompat.startForeground(
            this, Notif.ID_PROGRESS, Notif.progress(this, Downloader.ui.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (checker == null) {
            checker = scope.launch {
                while (true) {
                    delay(30_000)
                    Cool.check(applicationContext)
                }
            }
        }
        if (watcher == null) {
            val nm = getSystemService(NotificationManager::class.java)
            watcher = scope.launch {
                Downloader.ui.collectLatest { list ->
                    if (list.none { it.active }) {
                        stopSelf()
                    } else {
                        nm.notify(Notif.ID_PROGRESS, Notif.progress(this@DownloadService, list))
                    }
                }
            }
        }
        return START_STICKY
    }

    /** Android 15 caps data-sync foreground services at 6 hours per day. Pause cleanly and say so. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloader.pauseAll()
        Notif.tapToResume(this, "Android's 6-hour background limit was reached. Tap to continue.")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
