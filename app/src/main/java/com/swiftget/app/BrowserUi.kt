package com.swiftget.app

import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private val SITES = listOf(
    "🔎 Google" to "https://www.google.com",
    "▶️ YouTube" to "https://m.youtube.com",
    "📸 Instagram" to "https://www.instagram.com",
    "🎵 TikTok" to "https://www.tiktok.com",
    "✖ X" to "https://x.com",
    "👻 Snapchat" to "https://www.snapchat.com",
    "👍 Facebook" to "https://m.facebook.com",
    "🎞 Vimeo" to "https://vimeo.com",
)

private fun normalize(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        t.contains(".") && !t.contains(" ") -> "https://$t"
        else -> "https://www.google.com/search?q=" + Uri.encode(t)
    }
}

@Composable
fun BrowserScreen(
    web: WebView,
    address: MutableState<String>,
    found: List<DetectedMedia>,
    status: String,
    onDownload: (DetectedMedia) -> Unit,
    onExit: () -> Unit,
) {
    var text by remember(address.value) { mutableStateOf(address.value) }
    var open by remember { mutableStateOf(false) }
    BackHandler { if (web.canGoBack()) web.goBack() else onExit() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                shape = RoundedCornerShape(50),
                placeholder = { Text("Search or enter address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { web.loadUrl(normalize(text)) }),
            )
            Pill("Go", accent = true) { web.loadUrl(normalize(text)) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SITES.forEach { (name, url) -> Pill(name) { web.loadUrl(url) } }
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (found.isNotEmpty()) {
            Row(
                Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(50)).background(BrandGradient)
                    .clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "🎬 ${found.size} media found · tap to ${if (open) "hide" else "show"}",
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                )
            }
            if (open) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.take(8).forEach { m ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "${m.kind} · ${m.label}", Modifier.weight(1f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Pill("Get", accent = true) { onDownload(m); open = false }
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
            factory = { web.also { (it.parent as? ViewGroup)?.removeView(it) } },
        )
    }
}

@Composable
fun WebViewMissing(reason: String?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("The browser can't start on this phone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Clipz's browser needs \"Android System WebView\" (and Google Chrome) to be installed, enabled and up to date. " +
                "Open Play Store, search for Android System WebView, then update or enable it and reopen Clipz. " +
                "Pasted links, Share and the other tabs still work without it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reason != null) {
            Text("Details: $reason", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
