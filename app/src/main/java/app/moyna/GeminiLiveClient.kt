package app.moyna

import android.util.Base64
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

/** One conversation with Gemini Live over a websocket. Create a new one per conversation. */
class GeminiLiveClient(
    private val apiKey: String,
    private val listener: Listener,
    /** If set, this session only reads this text aloud in Moyna's voice (tile taps): no listening, no tools. */
    private val readAloud: String? = null,
) {

    interface Listener {
        fun onReady()
        fun onAudio(pcm24k: ByteArray)
        fun onInputText(text: String)
        fun onOutputText(text: String)
        fun onTurnComplete()
        fun onInterrupted()
        /** Gemini wants Moyna to do something (go_to_sleep, set_volume, end_conversation). */
        fun onToolCall(id: String, name: String, args: JSONObject)
        /** isError=false means a normal close (we asked for it or the server said goodbye). */
        fun onClosed(reason: String, isError: Boolean)
    }

    companion object {
        private const val TAG = "Moyna"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var finished = false
    @Volatile private var closingByUs = false

    fun connect() {
        // The key is sent as a header (not in the web address) so it never shows up in logs.
        val req = Request.Builder().url(URL).header("x-goog-api-key", apiKey).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSetup())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish("server closed: $code $reason", isError = !closingByUs && code != 1000)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish("closed: $code $reason", isError = !closingByUs && code != 1000)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish("failure: ${t.javaClass.simpleName}: ${t.message} http=${response?.code}", isError = !closingByUs)
            }
        })
    }

    fun sendAudio(pcm16k: ByteArray) {
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    /** Tell Gemini the result of a command, so it can say something like "ঠিক আছে, আওয়াজ বাড়িয়েছি". */
    fun sendToolResponse(id: String, name: String, result: String) {
        val r = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result)))))
        ws?.send(r.toString())
    }

    fun close() {
        closingByUs = true
        ws?.close(1000, "bye")
        finish("closed by app", isError = false)
    }

    private fun finish(reason: String, isError: Boolean) {
        if (finished) return
        finished = true
        Log.i(TAG, "session ended: $reason")
        listener.onClosed(reason, isError)
    }

    private fun handle(text: String) {
        if (finished) return
        val o = try { JSONObject(text) } catch (e: Exception) { Log.w(TAG, "bad json"); return }
        if (o.has("setupComplete")) {
            readAloud?.let { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", it)).toString()) }
            listener.onReady()
        }
        o.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { listener.onInputText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { listener.onOutputText(it) }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty()) listener.onAudio(Base64.decode(data, Base64.DEFAULT))
                }
            }
            if (sc.optBoolean("interrupted")) listener.onInterrupted()
            if (sc.optBoolean("turnComplete")) listener.onTurnComplete()
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                Log.i(TAG, "command from Gemini: ${c.optString("name")} ${c.optJSONObject("args")}")
                listener.onToolCall(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject())
            }
        }
        if (o.has("goAway")) Log.i(TAG, "server goAway: ${o.optJSONObject("goAway")}")
    }

    /** Setup for a session that only reads one sentence aloud. */
    private fun buildReadSetup(): String = JSONObject().put("setup", JSONObject()
        .put("model", MoynaConfig.MODEL)
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", MoynaConfig.VOICE)))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
            "You are a voice that reads text aloud. Read the user's text aloud exactly as written, word for word, " +
            "in natural, calm Bangladeshi Bengali. Do not add, remove, translate, explain or answer anything."))))
        .put("outputAudioTranscription", JSONObject())).toString()

    private fun buildSetup(): String {
        if (readAloud != null) return buildReadSetup()
        val setup = JSONObject()
            .put("model", MoynaConfig.MODEL)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", MoynaConfig.VOICE)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", MoynaConfig.SYSTEM_PROMPT + "\n\n" + timeNow()))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
                .put(tool("go_to_sleep", "Stop listening and stay quiet for some minutes. Use ONLY when she clearly asks you to stay quiet for a length of time or not to disturb her. For a plain stop, use end_conversation.",
                    JSONObject().put("minutes", JSONObject().put("type", "INTEGER")
                        .put("description", "How many minutes to stay quiet. 60 if she does not say.")), listOf("minutes")))
                .put(tool("set_volume", "Make the speaker louder or quieter.",
                    JSONObject().put("change", JSONObject().put("type", "STRING")
                        .put("enum", JSONArray().put("louder").put("quieter").put("max"))), listOf("change")))
                .put(tool("end_conversation", "End the conversation now, when she says stop, be quiet, thank you, that is all, or nothing else.",
                    JSONObject(), emptyList()))
                .put(tool("get_bus_times", "Live arrival times of her own buses at her own bus stops near home. Use for any question about when her bus comes.",
                    JSONObject(), emptyList()))
                .put(tool("get_weather", "Current weather and the next 12 hours at her home. Use for any weather question about where she lives.",
                    JSONObject(), emptyList()))
                .put(tool("get_prayer_times", "Today's prayer (namaz) times from her mosque calendar. Use for any prayer time question.",
                    JSONObject(), emptyList()))
                .apply { if (Lights.ready()) put(lightTool()) }
                .put(tool("look_up", "Look up current information on the internet with Google Search: news, sports scores, prices, weather in other places, opening hours, anything that changes.",
                    JSONObject().put("question", JSONObject().put("type", "STRING")
                        .put("description", "A short English question including the place, e.g. 'weather in Dhaka today'.")),
                    listOf("question"))))))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", 200)
                .put("silenceDurationMs", MoynaConfig.SILENCE_MS)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    /** set_light: only offered when there are light buttons. The names come from LIGHTS in personal.properties. */
    private fun lightTool(): JSONObject {
        val names = JSONArray(); Lights.all.forEach { names.put(it.nameEn) }; names.put("all")
        val known = Lights.all.joinToString("; ") { "'${it.nameEn}' (in Bengali she may say ${it.nameBn})" }
        return tool("set_light", "Turn one of her smart-plug lights on or off. Her lights: $known. Use 'all' when she means every light.",
            JSONObject()
                .put("light", JSONObject().put("type", "STRING").put("enum", names))
                .put("on", JSONObject().put("type", "BOOLEAN").put("description", "true = turn on, false = turn off")),
            listOf("light", "on"))
    }

    private fun tool(name: String, description: String, props: JSONObject, required: List<String>): JSONObject {
        val params = JSONObject().put("type", "OBJECT").put("properties", props)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", description).put("parameters", params)
    }

    /** The real date and time, so she can ask "কয়টা বাজে?" or "আজ কী বার?". */
    private fun timeNow(): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH)
        val here = java.time.ZonedDateTime.now()
        val dhaka = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Dhaka"))
        return "Right now it is ${here.format(fmt)} where she lives (${here.zone.id}). " +
            "In Bangladesh it is ${dhaka.format(fmt)}."
    }
}
