package com.swiftget.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class Status { QUEUED, DOWNLOADING, PAUSED, WAITING_NETWORK, FINALIZING, COMPLETED, FAILED }

val ACTIVE_STATES = setOf(Status.QUEUED, Status.DOWNLOADING, Status.WAITING_NETWORK, Status.FINALIZING)

/** One byte range of a file. end is inclusive; end < 0 means "length unknown". */
class Segment(val start: Long, @Volatile var end: Long, @Volatile var downloaded: Long = 0L) {
    val length: Long get() = if (end < 0) -1L else end - start + 1
    val done: Boolean get() = end >= 0 && downloaded >= length
    val remaining: Long get() = if (end < 0) Long.MAX_VALUE else (length - downloaded).coerceAtLeast(0)
}

class DownloadItem(
    val id: String,
    val url: String,
    @Volatile var fileName: String,
    @Volatile var totalBytes: Long,
    @Volatile var rangeOk: Boolean,
    @Volatile var etag: String?,
    @Volatile var lastModified: String?,
    val userAgent: String?,
    val cookie: String?,
    val referer: String?,
    val createdAt: Long,
    @Volatile var nameLocked: Boolean = false,
) {
    @Volatile var status: Status = Status.QUEUED
    @Volatile var error: String? = null
    @Volatile var savedUri: String? = null
    @Volatile var speed: Long = 0L

    // HLS (playlist of small segments): how many segments are already in the file, and the file size at that point.
    @Volatile var hls: Boolean = false
    @Volatile var hlsIndex: Int = 0
    @Volatile var hlsOffset: Long = 0L

    /** Set for files that did not come from a download, e.g. "Status" for saved WhatsApp statuses. */
    @Volatile var folder: String? = null

    val segments = CopyOnWriteArrayList<Segment>()

    val downloaded: Long
        get() = segments.sumOf { minOf(it.downloaded, if (it.length >= 0) it.length else Long.MAX_VALUE) }

    fun toUi() = DownloadUi(
        id = id,
        name = fileName,
        total = totalBytes,
        downloaded = downloaded,
        speed = speed,
        status = status,
        error = error,
        connections = if (status == Status.DOWNLOADING) segments.count { !it.done } else 0,
        savedUri = savedUri,
        folder = folder,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("name", fileName); put("total", totalBytes)
        put("range", rangeOk); put("created", createdAt); put("locked", nameLocked)
        put("status", status.name)
        put("hls", hls)
        putOpt("folder", folder)
        synchronized(this@DownloadItem) { put("hidx", hlsIndex); put("hoff", hlsOffset) }
        putOpt("etag", etag); putOpt("lm", lastModified); putOpt("ua", userAgent)
        putOpt("cookie", cookie); putOpt("referer", referer); putOpt("saved", savedUri)
        val arr = JSONArray()
        segments.forEach { arr.put(JSONObject().put("s", it.start).put("e", it.end).put("d", it.downloaded)) }
        put("segs", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            fun opt(k: String) = if (o.has(k)) o.getString(k) else null
            val item = DownloadItem(
                id = o.getString("id"), url = o.getString("url"), fileName = o.getString("name"),
                totalBytes = o.optLong("total", -1), rangeOk = o.optBoolean("range", false),
                etag = opt("etag"), lastModified = opt("lm"), userAgent = opt("ua"),
                cookie = opt("cookie"), referer = opt("referer"),
                createdAt = o.optLong("created", 0), nameLocked = o.optBoolean("locked", false),
            )
            item.status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED)
            item.savedUri = opt("saved")
            item.hls = o.optBoolean("hls", false)
            item.folder = opt("folder")
            item.hlsIndex = o.optInt("hidx", 0)
            item.hlsOffset = o.optLong("hoff", 0L)
            val arr = o.optJSONArray("segs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                item.segments.add(Segment(s.getLong("s"), s.getLong("e"), s.getLong("d")))
            }
            return item
        }
    }
}

val CATEGORIES = listOf("Videos", "Music", "Images", "Documents", "Archives", "Apps", "Games", "Status", "Other")

fun categoryOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mkv", "webm", "mov", "avi", "3gp", "ts", "m4v", "flv", "wmv", "mpg", "mpeg" -> "Videos"
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "amr" -> "Music"
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "Images"
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "rtf", "odt" -> "Documents"
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "Archives"
    "apk", "xapk", "apks" -> "Apps"
    else -> "Other"
}

/** One quality level of a stream, e.g. label "720p". */
data class HlsVariant(val label: String, val bandwidth: Long, val url: String)

/** A media file or playlist the in-app browser saw while a page was playing. kind: Video, Audio, HLS or Media. */
data class DetectedMedia(val url: String, val kind: String, val label: String)

/** Immutable snapshot for the UI and notifications. */
data class DownloadUi(
    val id: String,
    val name: String,
    val total: Long,
    val downloaded: Long,
    val speed: Long,
    val status: Status,
    val error: String?,
    val connections: Int,
    val savedUri: String?,
    val folder: String? = null,
) {
    /** Library folder this file belongs in. */
    val category: String get() = folder ?: categoryOf(name)
    val mime: String get() = mimeOf(name)

    val active: Boolean get() = status in ACTIVE_STATES
    val progress: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
}
