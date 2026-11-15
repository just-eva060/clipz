@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.swiftget.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.common.Effect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Loudness over time, 50 values per second. Everything (highlights, beats) is derived from this. */
class Energy(val values: FloatArray, val durationMs: Long) {
    val fps get() = Analyzer.FPS
}

data class Moment(val startMs: Long, val endMs: Long, val score: Float)

object Analyzer {
    const val FPS = 50

    /** Decodes the audio of a local file or an http(s) URL and measures loudness. Returns null if there is no audio. */
    suspend fun energy(ctx: Context, uri: Uri, onProgress: (Int) -> Unit): Energy? = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "http" || uri.scheme == "https") ex.setDataSource(uri.toString()) else ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) return@withContext null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            if (!fmt.containsKey(MediaFormat.KEY_DURATION)) return@withContext null
            val durUs = fmt.getLong(MediaFormat.KEY_DURATION)
            val frames = (durUs / 20_000).toInt() + 10
            val sumsq = DoubleArray(frames)
            val cnt = IntArray(frames)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var lastPct = -1
            var loops = 0
            while (!outDone) {
                if (++loops % 50 == 0) currentCoroutineContext().ensureActive()
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            val t = ex.sampleTime
                            c.queueInputBuffer(ii, 0, n, t, 0)
                            val pct = (t * 100 / max(1L, durUs)).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct.coerceIn(0, 100)) }
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        var k = 0
                        val stride = channels * 4
                        while (k < total) {
                            val frameIdx = k / channels
                            val t = info.presentationTimeUs + frameIdx * 1_000_000L / rate
                            val b = (t / 20_000).toInt()
                            if (b in 0 until frames) {
                                val v = sb.get(k).toDouble()
                                sumsq[b] += v * v; cnt[b]++
                            }
                            k += stride
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            val out = FloatArray(frames) { if (cnt[it] > 0) (sqrt(sumsq[it] / cnt[it]) / 32768.0).toFloat() else 0f }
            Energy(out, durUs / 1000)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }

    /** The loudest, most active stretches, best first. Not overlapping. */
    fun moments(e: Energy, windowSec: Int, count: Int): List<Moment> {
        val w = windowSec * FPS
        val n = e.values.size
        if (n < w) return listOf(Moment(0, e.durationMs, 1f)).filter { e.durationMs > 0 }
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + e.values[i]
        val step = 5 * FPS
        val cands = ArrayList<Pair<Int, Double>>()
        var s = 0
        while (s + w <= n) { cands.add(s to (prefix[s + w] - prefix[s]) / w); s += step }
        val best = cands.maxOfOrNull { it.second } ?: return emptyList()
        if (best < 0.003) return emptyList()
        val picked = ArrayList<Pair<Int, Double>>()
        for (c in cands.sortedByDescending { it.second }) {
            if (picked.size >= count) break
            if (picked.none { kotlin.math.abs(it.first - c.first) < w }) picked.add(c)
        }
        return picked.sortedBy { it.first }.map { Moment(it.first * 20L, (it.first + w) * 20L, (it.second / best).toFloat()) }
    }

    /** Estimates the tempo and returns beat times in ms. A steady-tempo estimate; it can be off on tempo changes. */
    fun beats(e: Energy, fromMs: Long, toMs: Long): List<Long> {
        val n = min(e.values.size, (toMs / 20).toInt() + 1)
        val start = (fromMs / 20).toInt().coerceAtLeast(1)
        if (n - start < 150) return emptyList()
        val onset = FloatArray(n)
        for (i in start until n) {
            val a = ln(1.0 + e.values[i] * 200.0)
            val b = ln(1.0 + e.values[i - 1] * 200.0)
            onset[i] = max(0.0, a - b).toFloat()
        }
        fun at(x: Double): Double {
            val i = x.toInt()
            if (i < start || i + 1 >= n) return 0.0
            val f = x - i
            return onset[i] * (1 - f) + onset[i + 1] * f
        }
        val limit = min(n, start + 90 * FPS)
        var bestLag = 25.0
        var bestScore = -1.0
        var lag = 16.0
        while (lag <= 50.0) {
            var s = 0.0
            for (i in start until limit - 50) s += onset[i] * at(i + lag)
            // gentle preference for common dance tempos (about 90-150 BPM)
            val bpm = 3000.0 / lag
            s *= 1.0 - 0.15 * kotlin.math.abs(bpm - 120.0) / 60.0
            if (s > bestScore) { bestScore = s; bestLag = lag }
            lag += 0.1
        }
        var bestPhase = 0.0
        var bestPhaseScore = -1.0
        var p = 0.0
        while (p < bestLag) {
            var s = 0.0
            var x = start + p
            while (x < limit) { s += at(x); x += bestLag }
            if (s > bestPhaseScore) { bestPhaseScore = s; bestPhase = p }
            p += 0.5
        }
        val out = ArrayList<Long>()
        var x = start + bestPhase
        while (x < n) { out.add((x * 20).toLong()); x += bestLag }
        return out
    }
}

/** A planned cut: take [durMs] of the source starting at [startMs]. */
data class Cut(val startMs: Long, val durMs: Long)

object Planner {
    /**
     * Builds the cut list: one cut every [beatsPerCut] beats inside the music window, each taken from the
     * most energetic parts of the source, kept in source order so the footage still flows.
     */
    fun cuts(src: Energy, beats: List<Long>, windowStartMs: Long, windowMs: Long, beatsPerCut: Int): List<Cut> {
        val cutTimes = ArrayList<Long>()
        cutTimes.add(0L)
        beats.filter { it > windowStartMs && it < windowStartMs + windowMs }.forEachIndexed { i, t ->
            if (i % beatsPerCut == 0) {
                val rel = t - windowStartMs
                if (rel - cutTimes.last() >= 350) cutTimes.add(rel)
            }
        }
        cutTimes.add(windowMs)
        val lens = (0 until cutTimes.size - 1).map { cutTimes[it + 1] - cutTimes[it] }.filter { it > 200 }
        if (lens.isEmpty()) return emptyList()
        val maxLen = lens.max()
        // candidate starts: 1-second steps ranked by energy, spaced apart
        val dur = src.durationMs
        val stepMs = 1000L
        val cand = ArrayList<Pair<Long, Double>>()
        var t = 0L
        while (t + maxLen <= dur) {
            val a = (t / 20).toInt()
            val b = min(src.values.size, ((t + maxLen) / 20).toInt())
            var s = 0.0
            for (i in a until b) s += src.values[i]
            cand.add(t to s / max(1, b - a))
            t += stepMs
        }
        if (cand.isEmpty()) return emptyList()
        val chosen = ArrayList<Long>()
        val need = lens.size
        for (c in cand.sortedByDescending { it.second }) {
            if (chosen.size >= need) break
            if (chosen.none { kotlin.math.abs(it - c.first) < maxLen }) chosen.add(c.first)
        }
        // not enough distinct spots in a short source: fill from the rest of the timeline
        var fill = 0
        while (chosen.size < need) { chosen.add(cand[fill % cand.size].first); fill += 3 }
        chosen.sort()
        return lens.mapIndexed { i, l -> Cut(min(chosen[i], max(0L, dur - l)), l) }
    }
}

object Clipper {
    /** Runs a Transformer export and waits for it. [begin] must call transformer.start(...). */
    private suspend fun run(ctx: Context, bitrate: Int, onProgress: (Int) -> Unit, begin: (Transformer) -> Unit): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                try {
                    val enc = DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                    val tr = Transformer.Builder(ctx)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(enc)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(true) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(false) }
                        })
                        .build()
                    begin(tr)
                    val holder = ProgressHolder()
                    val tick = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            if (tr.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress)
                            handler.postDelayed(this, 500)
                        }
                    }
                    handler.post(tick)
                    cont.invokeOnCancellation { handler.post { tr.cancel() } }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    private fun fx(vertical: Boolean, height: Int, zoom: Float?): List<Effect> {
        val l = ArrayList<Effect>()
        if (vertical) l.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        l.add(Presentation.createForHeight(height))
        if (zoom != null) l.add(ScaleAndRotateTransformation.Builder().setScale(zoom, zoom).build())
        return l
    }

    private suspend fun finish(ctx: Context, out: File, topic: String, source: String, credit: String, label: String): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val saved = out.inputStream().use {
                    Downloader.saveToLibrary("${label}_${System.currentTimeMillis()}.mp4", "video/mp4", it, "Topic:$topic")
                }
                Clips.add(ClipRec("${label}_${System.currentTimeMillis()}.mp4", saved.toString(), topic, source, credit, System.currentTimeMillis()))
                saved
            } catch (e: Exception) { null } finally { out.delete() }
        }

    /** One straight clip, optionally reframed to 9:16. */
    suspend fun clip(ctx: Context, src: Uri, startMs: Long, endMs: Long, vertical: Boolean, topic: String,
                     source: String, credit: String, onProgress: (Int) -> Unit): Uri? {
        val out = File(ctx.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val ok = run(ctx, 3_000_000, onProgress) { tr ->
            val item = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(src).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
                ).build(),
            ).setEffects(Effects(emptyList(), fx(vertical, if (vertical) 1280 else 720, null))).build()
            tr.start(item, out.absolutePath)
        }
        if (!ok) { out.delete(); return null }
        return finish(ctx, out, topic, source, credit, "clip")
    }

    /** Beat-synced edit: cuts of [src] on the beat of [music], with a zoom punch on every other cut. */
    suspend fun beatEdit(ctx: Context, src: Uri, music: Uri, musicStartMs: Long, cuts: List<Cut>, vertical: Boolean,
                         topic: String, source: String, credit: String, onProgress: (Int) -> Unit): Uri? {
        val out = File(ctx.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
        val total = cuts.sumOf { it.durMs }
        val ok = run(ctx, 4_000_000, onProgress) { tr ->
            val items = cuts.mapIndexed { i, c ->
                EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(src).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.startMs + c.durMs).build(),
                    ).build(),
                ).setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), fx(vertical, if (vertical) 1280 else 720, if (i % 2 == 1) 1.12f else null)))
                    .build()
            }
            val musicItem = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(music).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(musicStartMs).setEndPositionMs(musicStartMs + total).build(),
                ).build(),
            ).setRemoveVideo(true).build()
            val comp = Composition.Builder(listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(listOf(musicItem)))).build()
            tr.start(comp, out.absolutePath)
        }
        if (!ok) { out.delete(); return null }
        return finish(ctx, out, topic, source, credit, "edit")
    }
}

data class ClipRec(val name: String, val uri: String, val topic: String, val source: String, val credit: String, val time: Long)

/** Every clip made, grouped by topic in the studio. */
object Clips {
    private var prefs: android.content.SharedPreferences? = null
    val list = kotlinx.coroutines.flow.MutableStateFlow<List<ClipRec>>(emptyList())

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("clips", Context.MODE_PRIVATE)
        val out = ArrayList<ClipRec>()
        try {
            val arr = JSONArray(prefs!!.getString("json", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ClipRec(o.getString("name"), o.getString("uri"), o.getString("topic"), o.optString("source"), o.optString("credit"), o.optLong("time")))
            }
        } catch (e: Exception) { }
        list.value = out
    }

    fun add(r: ClipRec) {
        list.value = listOf(r) + list.value
        persist()
    }

    fun remove(r: ClipRec) {
        list.value = list.value.filter { it.uri != r.uri }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        list.value.forEach {
            arr.put(JSONObject().put("name", it.name).put("uri", it.uri).put("topic", it.topic).put("source", it.source).put("credit", it.credit).put("time", it.time))
        }
        prefs?.edit()?.putString("json", arr.toString())?.apply()
    }
}

/** Internet Archive: open search and direct file URLs, so footage can be analysed and clipped without saving all of it first. */
object Archive {
    data class Item(val id: String, val title: String, val license: String, val creditLine: String, val nonCommercial: Boolean)

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun licenseLabel(url: String): String {
        val m = Regex("creativecommons.org/(licenses|publicdomain)/([a-z-]+)/?([0-9.]*)").find(url) ?: return url
        return if (m.groupValues[1] == "publicdomain") "Public domain (CC0)" else "CC " + m.groupValues[2].uppercase() + " " + m.groupValues[3]
    }

    /** Only items with a Creative Commons license that allows edits, or from public-domain collections. */
    suspend fun search(text: String): List<Item> = withContext(Dispatchers.IO) {
        try {
            val q = "(" + text.replace("\"", " ") + ") AND mediatype:(movies) AND " +
                "(licenseurl:(*creativecommons.org*) OR collection:(prelinger) OR collection:(nasa))"
            val url = "https://archive.org/advancedsearch.php?q=" + Uri.encode(q) +
                "&fl[]=identifier&fl[]=title&fl[]=licenseurl&fl[]=creator&rows=30&output=json"
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val docs = JSONObject(resp.body?.string() ?: "").getJSONObject("response").getJSONArray("docs")
                val out = ArrayList<Item>()
                for (i in 0 until docs.length()) {
                    val d = docs.getJSONObject(i)
                    val id = d.optString("identifier")
                    val lic = d.optString("licenseurl")
                    if (lic.contains("-nd")) continue // "no derivatives" forbids editing
                    val title = d.optString("title")
                    val creator = d.opt("creator")?.let { if (it is JSONArray) it.optString(0) else it.toString() } ?: ""
                    val label = if (lic.isBlank()) "Public-domain collection (check the item page)" else licenseLabel(lic)
                    out.add(Item(id, title, label, "$title" + (if (creator.isNotBlank()) " by $creator" else "") +
                        " - https://archive.org/details/$id - $label", lic.contains("-nc")))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The smallest .mp4 in the item, as a direct URL. */
    suspend fun videoUrl(id: String): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("https://archive.org/metadata/$id").build()).execute().use { resp ->
                val files = JSONObject(resp.body?.string() ?: "").getJSONArray("files")
                var best: Pair<String, Long>? = null
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val name = f.optString("name")
                    if (!name.lowercase().endsWith(".mp4")) continue
                    val size = f.optString("size").toLongOrNull() ?: continue
                    if (best == null || size < best!!.second) best = name to size
                }
                best?.let { "https://archive.org/download/$id/" + it.first.split("/").joinToString("/") { p -> Uri.encode(p) } }
            }
        } catch (e: Exception) {
            null
        }
    }
}
