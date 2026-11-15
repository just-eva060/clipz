package com.swiftget.app

import android.content.Context
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One source (a creator, a site, a collection) and whether you are allowed to clip it. */
data class Right(val name: String, val status: String, val proof: String, val note: String, val expiry: String)

/** Status values: granted (creator said yes), licensed (CC / public domain), own (your recordings), blocked. */
object Rights {
    const val OWN = "My own recordings"
    val list = MutableStateFlow<List<Right>>(emptyList())
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("rights", Context.MODE_PRIVATE)
        val out = ArrayList<Right>()
        try {
            val arr = JSONArray(prefs!!.getString("json", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Right(o.getString("name"), o.getString("status"), o.optString("proof"), o.optString("note"), o.optString("expiry")))
            }
        } catch (e: Exception) { }
        if (out.none { it.name == OWN }) out.add(0, Right(OWN, "own", "", "Videos you recorded yourself", ""))
        list.value = out
    }

    private fun save() {
        val arr = JSONArray()
        list.value.forEach {
            arr.put(JSONObject().put("name", it.name).put("status", it.status).put("proof", it.proof).put("note", it.note).put("expiry", it.expiry))
        }
        prefs?.edit()?.putString("json", arr.toString())?.apply()
    }

    fun add(r: Right) {
        list.value = list.value.filter { it.name != r.name || it.name == OWN } + r
        save()
    }

    fun remove(name: String) {
        if (name == OWN) return
        list.value = list.value.filter { it.name != name }
        save()
    }

    fun expired(r: Right): Boolean = try {
        r.expiry.isNotBlank() && LocalDate.parse(r.expiry.trim()).isBefore(LocalDate.now())
    } catch (e: Exception) { false }

    fun cleared(r: Right): Boolean = r.status in setOf("granted", "licensed", "own") && !expired(r)
}

private val STATUSES = listOf("granted" to "Permission granted", "licensed" to "Licensed (CC / public domain)", "blocked" to "Not allowed")

@Composable
fun RightsScreen(onBack: () -> Unit) {
    val list by Rights.list.collectAsState()
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("granted") }
    var proof by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction("←", onClick = onBack)
            Text("Rights ledger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "Record who has allowed you to clip their content. The clip studio only exports from sources marked as cleared here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Add a source", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Creator or source name") }, singleLine = true)
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STATUSES.forEach { (k, label) -> FilterChip(selected = status == k, onClick = { status = k }, label = { Text(label) }) }
            }
            OutlinedTextField(proof, { proof = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Proof (link to their permission or license)") }, singleLine = true)
            OutlinedTextField(expiry, { expiry = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Expires (yyyy-mm-dd, optional)") }, singleLine = true)
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Note (credit wording, limits)") })
            Row(Modifier.padding(top = 10.dp)) {
                Pill("Save", accent = true) {
                    if (name.isNotBlank()) {
                        Rights.add(Right(name.trim(), status, proof.trim(), note.trim(), expiry.trim()))
                        name = ""; proof = ""; expiry = ""; note = ""
                    }
                }
            }
        }
        list.forEach { r ->
            val ok = Rights.cleared(r)
            GlassCard(Modifier.fillMaxWidth()) {
                Text(r.name, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        Rights.expired(r) -> "Expired ${r.expiry}"
                        r.status == "own" -> "Your own recordings"
                        else -> STATUSES.firstOrNull { it.first == r.status }?.second ?: r.status
                    },
                    color = if (ok) Mint else Coral, style = MaterialTheme.typography.labelLarge,
                )
                if (r.proof.isNotBlank()) Text("Proof: ${r.proof}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.name != Rights.OWN) Row(Modifier.padding(top = 8.dp)) { Pill("Remove", danger = true) { Rights.remove(r.name) } }
            }
        }
    }
}
