package com.swiftget.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Cool-down mode. Be clear about what this is: software cannot cool a phone. Heat comes from the CPU,
 * radio and battery working hard. What this does is cut Clipz's own load: it pauses downloads and
 * torrents and drops to light settings, then restores your settings when you turn it off.
 */
object Cool {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cool", Context.MODE_PRIVATE)

    /** Battery temperature in °C, or null if the phone doesn't report it. */
    fun temp(ctx: Context): Float? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun autoOn(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto", v).apply()

    fun turnOn(ctx: Context) {
        val app = ctx.applicationContext
        Downloader.init(app)
        val p = prefs(app)
        if (!p.getBoolean("on", false)) {
            p.edit().putInt("old_parallel", Downloader.maxParallel).putInt("old_conn", Downloader.maxConnections).apply()
        }
        p.edit().putBoolean("on", true).apply()
        Downloader.pauseAll()
        Torrents.pauseAll()
        Downloader.maxParallel = 1
        Downloader.setMaxConnections(2)
    }

    fun turnOff(ctx: Context) {
        val app = ctx.applicationContext
        Downloader.init(app)
        val p = prefs(app)
        Downloader.maxParallel = p.getInt("old_parallel", 3)
        Downloader.setMaxConnections(p.getInt("old_conn", 8))
        p.edit().putBoolean("on", false).apply()
        Downloader.resumeAll()
        Torrents.resumeAll()
    }

    const val HOT = 42f

    /** Called every so often while work is running. Switches cool-down on if the battery is hot. */
    fun check(ctx: Context) {
        if (!autoOn(ctx) || isOn(ctx)) return
        val t = temp(ctx) ?: return
        if (t >= HOT) {
            turnOn(ctx)
            Notif.tapToResume(ctx, "Your phone reached %.0f°C, so Clipz paused to let it cool. Tap to resume.".format(t))
        }
    }
}

class CoolTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (Cool.isOn(this)) Cool.turnOff(this) else Cool.turnOn(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Cool.isOn(this)
        val t = Cool.temp(this)
        tile.label = "Cool down"
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            on -> "Cooling" + (t?.let { " · %.1f°C".format(it) } ?: "")
            t != null -> "%.1f°C".format(t)
            else -> "Off"
        }
        tile.updateTile()
    }
}

@Composable
fun CoolScreen(onBack: () -> Unit, onAddTile: () -> Unit) {
    val ctx = LocalContext.current
    var temp by remember { mutableStateOf(Cool.temp(ctx)) }
    var on by remember { mutableStateOf(Cool.isOn(ctx)) }
    var auto by remember { mutableStateOf(Cool.autoOn(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            temp = Cool.temp(ctx)
            on = Cool.isOn(ctx)
            delay(3000)
        }
    }
    val t = temp
    val hot = t != null && t >= Cool.HOT
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Cool down", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text(if (t != null) "%.1f °C".format(t) else "No reading", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                color = if (hot) Coral else Mint)
            Text(
                when {
                    t == null -> "This phone doesn't report battery temperature."
                    hot -> "Running hot. Pause heavy work and let it rest."
                    t >= 38f -> "Warm. Normal while charging or downloading."
                    else -> "Normal."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Cool-down mode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Pauses all downloads and torrents and keeps Clipz light. Turning it off restores your settings and resumes everything.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Pill(if (on) "Turn off and resume" else "Turn on", accent = true) {
                if (on) Cool.turnOff(ctx) else Cool.turnOn(ctx)
                on = Cool.isOn(ctx)
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Auto-cool", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "While Clipz is working, switch cool-down on by itself if the battery reaches ${Cool.HOT.toInt()} °C.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Pill(if (auto) "Auto-cool: on" else "Auto-cool: off", accent = auto) {
                auto = !auto; Cool.setAuto(ctx, auto)
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Quick Settings tile", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Shows the temperature in your pull-down panel. Tap it to switch cool-down on or off.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Pill("Add tile", accent = true, onClick = onAddTile)
        }
        Text(
            "What this can't do: close other apps or lower your phone's temperature directly. " +
                "For a hot phone, also take off the case, get out of the sun, unplug the charger and lower screen brightness.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
}
