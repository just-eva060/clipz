package com.swiftget.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LINK_REGEX = Regex("https?://[^\\s\"'<>]+")

fun clipboardUrl(ctx: Context): String? = try {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    text?.let { LINK_REGEX.find(it)?.value }
} catch (e: Exception) {
    null
}

@Composable
fun DownloadsScreen(
    list: List<DownloadUi>,
    clipUrl: String?,
    onClipAccept: () -> Unit,
    onClipDismiss: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
    onView: (DownloadUi) -> Unit,
    onHide: (DownloadUi) -> Unit,
) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val inProgress = list.filter { it.status != Status.COMPLETED }
    val done = list.filter { it.status == Status.COMPLETED }
    val activeCount = list.count { it.active }
    val speed = list.filter { it.status == Status.DOWNLOADING }.sumOf { it.speed }
    val shown = when (filter) {
        1 -> inProgress
        2 -> done
        else -> list
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Hero(
                speed = speed, active = activeCount, queued = list.count { it.status == Status.QUEUED },
                done = done.size, onSettings = onSettings, onBrowse = onBrowse,
                onPaste = {
                    val url = clipboardUrl(ctx)
                    if (url != null && Downloader.add(url) != null) {
                        Toast.makeText(ctx, "Added to Clipz", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "No link found on the clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        item {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, shape = RoundedCornerShape(50),
                placeholder = { Text("Paste a download link") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    Box(Modifier.padding(end = 6.dp)) {
                        Pill("Add", accent = true) {
                            if (Downloader.add(link) != null) {
                                link = ""
                            } else {
                                Toast.makeText(ctx, "Enter a link starting with http:// or https://", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
        }
        item {
            AnimatedVisibility(visible = clipUrl != null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("📋 Link on your clipboard", fontWeight = FontWeight.SemiBold)
                    Text(
                        clipUrl ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Download", accent = true, onClick = onClipAccept)
                        Pill("Dismiss", onClick = onClipDismiss)
                    }
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("All ${list.size}", "In progress ${inProgress.size}", "Done ${done.size}").forEachIndexed { i, label ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚡", fontSize = 40.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Nothing here yet", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Paste a link, share one to Clipz from any app, or browse in the Browser tab. " +
                                "Downloads continue in the background and resume after any interruption.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        items(shown, key = { it.id }) { DownloadCard(it, onView, onHide) }
    }
}

@Composable
private fun Hero(
    speed: Long, active: Int, queued: Int, done: Int,
    onSettings: () -> Unit, onBrowse: () -> Unit, onPaste: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(HeroGradient).padding(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active > 0) PulseDot()
                if (active > 0) Spacer(Modifier.width(8.dp))
                Text("Clipz", color = Color.White.copy(alpha = 0.92f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) { Text("⚙", color = Color.White, fontSize = 18.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    speed > 0 -> fmtBytes(speed) + "/s"
                    active > 0 -> "Connecting…"
                    else -> "Ready"
                },
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "$active active · $queued queued · $done saved",
                color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroButton("📋 Paste link", onPaste)
                HeroButton("🌐 Browse", onBrowse)
            }
        }
    }
}

@Composable
private fun HeroButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PulseDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a",
    )
    Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(Color(0xFF7CFFD9)))
}

/** One file: a row for any state (queued, downloading, paused, done), with the actions that make sense for it. */
@Composable
fun DownloadCard(d: DownloadUi, onView: (DownloadUi) -> Unit, onHide: (DownloadUi) -> Unit) {
    val ctx = LocalContext.current
    var askDelete by remember { mutableStateOf(false) }
    val cat = d.category
    GlassCard(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val uri = d.savedUri
            val visual = cat == "Videos" || cat == "Images" || cat == "Status"
            if (d.status == Status.COMPLETED && visual && uri != null) {
                Thumb(
                    Uri.parse(uri), video = d.mime.startsWith("video/"),
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).clickable { onView(d) },
                ) { TypeBadge(cat, 54.dp) }
            } else {
                TypeBadge(cat, 54.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    statusLine(d),
                    color = if (d.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (d.status) {
                Status.QUEUED, Status.DOWNLOADING, Status.WAITING_NETWORK -> RoundAction("⏸") { Downloader.pause(d.id) }
                Status.PAUSED, Status.FAILED -> RoundAction("▶", accent = true) { Downloader.resume(d.id) }
                Status.COMPLETED -> RoundAction("▶", accent = true) { onView(d) }
                Status.FINALIZING -> {}
            }
        }
        if (d.status != Status.COMPLETED && (d.progress != null || d.active)) {
            Spacer(Modifier.height(12.dp))
            GradientBar(d.progress)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.status == Status.COMPLETED) {
                Pill("Open with…") { openWith(ctx, d) }
                Pill("🔒 Hide") { onHide(d) }
                Pill("Clear from list") { Downloader.remove(d.id) }
                Pill("Delete file", danger = true) { askDelete = true }
            } else if (d.status != Status.FINALIZING) {
                Pill("Remove", danger = true) { Downloader.remove(d.id) }
            }
        }
    }
    if (askDelete) {
        ConfirmDialog(
            "Delete this file?", "\"${d.name}\" will be deleted from your phone.", "Delete",
            onConfirm = { Downloader.remove(d.id, deleteFile = true) }, onDismiss = { askDelete = false },
        )
    }
}

fun statusLine(d: DownloadUi): String = when (d.status) {
    Status.QUEUED -> "Queued · starts when a slot is free"
    Status.DOWNLOADING -> {
        val left = if (d.total > 0 && d.speed > 0) fmtEta((d.total - d.downloaded) / d.speed) else "--"
        val base = "${fmtBytes(d.downloaded)} of ${fmtBytes(d.total)} · ${fmtBytes(d.speed)}/s · $left left · ${d.connections} connections"
        if (d.error != null) "Reconnecting (${d.error}) · $base" else base
    }
    Status.WAITING_NETWORK -> "Waiting for network. Resumes automatically · ${fmtBytes(d.downloaded)} saved"
    Status.PAUSED -> "Paused · ${fmtBytes(d.downloaded)} of ${fmtBytes(d.total)}"
    Status.FINALIZING -> "Saving to your ${d.category} folder…"
    Status.COMPLETED -> "${fmtBytes(d.total.takeIf { it > 0 } ?: d.downloaded)} · ${d.category}"
    Status.FAILED -> "Failed: ${d.error ?: "unknown error"}"
}

fun openWith(ctx: Context, d: DownloadUi) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.savedUri), d.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
