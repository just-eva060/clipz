@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.swiftget.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/** Shrinks your own photos and videos. Output is a new file; the original is never touched. */
object Compressor {
    fun sizeOf(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) { 0L }

    /** Returns the new file's size, or null on failure. */
    suspend fun image(ctx: Context, uri: Uri, maxSide: Int, quality: Int): Long? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null
            val scale = maxSide.toFloat() / maxOf(src.width, src.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
            } else src
            val tmp = File.createTempFile("cmp", ".jpg", ctx.cacheDir)
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            val size = tmp.length()
            tmp.inputStream().use { Downloader.saveToLibrary("compressed_${System.currentTimeMillis()}.jpg", "image/jpeg", it) }
            tmp.delete()
            size
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Re-encodes to H.264/AAC at a lower height and bitrate. Returns the new size, or null on failure. */
    suspend fun video(ctx: Context, uri: Uri, height: Int, bitrate: Int, onProgress: (Int) -> Unit): Long? {
        val out = File(ctx.cacheDir, "cmp_${System.currentTimeMillis()}.mp4")
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val handler = Handler(Looper.getMainLooper())
                try {
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(height))))
                        .build()
                    val enc = DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                    val transformer = Transformer.Builder(ctx)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(enc)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()
                    transformer.start(edited, out.absolutePath)
                    val holder = ProgressHolder()
                    val tick = object : Runnable {
                        override fun run() {
                            if (!cont.isActive) return
                            if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress)
                            handler.postDelayed(this, 500)
                        }
                    }
                    handler.post(tick)
                    cont.invokeOnCancellation { handler.post { transformer.cancel() } }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        if (!ok) { out.delete(); return null }
        return withContext(Dispatchers.IO) {
            try {
                val size = out.length()
                out.inputStream().use { Downloader.saveToLibrary("compressed_${System.currentTimeMillis()}.mp4", "video/mp4", it) }
                size
            } catch (e: Exception) { null } finally { out.delete() }
        }
    }
}

private data class Preset(val label: String, val height: Int, val bitrate: Int, val maxSide: Int, val quality: Int)

private val PRESETS = listOf(
    Preset("Smallest", 480, 800_000, 1280, 60),
    Preset("Balanced", 720, 2_000_000, 1920, 75),
    Preset("High quality", 1080, 5_000_000, 2560, 88),
)

@Composable
fun CompressScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var mime by remember { mutableStateOf("") }
    var preset by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            picked = uri
            mime = ctx.contentResolver.getType(uri) ?: ""
            message = null
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Compress", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "Make photos and videos smaller. The original stays as it is; the smaller copy is saved to your library.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                picked?.let { "${if (mime.startsWith("video/")) "🎬" else "🖼️"} Chosen · ${fmtBytes(Compressor.sizeOf(ctx, it))}" } ?: "No file chosen",
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Choose photo or video", accent = true) { if (!busy) pick.launch(arrayOf("video/*", "image/*")) }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Quality", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEachIndexed { i, p ->
                    FilterChip(selected = preset == i, onClick = { preset = i }, label = { Text(p.label) })
                }
            }
        }
        val uri = picked
        if (uri != null && !busy) {
            val isVideo = mime.startsWith("video/")
            val isImage = mime.startsWith("image/")
            if (isVideo || isImage) {
                Pill(if (isVideo) "Compress video" else "Compress photo", accent = true) {
                    busy = true; progress = 0; message = null
                    val before = Compressor.sizeOf(ctx, uri)
                    val p = PRESETS[preset]
                    scope.launch {
                        val after = if (isVideo) Compressor.video(ctx, uri, p.height, p.bitrate) { progress = it }
                        else Compressor.image(ctx, uri, p.maxSide, p.quality)
                        busy = false
                        message = if (after == null) "That didn't work. Some videos can't be re-encoded on this phone."
                        else "Done: ${fmtBytes(before)} → ${fmtBytes(after)}" +
                            (if (after >= before && before > 0) ". This file was already small, so it didn't shrink." else ". Saved to your library.")
                    }
                }
            } else {
                Text("Pick a photo or a video.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) {
            GradientBar(if (mime.startsWith("video/")) progress / 100f else null, Modifier.fillMaxWidth())
            Text(if (mime.startsWith("video/")) "Compressing… $progress%. Keep Clipz open." else "Compressing…")
        }
        message?.let { Text(it, color = Mint, fontWeight = FontWeight.SemiBold) }
        Text(
            "Video compression runs on your phone's chip, so it can warm the phone up on long videos. Audio-only files aren't supported yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
}
