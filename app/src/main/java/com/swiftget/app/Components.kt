package com.swiftget.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ---------------------------------------------------------------- building blocks

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, accent: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        accent -> Mint.copy(alpha = 0.18f)
        danger -> Color(0xFFFF6B6B).copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        accent -> Mint
        danger -> Color(0xFFFF8A8A)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RoundAction(glyph: String, accent: Boolean = false, size: Dp = 42.dp, onClick: () -> Unit) {
    val bg = if (accent) Modifier.background(BrandGradient) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    Box(
        Modifier.size(size).clip(CircleShape).then(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = (size.value * 0.42f).sp, color = if (accent) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun TypeBadge(cat: String, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(catColor(cat).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(catEmoji(cat), fontSize = (size.value * 0.45f).sp)
    }
}

/** Progress bar with a gradient fill. Pass null for "working, size unknown" (a sliding shimmer). */
@Composable
fun GradientBar(progress: Float?, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (progress != null) {
            val p by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "progress")
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(shape).background(BrandGradient))
        } else {
            val t = rememberInfiniteTransition(label = "shimmer")
            val x by t.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x",
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.offset(x = maxWidth * (x * 1.6f - 0.4f)).fillMaxHeight().width(maxWidth * 0.4f)
                        .clip(shape).background(BrandGradient),
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- thumbnails

object Thumbs {
    private val cache = LruCache<String, Bitmap>(80)
    private val gate = Semaphore(3)

    suspend fun load(ctx: Context, uri: Uri, video: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache.get(key)?.let { return@withContext it }
        gate.withPermit {
            try {
                val bmp = if (video) videoFrame(ctx, uri) else decode(ctx, uri, 360)
                if (bmp != null) cache.put(key, bmp)
                bmp
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** Decodes a picture no bigger than about 2x maxDim on each side. */
    fun decode(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun videoFrame(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

@Composable
fun Thumb(uri: Uri, video: Boolean, modifier: Modifier = Modifier, fallback: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, uri) { value = Thumbs.load(ctx, uri, video) }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
    }
}

// ---------------------------------------------------------------- full-screen viewer

@Composable
fun MediaViewer(uri: Uri, mime: String, title: String, secure: Boolean = false, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                mime.startsWith("image/") -> {
                    val ctx = LocalContext.current
                    val bmp by produceState<Bitmap?>(initialValue = null, uri) {
                        value = withContext(Dispatchers.IO) { Thumbs.decode(ctx, uri, 2048) }
                    }
                    val b = bmp
                    if (b != null) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
                mime.startsWith("video/") || mime.startsWith("audio/") -> {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            VideoView(c).apply {
                                val mc = MediaController(c)
                                mc.setAnchorView(this)
                                setMediaController(mc)
                                setVideoURI(uri)
                                setOnPreparedListener { it.isLooping = false; start() }
                            }
                        },
                    )
                }
                else -> Text(
                    "No preview for this file type",
                    color = Color.White, modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                Pill("Close", onClick = onClose)
            }
        }
    }
}
