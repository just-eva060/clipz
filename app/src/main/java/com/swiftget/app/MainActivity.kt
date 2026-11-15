package com.swiftget.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private val URL_REGEX = Regex("https?://[^\\s\"'<>]+")

private val MEDIA_EXT = mapOf(
    "mp4" to "Video", "m4v" to "Video", "webm" to "Video", "mov" to "Video", "mkv" to "Video",
    "mp3" to "Audio", "m4a" to "Audio", "aac" to "Audio", "ogg" to "Audio", "oga" to "Audio",
    "wav" to "Audio", "flac" to "Audio", "m3u8" to "HLS",
)

// Tiny pieces of a stream. They are not files on their own, so they are never offered.
private val SEGMENT_EXT = setOf("ts", "m4s", "m4f", "cmfv", "cmfa")

// Runs inside the page. Reports (1) what <video>/<audio> elements play, and (2) any fetch/XHR answer whose
// content type is video, audio or a playlist, which catches links that have no file extension.
// blob: addresses (video assembled in memory) cannot be saved this way and are skipped.
private const val SCAN_JS = """
(function(){
  if (window.__sgScan) return; window.__sgScan = 1;
  try { ClipzBridge.ready(); } catch(x) {}
  function rep(u){ try { if (u && /^https?:/.test(u)) ClipzBridge.found(String(u)); } catch(x) {} }
  function isMedia(ct){ ct = (ct || '').toLowerCase(); return ct.indexOf('video/') === 0 || ct.indexOf('audio/') === 0 || ct.indexOf('mpegurl') >= 0; }
  function scan(){ try {
    document.querySelectorAll('video,audio,source').forEach(function(e){ rep(e.currentSrc || e.src); });
  } catch(x) {} }
  try {
    var of = window.fetch;
    if (of) window.fetch = function(){
      return of.apply(this, arguments).then(function(r){
        try { if (isMedia(r.headers.get('content-type'))) rep(r.url); } catch(x) {}
        return r;
      });
    };
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(m, u){
      var self = this;
      try { this.addEventListener('readystatechange', function(){
        if (self.readyState === 2 && isMedia(self.getResponseHeader('content-type'))) rep(self.responseURL || u);
      }); } catch(x) {}
      return oo.apply(this, arguments);
    };
  } catch(x) {}
  scan(); setInterval(scan, 2500);
})();
"""

class MainActivity : ComponentActivity() {
    // The WebView is created only when the Browser tab is first opened. On phones where Android System WebView
    // is missing or disabled, creating it fails; doing it at startup would stop the whole app from opening.
    private var webView: WebView? = null
    private var webError: String? = null

    private val tab = mutableIntStateOf(0)
    private val showSettings = mutableStateOf(false)
    private val moreScreen = mutableStateOf<String?>(null)
    private val viewing = mutableStateOf<DownloadUi?>(null)
    private val clipUrl = mutableStateOf<String?>(null)
    private val browserUrl = mutableStateOf("")
    private var lastClip: String? = null
    private val detected = mutableStateListOf<DetectedMedia>()
    private val seenCount = AtomicInteger(0)
    private val seen = mutableIntStateOf(0)
    private val bridgeReady = mutableStateOf(false)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val quality = mutableStateOf<Pair<DetectedMedia, List<HlsVariant>>?>(null)

    override fun onStop() {
        Vault.lock()
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // ---- screen capture: Android shows its own approval dialog for every session
    private var pendingMode = ScreenCaptureService.MODE_RECORD
    private var pendingAudio = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_CODE, r.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    .putExtra(ScreenCaptureService.EXTRA_MODE, pendingMode)
                    .putExtra(ScreenCaptureService.EXTRA_AUDIO, pendingAudio)
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true) // get out of the way so you can show what you want captured
            }
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingAudio = granted
            launchProjection()
        }

    private fun startCapture(mode: String, audio: Boolean) {
        pendingMode = mode
        pendingAudio = audio
        if (audio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        try {
            val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture isn't available on this phone", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Downloader.init(applicationContext)
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            ClipzTheme {
                val list by Downloader.ui.collectAsState()
                val q = quality.value
                if (q != null) {
                    AlertDialog(
                        onDismissRequest = { quality.value = null },
                        title = { Text("Choose quality") },
                        text = {
                            Column {
                                q.second.forEachIndexed { i, v ->
                                    TextButton(onClick = {
                                        quality.value = null
                                        startDetected(q.first, v.url, v.label)
                                    }) {
                                        val mbps = if (v.bandwidth > 0) " · %.1f Mbps".format(v.bandwidth / 1_000_000.0) else ""
                                        val best = if (i == 0) " (best)" else ""
                                        Text(v.label + mbps + best)
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { quality.value = null }) { Text("Cancel") } },
                    )
                }
                BackHandler(enabled = showSettings.value) { showSettings.value = false }
                BackHandler(enabled = tab.intValue == 4 && moreScreen.value != null && !showSettings.value) { moreScreen.value = null }
                viewing.value?.let { d ->
                    val u = d.savedUri
                    if (u != null) MediaViewer(Uri.parse(u), d.mime, d.name) { viewing.value = null }
                }
                Scaffold(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                            val tabs = listOf("⚡" to "Home", "🌐" to "Browser", "💬" to "Status", "📁" to "Library", "☰" to "More")
                            tabs.forEachIndexed { i, (icon, label) ->
                                NavigationBarItem(
                                    selected = tab.intValue == i && !showSettings.value,
                                    onClick = {
                                        if (i == 4 && tab.intValue == 4) moreScreen.value = null
                                        tab.intValue = i; showSettings.value = false
                                    },
                                    icon = { Text(icon) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Mint.copy(alpha = 0.2f)),
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        if (showSettings.value) {
                            SettingsScreen(
                                onBack = { showSettings.value = false },
                                onAskNotifications = {
                                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                onAddTile = { addTile(QuickTileService::class.java, "Clipz") },
                                onScreenshot = { startCapture(ScreenCaptureService.MODE_SHOT, false) },
                                onRecord = { audio -> startCapture(ScreenCaptureService.MODE_RECORD, audio) },
                            )
                        } else {
                            when (tab.intValue) {
                                0 -> DownloadsScreen(
                                    list = list,
                                    clipUrl = clipUrl.value,
                                    onClipAccept = {
                                        clipUrl.value?.let { Downloader.add(it) }
                                        lastClip = clipUrl.value
                                        clipUrl.value = null
                                    },
                                    onClipDismiss = {
                                        lastClip = clipUrl.value
                                        clipUrl.value = null
                                    },
                                    onSettings = { showSettings.value = true },
                                    onBrowse = { tab.intValue = 1 },
                                    onView = { d -> viewing.value = d },
                                    onHide = { d -> hide(d) },
                                )
                                1 -> {
                                    val w = ensureWeb()
                                    if (w != null) {
                                        val status = "Scanner: " + (if (bridgeReady.value) "on" else "waiting for page") +
                                            " · ${seen.intValue} requests checked"
                                        BrowserScreen(w, browserUrl, detected, status, { m -> downloadDetected(m) }) { tab.intValue = 0 }
                                    } else {
                                        WebViewMissing(webError)
                                    }
                                }
                                2 -> StatusScreen()
                                3 -> LibraryScreen(list, onView = { d -> viewing.value = d }, onHide = { d -> hide(d) })
                                else -> MoreScreen(
                                    moreScreen.value, { moreScreen.value = it }, { showSettings.value = true }, list,
                                    onAddCoolTile = { addTile(CoolTileService::class.java, "Cool down") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Moves a finished file into the PIN-protected vault. */
    private fun hide(d: DownloadUi) {
        if (!Vault.hasPin()) {
            Toast.makeText(this, "Create your vault PIN first", Toast.LENGTH_LONG).show()
            tab.intValue = 4
            moreScreen.value = "vault"
            return
        }
        uiScope.launch {
            val ok = Vault.hide(d)
            Toast.makeText(
                this@MainActivity,
                if (ok) "Hidden in your vault" else "Couldn't hide this file",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra(EXTRA_RESUME, false)) { if (Cool.isOn(this)) Cool.turnOff(this) else Downloader.resumeAll() }
        // Magnet links and .torrent files go to the torrent engine.
        val shared = if (i.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        val magnet = (if (i.action == Intent.ACTION_VIEW) i.dataString else shared)
            ?.let { Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE).find(it)?.value }
        if (magnet != null) {
            val ok = Torrents.addMagnet(magnet)
            Toast.makeText(this, if (ok) "Torrent added. Open More > Torrents." else "That magnet link isn't valid", Toast.LENGTH_LONG).show()
            tab.intValue = 4; moreScreen.value = "torrents"
            return
        }
        if (i.action == Intent.ACTION_VIEW && (i.type == "application/x-bittorrent" || i.dataString?.substringBefore('?')?.endsWith(".torrent") == true)) {
            val d = i.data
            if (d != null && d.scheme != "http" && d.scheme != "https") {
                val ok = Torrents.addFile(this, d)
                Toast.makeText(this, if (ok) "Torrent added" else "Couldn't read that .torrent file", Toast.LENGTH_LONG).show()
                tab.intValue = 4; moreScreen.value = "torrents"
                return
            }
        }
        val url = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { URL_REGEX.find(it)?.value }
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        if (Downloader.add(url) != null) Toast.makeText(this, "Added to Clipz", Toast.LENGTH_SHORT).show()
    }

    private fun checkClipboard() {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            val url = URL_REGEX.find(text)?.value ?: return
            if (url != lastClip && !Downloader.knows(url)) clipUrl.value = url
        } catch (ignored: Exception) {
        }
    }

    // ---- media detection in the Browser tab

    private fun sniff(url: String) {
        val n = seenCount.incrementAndGet()
        if (n < 10 || n % 10 == 0) runOnUiThread { seen.intValue = n }
        if (!url.startsWith("http")) return
        val ext = url.substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.', "")
        val kind = MEDIA_EXT[ext] ?: return
        addDetected(url, kind)
    }

    private fun addDetected(url: String, kind: String) {
        runOnUiThread {
            if (detected.none { it.url == url }) {
                if (detected.size >= 30) detected.removeAt(0)
                detected.add(DetectedMedia(url, kind, URLUtil.guessFileName(url, null, null)))
            }
        }
    }

    private fun downloadDetected(m: DetectedMedia) {
        if (m.kind != "HLS") {
            startDetected(m, m.url, null)
            return
        }
        // Streams can offer several qualities: ask the playlist, and let the user choose.
        val ua = webView?.settings?.userAgentString
        val cookie = CookieManager.getInstance().getCookie(m.url)
        val referer = webView?.url
        Toast.makeText(this, "Checking available qualities…", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val variants = Downloader.hlsVariants(m.url, ua, cookie, referer)
            if (variants.size > 1) quality.value = m to variants else startDetected(m, m.url, null)
        }
    }

    private fun startDetected(m: DetectedMedia, url: String, tag: String?) {
        val title = (webView?.title ?: "").trim().ifEmpty { m.label.substringBeforeLast('.') }
        val ext = if (m.kind == "HLS") "ts" else
            m.url.substringBefore('?').substringAfterLast('.', "")
                .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
        val name = if (tag != null) "$title [$tag].$ext" else "$title.$ext"
        Downloader.add(
            url, webView?.settings?.userAgentString, CookieManager.getInstance().getCookie(url),
            webView?.url, name, hls = m.kind == "HLS",
        )
        Toast.makeText(this, "Added to Clipz", Toast.LENGTH_SHORT).show()
        tab.intValue = 0
    }

    private fun addTile(cls: Class<*>, label: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, cls),
                label,
                Icon.createWithResource(this, R.drawable.ic_stat),
                mainExecutor,
            ) {}
        } else {
            Toast.makeText(this, "Swipe down twice, tap the pencil, and drag $label into your tiles.", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureWeb(): WebView? {
        webView?.let { return it }
        if (webError != null) return null
        return try {
            buildWebView().also { webView = it }
        } catch (e: Throwable) {
            webError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptCookie(true)
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun ready() {
                runOnUiThread { bridgeReady.value = true }
            }

            @JavascriptInterface
            fun found(u: String) {
                if (!u.startsWith("http")) return
                val path = u.substringBefore('?').lowercase()
                if (path.substringAfterLast('.', "") in SEGMENT_EXT) return
                addDetected(u, if (path.endsWith(".m3u8")) "HLS" else "Media")
            }
        }, "ClipzBridge")
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                detected.clear()
                seenCount.set(0)
                seen.intValue = 0
                bridgeReady.value = false
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                view?.evaluateJavascript(SCAN_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(SCAN_JS, null)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.url?.toString()?.let { sniff(it) }
                return null // observe only; the request goes through untouched
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                browserUrl.value = url ?: ""
            }
        }
        // Any ordinary file download the site offers is handed to Clipz, with the page's cookies.
        w.setDownloadListener { url, ua, cd, mime, _ ->
            if (url.startsWith("http")) {
                Downloader.add(url, ua, CookieManager.getInstance().getCookie(url), w.url, URLUtil.guessFileName(url, cd, mime))
                Toast.makeText(this, "Added to Clipz", Toast.LENGTH_SHORT).show()
                tab.intValue = 0
            } else {
                Toast.makeText(this, "This kind of download can't be handled", Toast.LENGTH_LONG).show()
            }
        }
        w.loadUrl("https://www.google.com")
        return w
    }
}
