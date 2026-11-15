package com.swiftget.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A server answer we should not retry forever (404, 403, ...). */
class HttpFailure(val code: Int) : RuntimeException("Server replied HTTP $code")

/** The file changed on the server, or the server ignored our Range request. */
class ContentChanged : RuntimeException("File changed on the server")

/**
 * The whole engine. Every download is split into byte ranges that are fetched in parallel.
 * Progress of every range is persisted, so a killed app, a reboot or a dead network
 * only ever costs the few KB that were in flight.
 */
object Downloader {
    private const val MIN_SEGMENT = 512L * 1024
    private const val MIN_STEAL = 256L * 1024
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private lateinit var app: Context
    private lateinit var store: File
    private var started = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val items = LinkedHashMap<String, DownloadItem>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()

    private val _ui = MutableStateFlow<List<DownloadUi>>(emptyList())
    val ui: StateFlow<List<DownloadUi>> = _ui.asStateFlow()
    val online = MutableStateFlow(true)
    private val running = MutableStateFlow(0)

    /** How many downloads run at once. 0 = no limit. The queue itself has no limit on size or count. */
    var maxParallel: Int
        get() = app.getSharedPreferences("swiftget", Context.MODE_PRIVATE).getInt("parallel", 3)
        set(v) {
            app.getSharedPreferences("swiftget", Context.MODE_PRIVATE).edit().putInt("parallel", v).apply()
        }

    private suspend fun acquireSlot() {
        while (true) {
            val limit = maxParallel.let { if (it <= 0) Int.MAX_VALUE else it }
            val cur = running.value
            if (cur < limit && running.compareAndSet(cur, cur + 1)) return
            delay(400)
        }
    }

    val maxConnections: Int
        get() = app.getSharedPreferences("swiftget", Context.MODE_PRIVATE).getInt("connections", 8)

    fun setMaxConnections(n: Int) {
        app.getSharedPreferences("swiftget", Context.MODE_PRIVATE).edit().putInt("connections", n).apply()
    }

    // HTTP/1.1 only: HTTP/2 would multiplex every range onto ONE socket and defeat parallel fetching.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .build()
    }

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        store = File(app.filesDir, "downloads.json")
        load()
        watchNetwork()
        scope.launch { ticker() }
        resumeInterrupted()
    }

    private fun load() {
        try {
            if (store.exists()) {
                val arr = JSONArray(store.readText())
                synchronized(items) {
                    for (i in 0 until arr.length()) {
                        val item = DownloadItem.fromJson(arr.getJSONObject(i))
                        items[item.id] = item
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        publish()
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            synchronized(items) { items.values.forEach { arr.put(it.toJson()) } }
            val tmp = File(store.parentFile, "downloads.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(store)
        } catch (ignored: Exception) {
        }
    }

    private fun publish() {
        val list = synchronized(items) { items.values.map { it.toUi() } }.reversed()
        _ui.value = list
    }

    private fun resumeInterrupted() {
        val todo = synchronized(items) { items.values.filter { it.status in ACTIVE_STATES } }
        if (todo.isEmpty()) return
        if (ensureService()) {
            todo.forEach { start(it) }
        } else {
            // Android 15 refuses to start data-transfer services straight after boot.
            todo.forEach { if (it.status != Status.FINALIZING) it.status = Status.PAUSED }
            save(); publish()
            Notif.tapToResume(app, "Tap to continue your downloads.")
        }
    }

    fun ensureService(): Boolean = try {
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        true
    } catch (ignored: Exception) {
        false
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        online.value = cm.activeNetwork != null
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online.value = true
                }

                override fun onLost(network: Network) {
                    online.value = false
                    // Dead sockets would otherwise hang until the read timeout.
                    calls.values.forEach { set -> set.toList().forEach { it.cancel() } }
                }
            })
        } catch (ignored: Exception) {
        }
    }

    private suspend fun ticker() {
        var tick = 0
        val last = HashMap<String, Long>()
        while (true) {
            delay(1000)
            val snapshot = synchronized(items) { items.values.toList() }
            for (i in snapshot) {
                val d = i.downloaded
                if (i.status == Status.DOWNLOADING) {
                    val inst = (d - (last[i.id] ?: d)).coerceAtLeast(0)
                    i.speed = if (i.speed == 0L) inst else (i.speed * 0.6 + inst * 0.4).toLong()
                } else {
                    i.speed = 0
                }
                last[i.id] = d
            }
            publish()
            tick++
            if (tick % 2 == 0 && snapshot.any { it.status in ACTIVE_STATES }) save()
        }
    }

    // ---------------------------------------------------------------- public API

    fun knows(url: String): Boolean = synchronized(items) { items.values.any { it.url == url } }

    fun add(
        url: String,
        userAgent: String? = null,
        cookie: String? = null,
        referer: String? = null,
        name: String? = null,
        hls: Boolean = false,
    ): String? {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
        synchronized(items) {
            items.values.firstOrNull { it.url == clean && it.status != Status.COMPLETED }?.let { return it.id }
        }
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = clean,
            fileName = sanitize(name ?: URLUtil.guessFileName(clean, null, null)),
            totalBytes = -1, rangeOk = false, etag = null, lastModified = null,
            userAgent = userAgent, cookie = cookie, referer = referer,
            createdAt = System.currentTimeMillis(), nameLocked = name != null,
        )
        item.hls = hls
        synchronized(items) { items[item.id] = item }
        save(); publish()
        ensureService()
        start(item)
        return item.id
    }

    /**
     * Writes a file into the phone's shared storage, sorted into a folder by type:
     * Movies/Clipz, Music/Clipz, Pictures/Clipz, or Download/Clipz/<type>.
     * Saved WhatsApp statuses go into a "Status" sub-folder.
     */
    fun saveToLibrary(name: String, mime: String, input: InputStream, folder: String? = null): android.net.Uri {
        val sub = when {
            folder == "Status" -> "/Status"
            folder != null && folder.startsWith("Topic:") ->
                "/" + folder.removePrefix("Topic:").replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "General" }
            else -> ""
        }
        val (collection, rel) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Clipz$sub"
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Clipz$sub"
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Clipz$sub"
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Clipz/${folder ?: categoryOf(name)}"
        }
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> input.use { it.copyTo(out, 256 * 1024) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** Lists a file that already exists on the phone (a saved status, a file restored from the vault) in the app. */
    fun registerExternal(name: String, uri: String, size: Long, folder: String?) {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(), url = "local://$name", fileName = name,
            totalBytes = size, rangeOk = false, etag = null, lastModified = null,
            userAgent = null, cookie = null, referer = null,
            createdAt = System.currentTimeMillis(), nameLocked = true,
        )
        item.status = Status.COMPLETED
        item.savedUri = uri
        item.folder = folder
        synchronized(items) { items[item.id] = item }
        save(); publish()
    }

    fun setFolder(id: String, folder: String?) {
        val item = get(id) ?: return
        if (item.folder == "Status") return
        item.folder = folder
        save(); publish()
    }

    fun pause(id: String) {
        val item = get(id) ?: return
        if (item.status !in ACTIVE_STATES || item.status == Status.FINALIZING) return
        jobs.remove(id)?.cancel()
        cancelCalls(id)
        item.status = Status.PAUSED
        item.speed = 0
        save(); publish()
    }

    fun resume(id: String) {
        val item = get(id) ?: return
        if (item.status != Status.PAUSED && item.status != Status.FAILED) return
        ensureService()
        start(item)
    }

    fun pauseAll() {
        synchronized(items) { items.keys.toList() }.forEach { pause(it) }
    }

    fun resumeAll() {
        synchronized(items) { items.values.filter { it.status == Status.PAUSED || it.status == Status.FAILED } }
            .forEach { resume(it.id) }
    }

    /** Removes the entry and any partial data. A finished file is only deleted when [deleteFile] is true. */
    fun remove(id: String, deleteFile: Boolean = false) {
        val item = synchronized(items) { items.remove(id) } ?: return
        jobs.remove(id)?.cancel()
        cancelCalls(id)
        partFile(item).delete()
        if (deleteFile) {
            item.savedUri?.let { runCatching { app.contentResolver.delete(android.net.Uri.parse(it), null, null) } }
        }
        save(); publish()
    }

    private fun get(id: String) = synchronized(items) { items[id] }

    private fun cancelCalls(id: String) {
        calls[id]?.toList()?.forEach { it.cancel() }
    }

    // ---------------------------------------------------------------- engine

    private fun start(item: DownloadItem) {
        jobs[item.id]?.cancel()
        item.error = null
        if (item.status != Status.FINALIZING) item.status = Status.QUEUED
        publish()
        jobs[item.id] = scope.launch {
            var held = false
            try {
                acquireSlot()
                held = true
                execute(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                item.status = Status.FAILED
                item.error = e.message ?: e.javaClass.simpleName
            } finally {
                if (held) running.update { it - 1 }
                save(); publish()
            }
        }
    }

    private suspend fun execute(item: DownloadItem) {
        var restarts = 0
        while (true) {
            try {
                if (item.hls) {
                    downloadHls(item)
                } else {
                    ensureMetadata(item)
                    if (!item.segments.all { it.done }) downloadAll(item)
                }
                finish(item)
                return
            } catch (ignored: ContentChanged) {
                if (++restarts > 3) throw HttpFailure(416)
                item.segments.clear()
                item.totalBytes = -1
                partFile(item).delete()
            }
        }
    }

    private suspend fun ensureMetadata(item: DownloadItem) {
        if (item.segments.isNotEmpty()) return
        var backoff = 1000L
        while (true) {
            awaitOnline(item)
            try {
                probe(item)
                break
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                item.error = e.message
                delay(backoff)
                backoff = minOf(backoff * 2, 30_000L)
            }
        }
        item.error = null
        val total = item.totalBytes
        if (item.rangeOk && total > 0) {
            val n = (total / MIN_SEGMENT).toInt().coerceIn(1, maxConnections)
            val size = total / n
            for (i in 0 until n) {
                val s = i * size
                val e = if (i == n - 1) total - 1 else s + size - 1
                item.segments.add(Segment(s, e))
            }
        } else {
            item.segments.add(Segment(0, if (total > 0) total - 1 else -1))
        }
    }

    private fun probe(item: DownloadItem) {
        val call = client.newCall(baseRequest(item).header("Range", "bytes=0-0").build())
        calls.getOrPut(item.id) { ConcurrentHashMap.newKeySet() }.add(call)
        try {
            call.execute().use { r ->
                val code = r.code
                if (code == 408 || code == 429 || code >= 500) throw IOException("HTTP $code")
                if (code != 200 && code != 206) throw HttpFailure(code)
                item.etag = r.header("ETag")
                item.lastModified = r.header("Last-Modified")
                if (code == 206) {
                    val total = r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull() ?: -1L
                    item.totalBytes = total
                    item.rangeOk = total > 0
                } else {
                    item.totalBytes = r.header("Content-Length")?.toLongOrNull() ?: -1L
                    item.rangeOk = false
                }
                if (!item.nameLocked) {
                    item.fileName = sanitize(
                        URLUtil.guessFileName(
                            r.request.url.toString(), r.header("Content-Disposition"), r.header("Content-Type"),
                        ),
                    )
                }
            }
        } finally {
            calls[item.id]?.remove(call)
        }
    }

    private suspend fun downloadAll(item: DownloadItem) = coroutineScope {
        val part = partFile(item)
        part.parentFile?.mkdirs()
        if (item.segments.any { it.downloaded > 0 } && !part.exists()) item.segments.forEach { it.downloaded = 0 }
        if (item.rangeOk && item.totalBytes > 0) {
            RandomAccessFile(part, "rw").use { if (it.length() != item.totalBytes) it.setLength(item.totalBytes) }
        } else if (!part.exists()) {
            part.createNewFile()
        }
        item.status = Status.DOWNLOADING
        publish()
        item.segments.filter { !it.done }.forEach { seg -> launch { workerLoop(item, seg, part) } }
    }

    /** A worker keeps going until the file is finished, stealing half of the slowest remaining range. */
    private suspend fun workerLoop(item: DownloadItem, first: Segment, part: File) {
        var seg: Segment? = first
        while (seg != null) {
            runSegment(item, seg, part)
            seg = steal(item)
        }
    }

    @Synchronized
    private fun steal(item: DownloadItem): Segment? {
        if (!item.rangeOk) return null
        val victim = item.segments
            .filter { !it.done && it.remaining > MIN_STEAL }
            .maxByOrNull { it.remaining } ?: return null
        val mid = victim.start + victim.downloaded + victim.remaining / 2
        val fresh = Segment(mid, victim.end, 0)
        victim.end = mid - 1
        item.segments.add(fresh)
        return fresh
    }

    private suspend fun runSegment(item: DownloadItem, seg: Segment, part: File) {
        var backoff = 1000L
        var fatalHttp = 0
        while (!seg.done) {
            currentCoroutineContext().ensureActive()
            awaitOnline(item)
            if (!item.rangeOk) seg.downloaded = 0
            val offset = seg.start + seg.downloaded
            val rb = baseRequest(item)
            if (item.rangeOk) {
                rb.header("Range", "bytes=$offset-${seg.end}")
                validator(item)?.let { rb.header("If-Range", it) }
            }
            val call = client.newCall(rb.build())
            val set = calls.getOrPut(item.id) { ConcurrentHashMap.newKeySet() }
            set.add(call)
            val before = seg.downloaded
            try {
                call.execute().use { resp ->
                    val code = resp.code
                    if (code == 206 || (code == 200 && (!item.rangeOk || offset == 0L))) {
                        fatalHttp = 0
                        val body = resp.body ?: throw IOException("Empty response")
                        pump(item, seg, part, offset, body.byteStream())
                    } else if (code == 416 || (code == 200 && item.rangeOk)) {
                        throw ContentChanged()
                    } else if (code == 408 || code == 429 || code >= 500) {
                        throw IOException("HTTP $code")
                    } else {
                        if (++fatalHttp >= 5) throw HttpFailure(code) else throw IOException("HTTP $code")
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                item.error = e.message ?: "Connection lost"
            } finally {
                set.remove(call)
            }
            if (!seg.done) {
                if (seg.downloaded > before) {
                    backoff = 1000L
                } else {
                    delay(backoff)
                    backoff = minOf(backoff * 2, 30_000L)
                }
            }
        }
    }

    private suspend fun pump(item: DownloadItem, seg: Segment, part: File, offset: Long, input: InputStream) {
        val ctx = currentCoroutineContext()
        val buf = ByteArray(32 * 1024)
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(offset)
            var pos = offset
            while (true) {
                ctx.ensureActive()
                val end = seg.end
                val want = if (end < 0) buf.size else minOf(buf.size.toLong(), end - pos + 1).toInt()
                if (want <= 0) return
                val n = input.read(buf, 0, want)
                if (n < 0) {
                    if (end < 0) {
                        // Length was unknown: the stream ending means the file is complete.
                        if (pos == seg.start) throw HttpFailure(204)
                        seg.end = pos - 1
                        item.totalBytes = pos
                    }
                    return
                }
                raf.write(buf, 0, n)
                pos += n
                seg.downloaded = pos - seg.start
                item.error = null
            }
        }
    }

    private suspend fun awaitOnline(item: DownloadItem) {
        if (online.value) return
        if (item.status == Status.DOWNLOADING || item.status == Status.QUEUED) item.status = Status.WAITING_NETWORK
        online.first { it }
        if (item.status == Status.WAITING_NETWORK) item.status = Status.DOWNLOADING
    }

    private suspend fun finish(item: DownloadItem) {
        val part = partFile(item)
        item.status = Status.FINALIZING
        item.speed = 0
        publish(); save()
        val uri = withContext(Dispatchers.IO) { moveToDownloads(item, part) }
        item.savedUri = uri.toString()
        part.delete()
        item.status = Status.COMPLETED
        save(); publish()
        Notif.completed(app, item.fileName, uri.toString())
    }

    private fun moveToDownloads(item: DownloadItem, part: File): android.net.Uri =
        saveToLibrary(item.fileName, mimeOf(item.fileName), part.inputStream(), item.folder)

    @Suppress("unused")
    private fun moveToDownloadsLegacy(item: DownloadItem, part: File): android.net.Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(item.fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Clipz")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> part.inputStream().use { it.copyTo(out, 256 * 1024) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    // ---------------------------------------------------------------- HLS
    // A playlist (.m3u8) lists many small video pieces. They are fetched in order and joined into one file.
    // Encrypted (DRM) streams and live streams are refused with a clear message, never worked around.

    private fun urlOf(base: String, ref: String): String =
        base.toHttpUrl().resolve(ref.trim())?.toString() ?: throw IOException("Bad link in playlist")

    private fun fetchText(item: DownloadItem, url: String): String {
        val call = client.newCall(baseRequest(item, url).build())
        calls.getOrPut(item.id) { ConcurrentHashMap.newKeySet() }.add(call)
        try {
            call.execute().use { r ->
                val code = r.code
                if (code == 408 || code == 429 || code >= 500) throw IOException("HTTP $code")
                if (!r.isSuccessful) throw HttpFailure(code)
                return r.body?.string() ?: throw IOException("Empty playlist")
            }
        } finally {
            calls[item.id]?.remove(call)
        }
    }

    /** Quality levels listed by a stream's master playlist, best first. Empty if the link has no choices. */
    suspend fun hlsVariants(url: String, userAgent: String?, cookie: String?, referer: String?): List<HlsVariant> =
        withContext(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(url)
                    .header("User-Agent", userAgent ?: DEFAULT_UA)
                    .header("Accept-Encoding", "identity")
                cookie?.let { b.header("Cookie", it) }
                referer?.let { b.header("Referer", it) }
                val text = client.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) "" else (r.body?.string() ?: "")
                }
                if (!text.contains("#EXT-X-STREAM-INF")) return@withContext emptyList()
                val lines = text.lines().map { it.trim() }
                val out = ArrayList<HlsVariant>()
                for (i in lines.indices) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                    val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                    val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val h = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)
                    val label = if (h != null) "${h}p" else "${bw / 1000} kbps"
                    out.add(HlsVariant(label, bw, urlOf(url, next)))
                }
                out.groupBy { it.label }
                    .map { (_, group) -> group.maxByOrNull { it.bandwidth }!! }
                    .sortedByDescending { it.bandwidth }
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun pickVariant(base: String, text: String): String {
        val lines = text.lines().map { it.trim() }
        var best = -1L
        var bestUrl: String? = null
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (bw > best) {
                best = bw
                bestUrl = next
            }
        }
        return urlOf(base, bestUrl ?: throw IllegalStateException("No playable stream in the playlist"))
    }

    private suspend fun loadPlaylist(item: DownloadItem): List<String> {
        var backoff = 1000L
        while (true) {
            awaitOnline(item)
            try {
                var url = item.url
                var text = fetchText(item, url)
                if (text.contains("#EXT-X-STREAM-INF")) {
                    url = pickVariant(url, text)
                    text = fetchText(item, url)
                }
                if (Regex("#EXT-X-(SESSION-)?KEY:[^\\n]*METHOD=(?!NONE)").containsMatchIn(text)) {
                    throw IllegalStateException("This stream is encrypted, so it can't be downloaded")
                }
                if (!text.contains("#EXT-X-ENDLIST")) {
                    throw IllegalStateException("Live streams can't be downloaded")
                }
                val out = ArrayList<String>()
                Regex("#EXT-X-MAP:[^\\n]*URI=\"([^\"]+)\"").find(text)?.let {
                    out.add(urlOf(url, it.groupValues[1]))
                    if (item.fileName.endsWith(".ts")) item.fileName = item.fileName.dropLast(3) + ".mp4"
                }
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { out.add(urlOf(url, it)) }
                if (out.isEmpty()) throw IllegalStateException("The playlist has no media")
                return out
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                item.error = e.message ?: "Connection lost"
                delay(backoff)
                backoff = minOf(backoff * 2, 30_000L)
            }
        }
    }

    private suspend fun downloadHls(item: DownloadItem) {
        val seg = item.segments.firstOrNull() ?: Segment(0, -1, 0).also { item.segments.add(it) }
        if (seg.done) return
        val pieces = loadPlaylist(item)
        val part = partFile(item)
        part.parentFile?.mkdirs()
        if ((item.hlsIndex > 0 && !part.exists()) || item.hlsIndex > pieces.size) {
            synchronized(item) { item.hlsIndex = 0; item.hlsOffset = 0 }
        }
        item.error = null
        item.status = Status.DOWNLOADING
        publish()
        val ctx = currentCoroutineContext()
        val buf = ByteArray(32 * 1024)
        var backoff = 1000L
        var fatal = 0
        while (item.hlsIndex < pieces.size) {
            ctx.ensureActive()
            awaitOnline(item)
            val start = item.hlsOffset
            val call = client.newCall(baseRequest(item, pieces[item.hlsIndex]).build())
            val set = calls.getOrPut(item.id) { ConcurrentHashMap.newKeySet() }
            set.add(call)
            try {
                var newOffset = start
                call.execute().use { r ->
                    val code = r.code
                    if (code == 408 || code == 429 || code >= 500) throw IOException("HTTP $code")
                    if (!r.isSuccessful) {
                        if (++fatal >= 5) throw HttpFailure(code) else throw IOException("HTTP $code")
                    }
                    fatal = 0
                    val input = r.body?.byteStream() ?: throw IOException("Empty segment")
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.setLength(start) // drop any half-written piece from an earlier attempt
                        raf.seek(start)
                        var written = 0L
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                            seg.downloaded = start + written
                            item.error = null
                        }
                        newOffset = start + written
                    }
                }
                // Index and offset change together so a saved snapshot is always consistent.
                synchronized(item) {
                    item.hlsOffset = newOffset
                    item.hlsIndex++
                }
                seg.downloaded = newOffset
                item.totalBytes = newOffset * pieces.size / item.hlsIndex // running estimate for the progress bar
                backoff = 1000L
            } catch (e: IOException) {
                ctx.ensureActive()
                seg.downloaded = item.hlsOffset
                item.error = e.message ?: "Connection lost"
                delay(backoff)
                backoff = minOf(backoff * 2, 30_000L)
            } finally {
                set.remove(call)
            }
        }
        if (item.hlsOffset <= 0) throw IllegalStateException("The stream contained no data")
        seg.end = item.hlsOffset - 1
        seg.downloaded = item.hlsOffset
        item.totalBytes = item.hlsOffset
    }

    // ---------------------------------------------------------------- helpers

    private fun partFile(item: DownloadItem) =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "partial/${item.id}.part")

    private fun baseRequest(item: DownloadItem, url: String = item.url): Request.Builder {
        val b = Request.Builder().url(url)
        b.header("User-Agent", item.userAgent ?: DEFAULT_UA)
        // Identity encoding keeps byte offsets exact (no transparent gzip).
        b.header("Accept-Encoding", "identity")
        item.cookie?.let { b.header("Cookie", it) }
        item.referer?.let { b.header("Referer", it) }
        return b
    }

    private fun validator(i: DownloadItem): String? = i.etag?.takeIf { !it.startsWith("W/") } ?: i.lastModified

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "download" }.take(120)
}
