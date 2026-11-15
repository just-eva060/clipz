package com.swiftget.app

import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PIN-protected private storage for files you want out of the gallery and out of other apps. */
@Composable
fun VaultScreen() {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    // Block screenshots and the recent-apps thumbnail while the vault is on screen; lock it when you leave.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Vault.lock()
        }
    }
    val unlocked = Vault.unlocked
    var pinSet by remember { mutableStateOf(Vault.hasPin()) }

    when {
        !pinSet -> PinSetup { pinSet = true }
        !unlocked -> PinLock { pinSet = false }
        else -> VaultContents()
    }
}

@Composable
private fun PinSetup(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    PinEntry(
        title = if (first == null) "Create a PIN" else "Confirm your PIN",
        subtitle = if (first == null) "Choose 4 digits to lock your vault" else "Enter the same 4 digits again",
        error = error,
    ) { pin ->
        val f = first
        if (f == null) {
            first = pin
            error = null
        } else if (f == pin) {
            scope.launch {
                withContext(Dispatchers.Default) { Vault.setPin(pin) }
                Vault.unlock()
                onDone()
            }
        } else {
            error = "PINs didn't match. Try again."
            first = null
        }
    }
}

@Composable
private fun PinLock(onReset: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var askReset by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        PinEntry("Vault locked", "Enter your PIN", error) { pin ->
            scope.launch {
                val r = withContext(Dispatchers.Default) { Vault.verify(pin) }
                if (r.ok) {
                    error = null
                    Vault.unlock()
                } else {
                    error = r.message
                }
            }
        }
        TextButton(onClick = { askReset = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            Text("Forgot PIN?")
        }
    }
    if (askReset) {
        ConfirmDialog(
            "Reset the vault?",
            "There is no way to recover a forgotten PIN. Resetting permanently deletes everything hidden in the vault.",
            "Delete everything and reset",
            onConfirm = { Vault.reset(); onReset() },
            onDismiss = { askReset = false },
        )
    }
}

@Composable
fun PinEntry(title: String, subtitle: String, error: String?, onComplete: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(pin) {
        if (pin.length == 4) {
            onComplete(pin)
            pin = ""
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .background(if (i < pin.length) Mint else MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                r.forEach { k ->
                    if (k.isEmpty()) {
                        Spacer(Modifier.size(72.dp))
                    } else {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (k == "⌫") pin = pin.dropLast(1) else if (pin.length < 4) pin += k
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, fontSize = 26.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun VaultContents() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hidden by Vault.items.collectAsState()
    var viewing by remember { mutableStateOf<VaultItem?>(null) }
    var deleting by remember { mutableStateOf<VaultItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔒 Vault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${hidden.size} hidden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill("Lock now", accent = true) { Vault.lock() }
                }
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Hidden files are kept inside Clipz, out of your gallery and file manager. They are not encrypted, " +
                            "and they are deleted if you uninstall Clipz or clear its data. Restore anything you want to keep first.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (hidden.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Nothing hidden yet. In Downloads or Library, tap 🔒 Hide on any file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(hidden, key = { it.id }) { v ->
            val cat = v.folder ?: categoryOf(v.name)
            GlassCard(Modifier.fillMaxWidth()) {
                val previewable = v.mime.startsWith("image/") || v.mime.startsWith("video/")
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).clickable { viewing = v }) {
                    if (previewable) {
                        Thumb(Uri.fromFile(Vault.fileOf(v)), v.mime.startsWith("video/"), Modifier.fillMaxSize()) { TypeBadge(cat, 56.dp) }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypeBadge(cat, 56.dp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(fmtBytes(v.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundAction("👁", size = 38.dp) { viewing = v }
                    RoundAction("↩", accent = true, size = 38.dp) {
                        scope.launch {
                            val ok = Vault.restore(v)
                            Toast.makeText(ctx, if (ok) "Restored to your Library" else "Couldn't restore", Toast.LENGTH_SHORT).show()
                        }
                    }
                    RoundAction("🗑", size = 38.dp) { deleting = v }
                }
            }
        }
    }

    viewing?.let { v ->
        MediaViewer(Uri.fromFile(Vault.fileOf(v)), v.mime, v.name, secure = true) { viewing = null }
    }
    deleting?.let { v ->
        ConfirmDialog(
            "Delete from vault?", "\"${v.name}\" will be permanently deleted.", "Delete",
            onConfirm = { Vault.delete(v) }, onDismiss = { deleting = null },
        )
    }
}
