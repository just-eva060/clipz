package com.swiftget.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class GameInfo(val key: String, val emoji: String, val title: String, val blurb: String, val bestKey: String)

private val GAMES = listOf(
    GameInfo("snake", "🐍", "Snake", "Eat, grow, don't crash. Speeds up as you score.", "snake_best"),
    GameInfo("2048", "🔢", "2048", "Slide and join tiles to reach 2048.", "g2048_best"),
    GameInfo("memory", "🧠", "Memory match", "Flip cards and find every pair in the fewest moves.", "memory_best"),
)

/** Offline mini games, and a place for the game files (APKs, bundles) you download. */
@Composable
fun GamesScreen(list: List<DownloadUi>) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = playing != null) { playing = null }
    val current = GAMES.firstOrNull { it.key == playing }

    if (current != null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundAction("←") { playing = null }
                Text("${current.emoji} ${current.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            when (current.key) {
                "snake" -> SnakeGame()
                "2048" -> Game2048()
                else -> MemoryGame()
            }
        }
    } else {
        val files = list.filter { it.status == Status.COMPLETED && (it.category == "Apps" || it.category == "Games") }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Games", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Play offline. No internet needed.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            GAMES.forEach { g ->
                val best = gameBest(ctx, g.bestKey)
                GlassCard(Modifier.fillMaxWidth().clickable { playing = g.key }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(g.emoji, style = MaterialTheme.typography.headlineMedium) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(g.blurb, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            if (best > 0) {
                                Text(
                                    if (g.key == "memory") "Best: $best moves" else "Best: $best",
                                    color = Mint, style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Pill("Play", accent = true) { playing = g.key }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Game files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Apps you downloaded appear here. Tap Mark as game for the ones that are games. " +
                    "Plain .apk files install directly. Bundles (.xapk, .apks) and .obb data need a bundle installer app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            if (files.isEmpty()) {
                Text(
                    "No game files yet. Download an .apk from the Browser tab or paste its link, and it will show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            files.forEach { d -> GameFileCard(d) }
        }
    }
}

@Composable
private fun GameFileCard(d: DownloadUi) {
    val ctx = LocalContext.current
    val isGame = d.folder == "Games"
    val isApk = d.name.lowercase().endsWith(".apk")
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(if (isGame) "Games" else "Apps", 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    "${fmtBytes(d.total.takeIf { it > 0 } ?: d.downloaded)} · ${if (isGame) "Game" else "App"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isApk) Pill("📲 Install", accent = true) { installApk(ctx, d) }
            Pill("Open with…") { openWith(ctx, d) }
            Pill(if (isGame) "Unmark game" else "🎮 Mark as game") {
                Downloader.setFolder(d.id, if (isGame) null else "Games")
            }
        }
    }
}

/** Hands the APK to Android's installer. Android asks you to allow Clipz to install apps the first time. */
private fun installApk(ctx: android.content.Context, d: DownloadUi) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(ctx, "Allow Clipz to install apps, then tap Install again", Toast.LENGTH_LONG).show()
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Open Settings > Apps > Clipz > Install unknown apps", Toast.LENGTH_LONG).show()
        }
        return
    }
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(d.savedUri), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Couldn't start the installer. Open the file from your Files app instead.", Toast.LENGTH_LONG).show()
    }
}
