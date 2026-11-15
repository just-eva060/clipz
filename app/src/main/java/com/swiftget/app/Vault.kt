package com.swiftget.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long,
    val folder: String?,
)

data class PinResult(val ok: Boolean, val message: String?)

/**
 * Hidden files live in Clipz's private storage, which the gallery, file managers and other apps cannot see,
 * behind a PIN. They are NOT encrypted, and Android deletes them if Clipz is uninstalled or its data is cleared.
 */
object Vault {
    private lateinit var app: Context

    var unlocked: Boolean by mutableStateOf(false)
        private set

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        load()
    }

    private fun prefs() = app.getSharedPreferences("vault", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- PIN

    fun hasPin(): Boolean = prefs().contains("hash")

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    /** Slow (about a quarter of a second): call off the main thread. */
    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs().edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0).putLong("lock", 0)
            .apply()
    }

    /** Slow: call off the main thread. Wrong guesses are slowed down after 5 failures. */
    fun verify(pin: String): PinResult {
        val p = prefs()
        val now = System.currentTimeMillis()
        val lockUntil = p.getLong("lock", 0)
        if (now < lockUntil) return PinResult(false, "Too many tries. Wait ${(lockUntil - now) / 1000 + 1}s")
        val salt = Base64.decode(p.getString("salt", "") ?: "", Base64.NO_WRAP)
        val stored = Base64.decode(p.getString("hash", "") ?: "", Base64.NO_WRAP)
        if (MessageDigest.isEqual(hash(pin, salt), stored)) {
            p.edit().putInt("fails", 0).putLong("lock", 0).apply()
            return PinResult(true, null)
        }
        val fails = p.getInt("fails", 0) + 1
        val waitMs = if (fails >= 5) minOf(30L * (1L shl minOf(fails - 5, 6)), 3600L) * 1000L else 0L
        p.edit().putInt("fails", fails).putLong("lock", if (waitMs > 0) now + waitMs else 0L).apply()
        return PinResult(false, if (waitMs > 0) "Too many tries. Try again in ${waitMs / 1000}s" else "Wrong PIN")
    }

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }

    /** Forgot the PIN: the only way back in is to wipe the vault. */
    fun reset() {
        _items.value.forEach { fileOf(it).delete() }
        _items.value = emptyList()
        save()
        prefs().edit().clear().apply()
        unlocked = false
    }

    // ---------------------------------------------------------------- files

    private fun dir(): File = File(app.filesDir, "vault").also { it.mkdirs() }
    fun fileOf(item: VaultItem): File = File(dir(), item.id)
    private fun metaFile() = File(app.filesDir, "vault.json")

    private fun load() {
        try {
            val f = metaFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            val list = ArrayList<VaultItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VaultItem(
                        o.getString("id"), o.getString("name"), o.getString("mime"),
                        o.optLong("size", 0), o.optLong("added", 0),
                        if (o.has("folder")) o.getString("folder") else null,
                    ),
                )
            }
            _items.value = list
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            _items.value.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime)
                        .put("size", it.size).put("added", it.addedAt).putOpt("folder", it.folder),
                )
            }
            val tmp = File(app.filesDir, "vault.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(metaFile())
        } catch (ignored: Exception) {
        }
    }

    /** Copies the file into the vault, and only then removes it from the gallery and the library. */
    suspend fun hide(d: DownloadUi): Boolean = withContext(Dispatchers.IO) {
        val uri = d.savedUri?.let { Uri.parse(it) } ?: return@withContext false
        val id = UUID.randomUUID().toString()
        val out = File(dir(), id)
        try {
            app.contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { input.copyTo(it, 256 * 1024) }
            }
            if (out.length() <= 0L) throw IllegalStateException("empty copy")
            val item = VaultItem(id, d.name, mimeOf(d.name), out.length(), System.currentTimeMillis(), d.folder)
            _items.value = _items.value + item
            save()
            Downloader.remove(d.id, deleteFile = true)
            true
        } catch (e: Exception) {
            out.delete()
            false
        }
    }

    suspend fun restore(item: VaultItem): Boolean = withContext(Dispatchers.IO) {
        val f = fileOf(item)
        if (!f.exists()) return@withContext false
        try {
            val uri = Downloader.saveToLibrary(item.name, item.mime, f.inputStream(), item.folder)
            Downloader.registerExternal(item.name, uri.toString(), item.size, item.folder)
            delete(item)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(item: VaultItem) {
        fileOf(item).delete()
        _items.value = _items.value.filter { it.id != item.id }
        save()
    }
}
