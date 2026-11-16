package app.moyna

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Looks up current information (weather, news, scores, prices...) with Google Search.
 *
 * Why a separate model: on the free tier, Google Search only works with the older Live model
 * (MoynaConfig.LOOKUP_MODEL). Moyna keeps talking with the main model and calls this when she
 * needs fresh facts; the facts come back as text and Moyna says them in her own words.
 */
object LookUp {
    private const val TAG = "Moyna"
    private const val URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    }

    /** Ask [question]; [done] gets the answer text, or null if it failed. Called on an OkHttp thread. */
    fun ask(apiKey: String, question: String, timeoutMs: Long = 15_000, done: (String?) -> Unit) {
        val answer = StringBuilder()
        var finished = false
        var searched = false
        var lastTextAt = 0L
        fun finish(ws: WebSocket, ok: Boolean) {
            synchronized(answer) {
                if (finished) return
                finished = true
            }
            ws.close(1000, null)
            val text = answer.toString().trim()
            Log.i(TAG, "look-up ${if (ok && text.isNotEmpty()) "done (searched=$searched)" else "failed"}: ${text.take(200)}")
            done(if (ok && text.isNotEmpty()) text else null)
        }
        val req = Request.Builder().url(URL).header("x-goog-api-key", apiKey).build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put("setup", JSONObject()
                    .put("model", MoynaConfig.LOOKUP_MODEL)
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                        "You answer factual questions using Google Search. Always search first. Reply in plain " +
                        "English with ONLY the facts needed to answer the question, in ONE short sentence of at " +
                        "most 25 words (numbers, names, dates, times). Temperatures in Fahrenheit only. " +
                        "No background, no extra details, no advice."))))
                    .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
                    .put("outputAudioTranscription", JSONObject()))
                webSocket.send(setup.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())
            private fun handle(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                if (o.has("setupComplete")) {
                    webSocket.send(JSONObject().put("realtimeInput", JSONObject().put("text", question)).toString())
                }
                o.optJSONObject("serverContent")?.let { sc ->
                    sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                        synchronized(answer) { answer.append(it) }
                        lastTextAt = android.os.SystemClock.elapsedRealtime()
                    }
                    // The model also speaks its answer, and only says "done" when that speech has
                    // finished streaming (5-10 s later). The text is complete long before that: the
                    // search details arrive right after the last words. So stop there.
                    if (sc.has("groundingMetadata")) {
                        searched = true
                        if (synchronized(answer) { answer.isNotBlank() }) finish(webSocket, true)
                    }
                    if (sc.optBoolean("turnComplete")) finish(webSocket, true)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) Log.w(TAG, "look-up closed: $code $reason")
                finish(webSocket, false)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "look-up failed: ${t.javaClass.simpleName} ${t.message}")
                finish(webSocket, false)
            }
        })
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ finish(ws, answer.isNotEmpty()) }, timeoutMs)
        // Backstop: if the words stopped coming 2 s ago, the answer is complete.
        val stalled = object : Runnable {
            override fun run() {
                if (finished) return
                if (lastTextAt > 0 && android.os.SystemClock.elapsedRealtime() - lastTextAt > 2_000) finish(ws, true)
                else handler.postDelayed(this, 300)
            }
        }
        handler.postDelayed(stalled, 300)
    }
}
