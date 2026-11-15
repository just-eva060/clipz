package com.swiftget.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Everything you have downloaded, sorted into folders by type. */
@Composable
fun LibraryScreen(list: List<DownloadUi>, onView: (DownloadUi) -> Unit, onHide: (DownloadUi) -> Unit) {
    val done = list.filter { it.status == Status.COMPLETED }
    var folder by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = folder != null) { folder = null }

    val open = folder
    if (open == null) {
        val totalSize = done.sumOf { if (it.total > 0) it.total else it.downloaded }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${done.size} files · ${fmtBytes(totalSize)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(CATEGORIES) { cat ->
                val files = done.filter { it.category == cat }
                val size = files.sumOf { if (it.total > 0) it.total else it.downloaded }
                GlassCard(
                    Modifier.fillMaxWidth().alpha(if (files.isEmpty()) 0.55f else 1f).clickable { folder = cat },
                ) {
                    TypeBadge(cat, 46.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(cat, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${files.size} ${if (files.size == 1) "file" else "files"} · ${fmtBytes(size)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    } else {
        val files = done.filter { it.category == open }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundAction("←") { folder = null }
                    Column {
                        Text("${catEmoji(open)} $open", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "${files.size} ${if (files.size == 1) "file" else "files"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (files.isEmpty()) {
                item {
                    Text(
                        "Nothing in this folder yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            items(files, key = { it.id }) { DownloadCard(it, onView, onHide) }
        }
    }
}
