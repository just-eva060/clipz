package com.swiftget.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

@Composable
fun ClipStudioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rights by Rights.list.collectAsState()
    val clips by Clips.list.collectAsState()
    val cleared = rights.filter { Rights.cleared(it) }

    var mode by remember { mutableIntStateOf(0) } // 0 quick clips, 1 beat edit, 2 my clips
    var src by remember { mutableStateOf<Uri?>(null) }
    var srcRight by remember { mutableStateOf<String?>(null) }
    var srcCredit by remember { mutableStateOf("") }
    var srcOk by remember { mutableStateOf(false) }
    var music by remember { mutableStateOf<Uri?>(null) }
    var musicRight by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Archive.Item>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("General") }
    var vertical by remember { mutableStateOf(true) }
    var moments by remember { mutableStateOf<List<Moment>>(emptyList()) }
    var srcEnergy by remember { mutableStateOf<Energy?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var lengthSec by remember { mutableIntStateOf(30) }
    var pace by remember { mutableIntStateOf(1) } // beats per cut: 1 fast, 2 medium, 4 slow
    var job by remember { mutableStateOf<Job?>(null) }

    val pickSrc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { src = u; srcRight = null; srcOk = false; srcCredit = ""; moments = emptyList(); srcEnergy = null; message = null }
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { music = u; musicRight = null; message = null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Clip studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Quick clips", "Beat edit", "My clips").forEachIndexed { i, l ->
                FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(l) })
            }
        }

        if (mode < 2) {
            // ---- source
            GlassCard(Modifier.fillMaxWidth()) {
                Text("1. Footage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    src?.let { if (it.scheme == "http" || it.scheme == "https") "From Internet Archive" else "Local file chosen" } ?: "Nothing chosen",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Choose a video", accent = true) { pickSrc.launch(arrayOf("video/*")) }
                }
                if (src != null && src?.scheme != "https" && src?.scheme != "http") {
                    Text("Who allows you to clip this?", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cleared.forEach { r ->
                            FilterChip(
                                selected = srcRight == r.name,
                                onClick = { srcRight = r.name; srcOk = true; srcCredit = if (r.note.isNotBlank()) r.note else r.name },
                                label = { Text(r.name) },
                            )
                        }
                    }
                    if (cleared.size <= 1) Text("Add the creator in the Rights ledger first (More > Rights ledger).", style = MaterialTheme.typography.bodySmall, color = Coral)
                }
                Text("Or search free, openly licensed footage", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("e.g. space, ocean, city") })
                Row(Modifier.padding(top = 8.dp)) {
                    Pill(if (searching) "Searching…" else "Search Internet Archive") {
                        if (!searching && query.isNotBlank()) { searching = true; scope.launch { results = Archive.search(query); searching = false } }
                    }
                }
                results.forEach { r ->
                    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(r.license + if (r.nonCommercial) " · non-commercial use only" else "", style = MaterialTheme.typography.bodySmall,
                            color = if (r.nonCommercial) Gold else Mint)
                        Row(Modifier.padding(top = 6.dp)) {
                            Pill("Use this", accent = true) {
                                scope.launch {
                                    val u = Archive.videoUrl(r.id)
                                    if (u == null) toast(ctx, "No MP4 found in that item")
                                    else {
                                        src = Uri.parse(u); srcRight = "Internet Archive: ${r.license}"; srcOk = true
                                        srcCredit = r.creditLine; moments = emptyList(); srcEnergy = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("2. Topic folder and format", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text("Topic or creator folder") })
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vertical, onClick = { vertical = true }, label = { Text("Vertical 9:16") })
                    FilterChip(selected = !vertical, onClick = { vertical = false }, label = { Text("Original shape") })
                }
            }
        }

        if (mode == 0) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("3. Find the best moments", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Measures the loudest, most active parts of the audio and suggests clips. It judges sound, not meaning, so skip any that don't work. " +
                        "For web footage this streams the file, which uses data.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                val u = src
                if (u != null && busy == null) {
                    Pill("Analyse", accent = true) {
                        busy = "Analysing"; progress = 0; message = null
                        job = scope.launch {
                            val e = Analyzer.energy(ctx, u) { progress = it }
                            srcEnergy = e
                            moments = if (e == null) emptyList() else Analyzer.moments(e, 30, 5)
                            if (moments.isEmpty()) message = "Couldn't find usable audio in that video."
                            busy = null
                        }
                    }
                }
                moments.forEachIndexed { i, m ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${mmss(m.startMs)} – ${mmss(m.endMs)}", fontWeight = FontWeight.SemiBold)
                        Text("energy ${(m.score * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Pill("Clip it", accent = true) {
                            val s = src ?: return@Pill
                            if (!srcOk) { toast(ctx, "Choose who allows you to clip this first"); return@Pill }
                            busy = "Clipping"; progress = 0; message = null
                            job = scope.launch {
                                val r = Clipper.clip(ctx, s, m.startMs, m.endMs, vertical, topic.ifBlank { "General" }, srcRight ?: "", srcCredit) { progress = it }
                                message = if (r != null) "Saved to Movies/Clipz/${topic.ifBlank { "General" }}. See My clips." else "That clip didn't export."
                                busy = null
                            }
                        }
                    }
                }
            }
        }

        if (mode == 1) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("3. Music", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Music is usually the copyrighted part. Use a track you own, made, or are licensed to use, and record that in the Rights ledger.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text(if (music != null) "Track chosen" else "No track chosen")
                Row(Modifier.padding(top = 8.dp)) { Pill("Choose a track", accent = true) { pickMusic.launch(arrayOf("audio/*", "video/*")) } }
                if (music != null) {
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cleared.forEach { r -> FilterChip(selected = musicRight == r.name, onClick = { musicRight = r.name }, label = { Text(r.name) }) }
                    }
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("4. Style", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Length", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60).forEach { s -> FilterChip(selected = lengthSec == s, onClick = { lengthSec = s }, label = { Text("${s}s") }) }
                }
                Text("Cut speed", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Every beat", 2 to "Every 2 beats", 4 to "Every 4 beats").forEach { (n, l) ->
                        FilterChip(selected = pace == n, onClick = { pace = n }, label = { Text(l) })
                    }
                }
                val s = src
                val m = music
                if (s != null && m != null && busy == null) {
                    Row(Modifier.padding(top = 10.dp)) {
                        Pill("Make the edit", accent = true) {
                            if (!srcOk) { toast(ctx, "Choose who allows you to use the footage"); return@Pill }
                            if (musicRight == null) { toast(ctx, "Choose who allows you to use the music"); return@Pill }
                            message = null; progress = 0
                            job = scope.launch {
                                busy = "Listening to the music"
                                val me = Analyzer.energy(ctx, m) { progress = it }
                                if (me == null) { message = "Couldn't read audio from that track."; busy = null; return@launch }
                                val win = Analyzer.moments(me, lengthSec, 1).firstOrNull()
                                    ?: run { message = "That track is too quiet or too short."; busy = null; return@launch }
                                val beats = Analyzer.beats(me, win.startMs, win.endMs)
                                if (beats.size < 6) { message = "Couldn't find a steady beat in that track."; busy = null; return@launch }
                                busy = "Studying the footage"; progress = 0
                                val se = srcEnergy ?: Analyzer.energy(ctx, s) { progress = it }
                                srcEnergy = se
                                if (se == null) { message = "Couldn't read audio from the footage, which the editor uses to find action."; busy = null; return@launch }
                                val cuts = Planner.cuts(se, beats, win.startMs, win.endMs - win.startMs, pace)
                                if (cuts.size < 3) { message = "The footage is too short for that style. Try a longer video or slower cuts."; busy = null; return@launch }
                                busy = "Editing ${cuts.size} cuts"; progress = 0
                                val credit = listOf(srcCredit, "Music: $musicRight").filter { it.isNotBlank() }.joinToString(" | ")
                                val r = Clipper.beatEdit(ctx, s, m, win.startMs, cuts, vertical, topic.ifBlank { "General" }, srcRight ?: "", credit) { progress = it }
                                message = if (r != null) "Done: ${cuts.size} cuts on the beat. See My clips." else "The edit didn't export. Try fewer or slower cuts."
                                busy = null
                            }
                        }
                    }
                } else if (busy == null) {
                    Text("Choose footage and a track first.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Text(
                "How it edits: it finds the tempo of your track, cuts the footage on the beat, picks the most active parts of the footage, " +
                    "and adds a zoom punch on alternate cuts. It's an automatic assistant, not a replacement for an editor's taste, so expect to try a few settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        busy?.let {
            GradientBar(progress / 100f, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$it… $progress%")
                Pill("Cancel", danger = true) { job?.cancel(); busy = null }
            }
        }
        message?.let { Text(it, color = Mint, fontWeight = FontWeight.SemiBold) }

        if (mode == 2) {
            if (clips.isEmpty()) Text("No clips yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            clips.groupBy { it.topic }.forEach { (t, list) ->
                Text("📁 $t", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                list.forEach { c ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        if (c.credit.isNotBlank()) Text("Credit: ${c.credit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Play", accent = true) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } catch (e: Exception) { toast(ctx, "No video player found") }
                            }
                            Pill("Share") {
                                try {
                                    ctx.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(c.uri))
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip",
                                        ),
                                    )
                                } catch (e: Exception) { }
                            }
                            if (c.credit.isNotBlank()) Pill("Copy credit") {
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("credit", c.credit))
                                toast(ctx, "Credit copied. Paste it in your post caption.")
                            }
                            Pill("Remove from list", danger = true) { Clips.remove(c) }
                        }
                    }
                }
            }
        }
    }
}
