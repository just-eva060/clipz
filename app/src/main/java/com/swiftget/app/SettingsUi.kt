package com.swiftget.app

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
private fun Section(title: String, body: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        content()
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAskNotifications: () -> Unit,
    onAddTile: () -> Unit,
    onScreenshot: () -> Unit,
    onRecord: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    var mic by remember { mutableStateOf(false) }
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    var conn by remember { mutableIntStateOf(Downloader.maxConnections) }
    var parallel by remember { mutableIntStateOf(Downloader.maxParallel) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        Section(
            "Downloads at once",
            "There is no limit on how many downloads you can queue or how big they are. Choose how many run at the same time; the rest wait their turn.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5, 0).forEach { n ->
                    FilterChip(
                        selected = parallel == n,
                        onClick = { parallel = n; Downloader.maxParallel = n },
                        label = { Text(if (n == 0) "All" else "$n") },
                    )
                }
            }
        }

        Section(
            "Connections per download",
            "More connections are faster on a good link. Fewer can be steadier on a very weak one.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 8, 16).forEach { n ->
                    FilterChip(selected = conn == n, onClick = { conn = n; Downloader.setMaxConnections(n) }, label = { Text("$n") })
                }
            }
        }

        Section(
            "Quick Settings switch",
            "Add the Clipz tile to your pull-down panel. Lit means downloading; tap to pause everything, tap again to resume.",
        ) { Pill("Add tile", accent = true, onClick = onAddTile) }

        Section(
            "Keep downloads alive",
            "Allow notifications so Android lets Clipz run in the background, and exempt it from battery optimisation so it is not stopped while you sleep.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Allow notifications", onClick = onAskNotifications)
                Pill(if (unrestricted) "Battery: unrestricted ✓" else "Allow unrestricted battery use", accent = !unrestricted) {
                    if (!unrestricted) {
                        battery.launch(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        )
                    }
                }
            }
        }

        Section(
            "Capture your screen",
            "Take a screenshot or record whatever is on screen. Android asks you to approve each capture. Apps that block capture, and DRM video, appear black: that is Android's own protection.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mic, onClick = { mic = !mic }, label = { Text("Record microphone too") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Screenshot", accent = true, onClick = onScreenshot)
                    Pill("Record screen", accent = true) { onRecord(mic) }
                }
            }
        }

        Section(
            "Send downloads to Clipz",
            "Share: in any browser or app, tap Share, then Clipz.\nCopy: copy a link, open Clipz, tap Download on the banner.\nBrowser tab: file downloads and detected media on any site.\nOpen with: links to zip, apk, pdf, mp3, mp4 and similar files.",
        ) { Pill("Resume all paused downloads") { Downloader.resumeAll() } }
    }
}
