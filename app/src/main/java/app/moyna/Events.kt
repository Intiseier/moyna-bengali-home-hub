package app.moyna

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The events log: one line of JSON per event, one file per day, kept for 60 days, in the app's
 * private storage on the phone (files/events/2026-11-16.jsonl). Text only, never audio.
 * Copy to the PC with the adb command in the README.
 */
object Events {
    private var dir: File? = null
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private const val KEEP_DAYS = 60L

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "events").apply { mkdirs() }
        prune()
    }

    @Synchronized
    fun log(type: String, vararg fields: Pair<String, Any?>) {
        val d = dir ?: return
        try {
            val o = JSONObject().put("t", LocalDateTime.now().format(stamp)).put("type", type)
            for ((k, v) in fields) if (v != null) o.put(k, v)
            File(d, "${LocalDate.now()}.jsonl").appendText(o.toString() + "\n")
        } catch (e: Exception) { Log.w("Moyna", "event not saved: ${e.message}") }
    }

    /** Today's events, oldest first. */
    @Synchronized
    fun today(): List<JSONObject> = try {
        File(dir, "${LocalDate.now()}.jsonl").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { try { JSONObject(it) } catch (e: Exception) { null } } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun prune() {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
        dir?.listFiles()?.forEach { if (it.name.endsWith(".jsonl") && it.name.substringBefore('.') < cutoff) it.delete() }
    }
}
