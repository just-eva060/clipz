package com.swiftget.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class Trend(val rank: Int, val title: String, val channel: String, val views: Long, val id: String)

val REGIONS = linkedMapOf(
    "UG" to "Uganda", "KE" to "Kenya", "TZ" to "Tanzania", "RW" to "Rwanda", "NG" to "Nigeria",
    "GH" to "Ghana", "ZA" to "South Africa", "US" to "United States", "GB" to "United Kingdom", "IN" to "India",
)

/**
 * Daily trending list, per country, from YouTube's official Data API (you supply a free API key).
 * It never touches anyone's feed or account. Country is the finest level the API offers.
 */
object Trends {
    private var app: Context? = null
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun prefs() = app!!.getSharedPreferences("trends", Context.MODE_PRIVATE)
    private fun file() = File(app!!.filesDir, "trends.json")

    fun init(ctx: Context) { app = ctx.applicationContext }

    var apiKey: String
        get() = prefs().getString("key", "") ?: ""
        set(v) { prefs().edit().putString("key", v.trim()).apply() }

    var regions: Set<String>
        get() = prefs().getStringSet("regions", setOf("UG", "KE")) ?: setOf("UG")
        set(v) { prefs().edit().putStringSet("regions", v).apply() }

    var lastRefresh: String
        get() = prefs().getString("last", "") ?: ""
        private set(v) { prefs().edit().putString("last", v).apply() }

    private fun readAll(): JSONObject = try { JSONObject(file().readText()) } catch (e: Exception) { JSONObject() }

    fun dates(): List<String> = readAll().keys().asSequence().map { it.substringBefore('|') }.distinct().sortedDescending().toList()

    fun get(date: String, region: String): List<Trend> {
        val arr = readAll().optJSONArray("$date|$region") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Trend(it + 1, o.optString("t"), o.optString("c"), o.optLong("v"), o.optString("id"))
        }
    }

    /** Fetches today's list for each chosen country. Returns a short message about how it went. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        if (app == null) return@withContext "Not ready"
        val key = apiKey
        if (key.isBlank()) return@withContext "Add your YouTube API key first."
        val all = readAll()
        val today = LocalDate.now().toString()
        var ok = 0
        var problem: String? = null
        for (r in regions) {
            try {
                val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&chart=mostPopular" +
                    "&regionCode=$r&maxResults=25&key=" + Uri.encode(key)
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        problem = try { JSONObject(body).getJSONObject("error").getString("message") } catch (e: Exception) { "HTTP ${resp.code}" }
                        return@use
                    }
                    val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                    val out = JSONArray()
                    for (i in 0 until items.length()) {
                        val it = items.getJSONObject(i)
                        val sn = it.optJSONObject("snippet")
                        out.put(
                            JSONObject().put("id", it.optString("id")).put("t", sn?.optString("title") ?: "")
                                .put("c", sn?.optString("channelTitle") ?: "")
                                .put("v", it.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L),
                        )
                    }
                    all.put("$today|$r", out)
                    ok++
                }
            } catch (e: Exception) {
                problem = e.message ?: "Network error"
            }
        }
        // Keep the last 60 days.
        val keep = all.keys().asSequence().map { it }.filter { it.substringBefore('|') >= LocalDate.now().minusDays(60).toString() }.toList()
        val trimmed = JSONObject()
        keep.forEach { trimmed.put(it, all.get(it)) }
        file().writeText(trimmed.toString())
        if (ok > 0) lastRefresh = java.time.LocalDateTime.now().toString().substringBefore('.')
        if (problem != null && ok == 0) "Couldn't refresh: $problem" else "Updated $ok of ${regions.size} countries" + (problem?.let { " ($it)" } ?: "")
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<TrendsWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("trends", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class TrendsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Trends.init(applicationContext)
        if (Trends.apiKey.isBlank()) return Result.success()
        Trends.refresh()
        return Result.success()
    }
}

@Composable
fun TrendsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Trends.apiKey) }
    var regions by remember { mutableStateOf(Trends.regions) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var region by remember { mutableStateOf(regions.firstOrNull() ?: "UG") }
    var dateIdx by remember { mutableIntStateOf(0) }

    val dates = remember(tick) { Trends.dates() }
    val date = dates.getOrNull(dateIdx)
    val rows = remember(tick, date, region) { if (date != null) Trends.get(date, region) else emptyList() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Trending", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "What's most popular on YouTube per country, refreshed once a day from YouTube's official API. Use it for ideas. Clip only sources that are cleared in your Rights ledger.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Setup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Get a free key: Google Cloud Console > new project > enable YouTube Data API v3 > Credentials > API key. Paste it below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("YouTube API key") }, singleLine = true)
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REGIONS.forEach { (code, name) ->
                    FilterChip(
                        selected = code in regions,
                        onClick = { regions = if (code in regions) regions - code else regions + code },
                        label = { Text(name) },
                    )
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (busy) "Refreshing…" else "Save and refresh now", accent = true) {
                    if (!busy) {
                        Trends.apiKey = key
                        Trends.regions = regions
                        if (region !in regions) region = regions.firstOrNull() ?: region
                        busy = true
                        scope.launch {
                            message = Trends.refresh()
                            busy = false; dateIdx = 0; tick++
                        }
                    }
                }
            }
            message?.let { Text(it, color = Mint, modifier = Modifier.padding(top = 6.dp)) }
            if (Trends.lastRefresh.isNotBlank()) Text("Last refreshed ${Trends.lastRefresh.replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dates.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                regions.forEach { c -> FilterChip(selected = region == c, onClick = { region = c }, label = { Text(REGIONS[c] ?: c) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dates.take(14).forEachIndexed { i, d -> FilterChip(selected = dateIdx == i, onClick = { dateIdx = i }, label = { Text(d) }) }
            }
        } else {
            Text("No data yet. Add your key and tap Save and refresh now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { t ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text("#${t.rank}  ${t.title}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${t.channel} · ${fmtViews(t.views)} views", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp)) {
                    Pill("Open on YouTube") {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${t.id}")))
                        } catch (e: Exception) { }
                    }
                }
            }
        }
    }
}

private fun fmtViews(v: Long): String = when {
    v >= 1_000_000_000 -> "%.1fB".format(v / 1e9)
    v >= 1_000_000 -> "%.1fM".format(v / 1e6)
    v >= 1_000 -> "%.1fK".format(v / 1e3)
    else -> "$v"
}
