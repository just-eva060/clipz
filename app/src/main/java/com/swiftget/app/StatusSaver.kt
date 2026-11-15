package com.swiftget.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

data class StatusFile(val name: String, val uri: Uri, val mime: String, val size: Long, val modified: Long) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

data class StatusSource(val key: String, val label: String, val path: String)

/**
 * Saves WhatsApp statuses you have already viewed. WhatsApp keeps them in a hidden folder on your phone;
 * Android only lets an app read that folder after you pick it yourself once (the app never gets wider access).
 */
object StatusSaver {
    val SOURCES = listOf(
        StatusSource("wa", "WhatsApp", "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
        StatusSource("w4b", "WhatsApp Business", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
    )

    private val OK_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "mp4", "3gp", "mkv")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("status", Context.MODE_PRIVATE)

    /** Where the folder picker should open, so you only have to tap "Use this folder". */
    fun hint(src: StatusSource): Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + src.path)

    fun connectedUri(ctx: Context, key: String): Uri? =
        prefs(ctx).getString("tree_$key", null)?.let { Uri.parse(it) }

    fun isConnected(ctx: Context, key: String): Boolean = connectedUri(ctx, key) != null

    fun connect(ctx: Context, key: String, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {
        }
        prefs(ctx).edit().putString("tree_$key", uri.toString()).apply()
    }

    fun disconnect(ctx: Context, key: String) {
        connectedUri(ctx, key)?.let {
            try {
                ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Exception) {
            }
        }
        prefs(ctx).edit().remove("tree_$key").apply()
    }

    /** Blocking: call from a background thread. */
    fun list(ctx: Context, key: String): List<StatusFile> {
        val tree = connectedUri(ctx, key) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val dir = findStatuses(root, 0) ?: root
        val out = ArrayList<StatusFile>()
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            if (n.startsWith(".")) continue
            if (n.substringAfterLast('.', "").lowercase() !in OK_EXT) continue
            out.add(StatusFile(n, f.uri, mimeOf(n), f.length(), f.lastModified()))
        }
        return out.sortedByDescending { it.modified }
    }

    // You may have picked a parent folder; look a few levels down for ".Statuses".
    private fun findStatuses(d: DocumentFile, depth: Int): DocumentFile? {
        if (d.name == ".Statuses") return d
        if (depth >= 4) return null
        for (c in d.listFiles()) {
            if (c.isDirectory) findStatuses(c, depth + 1)?.let { return it }
        }
        return null
    }

    fun savedNames(ctx: Context): Set<String> = prefs(ctx).getStringSet("saved", emptySet()) ?: emptySet()

    private fun markSaved(ctx: Context, name: String) {
        val set = HashSet(savedNames(ctx))
        set.add(name)
        prefs(ctx).edit().putStringSet("saved", set).apply()
    }

    /** Blocking: call from a background thread. */
    fun save(ctx: Context, f: StatusFile): Boolean {
        return try {
            val input = ctx.contentResolver.openInputStream(f.uri) ?: return false
            val name = "Status_" + f.name
            val uri = Downloader.saveToLibrary(name, f.mime, input, "Status")
            Downloader.registerExternal(name, uri.toString(), f.size, "Status")
            markSaved(ctx, f.name)
            true
        } catch (e: Exception) {
            false
        }
    }
}
