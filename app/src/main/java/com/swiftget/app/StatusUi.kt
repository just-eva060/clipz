package com.swiftget.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WhatsApp status saver: browse the statuses you have viewed and save the ones you want. */
@Composable
fun StatusScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(StatusSaver.SOURCES.first()) }
    var connectTick by remember { mutableIntStateOf(0) }
    var savedTick by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<StatusFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<StatusFile?>(null) }

    val connected = remember(source, connectTick) { StatusSaver.isConnected(ctx, source.key) }
    val saved = remember(savedTick, files) { StatusSaver.savedNames(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            StatusSaver.connect(ctx, source.key, uri)
            connectTick++
        }
    }

    LaunchedEffect(source, connectTick) {
        if (StatusSaver.isConnected(ctx, source.key)) {
            loading = true
            files = withContext(Dispatchers.IO) {
                try {
                    StatusSaver.list(ctx, source.key)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            loading = false
        } else {
            files = emptyList()
        }
    }

    val shown = when (filter) {
        1 -> files.filter { !it.isVideo }
        2 -> files.filter { it.isVideo }
        else -> files
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Status saver", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusSaver.SOURCES.forEach { s ->
                        FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) })
                    }
                }
            }
        }

        if (!connected) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("💬 Connect ${source.label}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. Open ${source.label} and view the statuses you want to keep.\n" +
                            "2. Tap Connect below. Android will open the hidden Statuses folder.\n" +
                            "3. Tap \"Use this folder\", then Allow.\n\n" +
                            "Clipz can only read that one folder, and only because you chose it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Pill("Connect ${source.label}", accent = true) { picker.launch(StatusSaver.hint(source)) }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("All", "Photos", "Videos").forEachIndexed { i, label ->
                            FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("⬇ Save all new", accent = true) {
                            val todo = shown.filter { it.name !in saved }
                            if (todo.isEmpty()) {
                                Toast.makeText(ctx, "Everything here is already saved", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { todo.count { StatusSaver.save(ctx, it) } }
                                    savedTick++
                                    Toast.makeText(ctx, "Saved $ok of ${todo.size}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        Pill("↻ Refresh") { connectTick++ }
                        Pill("Disconnect", danger = true) {
                            StatusSaver.disconnect(ctx, source.key)
                            connectTick++
                        }
                    }
                }
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No statuses found. Open ${source.label}, view some statuses, then tap Refresh.\n" +
                            "If that doesn't work, Disconnect and connect again, choosing the folder named .Statuses.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.uri.toString() }) { f ->
                val isSaved = f.name in saved
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).clickable { viewing = f },
                ) {
                    Thumb(f.uri, f.isVideo, Modifier.fillMaxSize()) { TypeBadge("Status", 48.dp) }
                    if (f.isVideo) {
                        Box(
                            Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("▶", color = Color.White, fontSize = 14.sp) }
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(34.dp).clip(CircleShape)
                            .background(if (isSaved) Mint else Color.Black.copy(alpha = 0.6f))
                            .clickable {
                                if (!isSaved) {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { StatusSaver.save(ctx, f) }
                                        savedTick++
                                        Toast.makeText(ctx, if (ok) "Saved to Library › Status" else "Couldn't save this one", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (isSaved) "✓" else "⬇", color = if (isSaved) Color(0xFF00382A) else Color.White, fontSize = 15.sp) }
                }
            }
        }
    }

    viewing?.let { MediaViewer(it.uri, it.mime, it.name) { viewing = null } }
}
