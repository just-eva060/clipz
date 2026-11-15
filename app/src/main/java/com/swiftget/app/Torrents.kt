package com.swiftget.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A BitTorrent client (libtorrent4j): magnet links and .torrent files. Stops seeding when a download finishes. */
object Torrents {
    data class T(
        val id: String, val name: String, val progress: Float, val rate: Long, val peers: Int,
        val state: String, val paused: Boolean, val done: Boolean, val size: Long,
    )

    val list = MutableStateFlow<List<T>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    private var app: Context? = null
    private var sm: SessionManager? = null
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val finished = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ticker: Job? = null

    private fun prefs() = app!!.getSharedPreferences("torrents", Context.MODE_PRIVATE)
    private fun dir(): File = (app!!.getExternalFilesDir("torrents") ?: File(app!!.filesDir, "torrents")).also { it.mkdirs() }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val saved = prefs().all.filterKeys { it.startsWith("src_") }.values.mapNotNull { it as? String }
        if (saved.isNotEmpty()) scope.launch { saved.forEach { start(it) } }
    }

    /** Info-hash from a magnet link (40 hex or 32 base32 characters), lowercase hex, or null. */
    fun hashOf(magnet: String): String? {
        val m = Regex("btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1) ?: return null
        if (m.length == 40) return m.lowercase()
        if (m.length != 32) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0L
        var count = 0
        val sb = StringBuilder()
        for (c in m.uppercase()) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            bits = (bits shl 5) or v.toLong()
            count += 5
            if (count >= 8) {
                count -= 8
                sb.append("%02x".format(((bits shr count) and 0xFF).toInt()))
            }
        }
        return sb.toString()
    }

    private fun ensure(): SessionManager? {
        sm?.let { return it }
        return try {
            val s = SessionManager()
            s.addListener(object : AlertListener {
                override fun types(): IntArray? = intArrayOf(AlertType.ADD_TORRENT.swig())
                override fun alert(alert: Alert<*>?) {
                    if (alert is AddTorrentAlert) {
                        val h = alert.handle()
                        handles[h.infoHash().toHex()] = h
                    }
                }
            })
            s.start()
            sm = s
            startTicker()
            s
        } catch (t: Throwable) {
            error.value = "Torrent engine couldn't start on this phone: ${t.javaClass.simpleName}"
            null
        }
    }

    /** Adds a magnet link. Returns false if it isn't one. */
    fun addMagnet(uri: String): Boolean {
        val u = uri.trim()
        val hash = hashOf(u) ?: return false
        if (!u.startsWith("magnet:?", ignoreCase = true)) return false
        prefs().edit().putString("src_$hash", u).apply()
        scope.launch { start(u) }
        return true
    }

    /** Adds a .torrent file the user picked. */
    fun addFile(ctx: Context, uri: Uri): Boolean {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
            val f = File(app!!.filesDir, "t_${System.currentTimeMillis()}.torrent")
            f.writeBytes(bytes)
            val hash = TorrentInfo(f).infoHash().toHex()
            prefs().edit().putString("src_$hash", "file:${f.absolutePath}").apply()
            scope.launch { start("file:${f.absolutePath}") }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun start(source: String) {
        val s = ensure() ?: return
        try {
            if (source.startsWith("file:")) {
                val f = File(source.removePrefix("file:"))
                if (f.exists()) s.download(TorrentInfo(f), dir())
            } else {
                s.download(source, dir(), torrent_flags_t())
            }
            Torrents.kick()
        } catch (t: Throwable) {
            error.value = "Couldn't add that torrent: ${t.javaClass.simpleName}"
        }
    }

    private fun kick() {
        val ctx = app ?: return
        try {
            ctx.startForegroundService(Intent(ctx, TorrentService::class.java))
        } catch (e: Exception) {
            // Background start refused; the torrent still runs while the app is open.
        }
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            var n = 0
            while (true) {
                refresh()
                if (++n % 30 == 0) app?.let { Cool.check(it) }
                delay(1000)
            }
        }
    }

    private fun refresh() {
        val out = ArrayList<T>()
        for ((id, h) in handles) {
            try {
                if (!h.isValid) continue
                val st = h.status()
                if (st.isFinished && finished.add(id)) {
                    h.pause()
                    paused.add(id)
                }
                val done = finished.contains(id)
                val isPaused = paused.contains(id)
                val name = st.name().takeIf { it.isNotBlank() } ?: "Fetching info…"
                val state = when {
                    done -> "Finished"
                    isPaused -> "Paused"
                    else -> when (st.state().name) {
                        "CHECKING_FILES", "CHECKING_RESUME_DATA" -> "Checking files"
                        "DOWNLOADING_METADATA" -> "Fetching info"
                        "DOWNLOADING" -> "Downloading"
                        else -> st.state().name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
                out.add(T(id, name, if (done) 1f else st.progress(), if (isPaused) 0 else st.downloadPayloadRate().toLong(),
                    st.numPeers(), state, isPaused, done, st.totalWanted()))
            } catch (e: Exception) {
                // handle went away mid-read
            }
        }
        list.value = out.sortedBy { it.name }
    }

    fun pause(id: String) { handles[id]?.let { it.pause(); paused.add(id) } }
    fun resume(id: String) {
        if (finished.contains(id)) return
        handles[id]?.let { it.resume(); paused.remove(id) }
        kick()
    }
    fun pauseAll() { handles.keys.forEach { pause(it) } }
    fun resumeAll() { handles.keys.forEach { resume(it) } }

    fun remove(id: String) {
        val h = handles.remove(id) ?: return
        val name = try { h.status().name() } catch (e: Exception) { "" }
        try { sm?.remove(h) } catch (e: Exception) { }
        paused.remove(id); finished.remove(id)
        prefs().edit().remove("src_$id").apply()
        if (name.isNotBlank()) File(dir(), name).deleteRecursively()
        refresh()
    }

    /** Copies finished files into your library folders. Returns how many were saved. */
    suspend fun saveToLibrary(id: String): Int = withContext(Dispatchers.IO) {
        val name = list.value.firstOrNull { it.id == id }?.name ?: return@withContext 0
        val root = File(dir(), name)
        val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else listOf(root).filter { it.exists() }
        var n = 0
        for (f in files) {
            try {
                f.inputStream().use { Downloader.saveToLibrary(f.name, mimeOf(f.name), it) }
                n++
            } catch (e: Exception) { }
        }
        n
    }

    fun anyActive() = list.value.any { !it.paused && !it.done }
}

class TorrentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("torrent", "Torrents", NotificationManager.IMPORTANCE_LOW))
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clipz:torrent").apply { setReferenceCounted(false); acquire() }
    }

    private fun note(list: List<Torrents.T>): Notification {
        val active = list.filter { !it.paused && !it.done }
        val rate = active.sumOf { it.rate }
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "torrent")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Torrents: ${active.size} active")
            .setContentText(fmtBytes(rate) + "/s")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 1002, note(Torrents.list.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher == null) {
            val nm = getSystemService(NotificationManager::class.java)
            watcher = scope.launch {
                delay(4000) // give a freshly added torrent time to appear
                Torrents.list.collectLatest { l ->
                    if (l.none { !it.paused && !it.done }) stopSelf() else nm.notify(1002, note(l))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Torrents.pauseAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

@Composable
fun TorrentsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by Torrents.list.collectAsState()
    val err by Torrents.error.collectAsState()
    var link by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = Torrents.addFile(ctx, uri)
            Toast.makeText(ctx, if (ok) "Torrent added" else "That isn't a valid .torrent file", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Torrents", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a magnet link") }, singleLine = true,
            )
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Paste", false) {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    link = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: link
                }
                Pill("Add magnet", accent = true) {
                    if (Torrents.addMagnet(link)) { link = ""; Toast.makeText(ctx, "Torrent added", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(ctx, "That isn't a magnet link", Toast.LENGTH_SHORT).show()
                }
                Pill(".torrent file") { pickFile.launch(arrayOf("*/*")) }
            }
        }
        err?.let { Text(it, color = Coral) }
        if (list.isEmpty()) {
            Text(
                "Nothing here yet. Tap a magnet link in any browser and choose Clipz, paste one above, or add a .torrent file.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.forEach { t ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                GradientBar(if (t.state == "Fetching info") null else t.progress, Modifier.fillMaxWidth())
                Text(
                    "${t.state} · ${(t.progress * 100).toInt()}%" + (if (t.size > 0) " of ${fmtBytes(t.size)}" else "") +
                        (if (!t.paused && !t.done) " · ${fmtBytes(t.rate)}/s · ${t.peers} peers" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.done) {
                        Pill("Save to library", accent = true) {
                            scope.launch {
                                val n = Torrents.saveToLibrary(t.id)
                                Toast.makeText(ctx, if (n > 0) "Saved $n file(s) to your library" else "Couldn't save", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Pill(if (t.paused) "Resume" else "Pause", accent = true) { if (t.paused) Torrents.resume(t.id) else Torrents.pause(t.id) }
                    }
                    Pill("Remove and delete files", danger = true) { Torrents.remove(t.id) }
                }
            }
        }
        Text(
            "Downloads stop seeding as soon as they finish, to save battery and data. Files are kept in Clipz's own storage until you save them to your library; removing a torrent deletes that copy.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
}
