package com.swiftget.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The "More" tab: everything beyond downloading, so the bottom bar stays uncluttered. */
@Composable
fun MoreScreen(
    sub: String?,
    onOpen: (String?) -> Unit,
    onSettings: () -> Unit,
    list: List<DownloadUi>,
    onAddCoolTile: () -> Unit,
) {
    when (sub) {
        "vault" -> VaultScreen()
        "games" -> GamesScreen(list)
        "torrents" -> TorrentsScreen(onBack = { onOpen(null) })
        "compress" -> CompressScreen(onBack = { onOpen(null) })
        "studio" -> ClipStudioScreen(onBack = { onOpen(null) })
        "rights" -> RightsScreen(onBack = { onOpen(null) })
        "trends" -> TrendsScreen(onBack = { onOpen(null) })
        "cool" -> CoolScreen(onBack = { onOpen(null) }, onAddTile = onAddCoolTile)
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            MoreTile("✂️", "Clip studio", "Auto-clip highlights and beat-synced edits") { onOpen("studio") }
            MoreTile("📈", "Trending", "Daily most-popular per country (official API)") { onOpen("trends") }
            MoreTile("📜", "Rights ledger", "Who allows you to clip their content") { onOpen("rights") }
            MoreTile("🧲", "Torrents", "Magnet links and .torrent files") { onOpen("torrents") }
            MoreTile("🗜️", "Compress", "Make photos and videos smaller") { onOpen("compress") }
            MoreTile("❄️", "Cool down", "Phone temperature and a Quick Settings tile") { onOpen("cool") }
            MoreTile("🔒", "Vault", "Hide files behind a PIN") { onOpen("vault") }
            MoreTile("🎮", "Games", "Offline mini games and your game files") { onOpen("games") }
            MoreTile("⚙", "Setup", "Speed, battery, notifications, screen capture") { onSettings() }
        }
    }
}

@Composable
private fun MoreTile(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
