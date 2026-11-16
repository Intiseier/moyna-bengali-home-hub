package app.moyna

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

enum class MoynaState { IDLE, LISTENING, THINKING, SPEAKING, ERROR, SLEEPING, TEST }

/**
 * The "brain" that runs one conversation at a time:
 * "Moyna" (or a tap) -> chime -> listen -> think -> speak -> listen for a follow-up -> back to waiting.
 * The microphone is always on. While waiting, its sound only goes to the on-phone wake word
 * detector. Only after the chime is her voice sent to Gemini.
 * Everything here runs on the main thread. It lives inside MoynaService.
 */
class MoynaEngine(
    private val context: Context,
    private val onState: (MoynaState, String?) -> Unit,
    /** Ask for the Moyna screen to be shown (and the screen turned on). */
    private val onWantScreen: () -> Unit,
) {

    private val tag = "Moyna"
    private val main = Handler(Looper.getMainLooper())
    private val player = AudioPlayer()
    private val mic = MicStreamer { pcm, rms -> main.post { onMicChunk(pcm, rms) } }
    private val wake = WakeWord(context.assets) { word, mode ->
        main.post {
            when {
                state == MoynaState.TEST -> { testHits++; Log.i(tag, "TEST WAKE #$testHits"); showTest() }
                mode == Listen.COMMAND && client != null && word.startsWith("MOYNA") -> {
                    lastNameAt = SystemClock.elapsedRealtime()
                    // Her name alone, while Moyna is talking, cuts the answer short; Moyna then listens.
                    if (state == MoynaState.SPEAKING) { Log.i(tag, "name heard while talking: answer cut short"); stopSpeaking() }
                }
                mode == Listen.COMMAND && client != null && word.startsWith("STOP") -> {
                    // While Moyna talks, her own voice comes back through the mic and can sound like a
                    // stop word. Then only "Moyna ... stop" counts (her name within the last 2.5 s).
                    val nameFirst = word.startsWith("STOP_NAME") ||
                        SystemClock.elapsedRealtime() - lastNameAt < 2_500
                    if ((state == MoynaState.SPEAKING || lookingUp) && !nameFirst) Log.i(tag, "ignored $word: Moyna was talking and no name before it")
                    else stopConversation("voice: $word")
                }
                mode == Listen.WAKE && client == null && (state == MoynaState.IDLE || state == MoynaState.ERROR) ->
                    startConversation("wake:$word")
            }
        }
    }

    var state = MoynaState.IDLE
        private set

    private var client: GeminiLiveClient? = null
    private var session = 0                 // which conversation callbacks belong to
    private var ready = false               // Gemini has accepted our setup
    private var retried = false             // one quiet retry per conversation if connecting fails
    private val pending = ArrayList<ByteArray>()  // her voice recorded before Gemini was ready
    private var sendAfter = 0L              // don't send the chime itself to Gemini
    private var heardSpeech = false
    private var gotWordsThisTurn = false    // Gemini transcribed some of her words this turn
    private var lastLoudAt = 0L
    private var noiseFloor = 150.0
    private var modelTurnOpen = false       // Gemini is in the middle of an answer
    private var dropRestOfAnswer = false    // she tapped to stop Moyna talking
    private var conversationStartedAt = 0L
    private var followUpDeadline = 0L       // loud noise can't push the follow-up window past this
    private var silentChunks = 0            // microphone delivering pure silence (blocked by Android?)
    private val bargeIn = ArrayList<ByteArray>()  // loud chunks heard while Moyna talks

    private var exchanges = 0               // answers Moyna has given in this conversation
    private var lookingUp = false           // a look_up is running: play the "searching" sounds, mute the mic to Gemini

    // While a look-up runs: a soft tick every 1.2 s, and one "please wait a bit more" after 8 s.
    private val lookupTick = object : Runnable {
        override fun run() {
            if (!lookingUp) return
            if (player.isIdle()) player.play(Sounds.tick)
            main.postDelayed(this, 1200)
        }
    }
    private val lookupSlow = Runnable {
        if (lookingUp) { Log.i(tag, "look-up is slow; asking her to wait"); Clips.get(context, "waiting")?.let { player.play(it) } }
    }
    private fun stopLookupSounds() {
        lookingUp = false
        main.removeCallbacks(lookupTick); main.removeCallbacks(lookupSlow)
    }

    private val giveUp = Runnable { endConversation(playEndChime = true) }

    /** Start the microphone. Safe to call again (e.g. after the screen is shown). */
    fun start() = main.post {
        mic.start(echoCancel = client != null && MoynaConfig.ECHO_CANCEL)
        main.removeCallbacks(usageTick); usageTick.run()
    }

    // ---------- talk-time today (the thin bar at the bottom of the screen) ----------

    private val prefs = context.getSharedPreferences("moyna", Context.MODE_PRIVATE)
    private var talkStartedAt = 0L

    private fun storedTalkMsToday(): Long {
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString("talk_day", "") != today) prefs.edit().putString("talk_day", today).putLong("talk_ms", 0).apply()
        return prefs.getLong("talk_ms", 0)
    }

    /** Add the time since the conversation started to today's total. */
    private fun recordTalk() {
        if (talkStartedAt == 0L) return
        val ms = SystemClock.elapsedRealtime() - talkStartedAt
        talkStartedAt = 0
        prefs.edit().putLong("talk_ms", storedTalkMsToday() + ms).apply()
        Log.i(tag, "talk time today: ${storedTalkMsToday() / 60_000} min")
        publishUsage()
    }

    private fun publishUsage() {
        val running = if (talkStartedAt > 0) SystemClock.elapsedRealtime() - talkStartedAt else 0
        val used = (storedTalkMsToday() + running) / (MoynaConfig.DAILY_TALK_MINUTES * 60_000f)
        val alert = System.currentTimeMillis() - prefs.getLong("quota_at", 0) < 6 * 3_600_000L
        MoynaHub.publishUsage(used, alert)
    }

    private val usageTick = object : Runnable {
        override fun run() { publishUsage(); main.postDelayed(this, if (client != null) 10_000 else 60_000) }
    }

    /** Called when she taps the screen. */
    fun trigger() = main.post {
        when (state) {
            MoynaState.IDLE, MoynaState.ERROR -> startConversation("tap")
            MoynaState.SLEEPING -> { main.removeCallbacks(sleepTick); startConversation("tap while sleeping") }
            MoynaState.SPEAKING -> stopSpeaking()
            else -> {}
        }
    }

    /** The red stop button: stop everything, same as saying "থামো". */
    fun stopButton() = main.post { stopConversation("stop button") }

    /** "থামো" or the stop button: cut the answer, end the conversation, go back to waiting for "Moyna". */
    private fun stopConversation(why: String) {
        Log.i(tag, "stop ($why): conversation ended, still listening for Moyna")
        player.stopNow()
        endConversation(playEndChime = true)
    }

    // ---------- sleep (only when she asks for quiet: "এক ঘণ্টা চুপ থাকো") ----------

    private var sleepUntil = 0L
    private var lastNameAt = 0L    // when "Moyna" was last heard during a conversation
    private val sleepTick = object : Runnable {
        override fun run() {
            if (state != MoynaState.SLEEPING) return
            if (SystemClock.elapsedRealtime() >= sleepUntil) {
                Log.i(tag, "sleep over; listening for Moyna again")
                wake.clear()
                setState(MoynaState.IDLE)
            } else {
                setState(MoynaState.SLEEPING, sleepText())
                main.postDelayed(this, 30_000)
            }
        }
    }

    /** End any conversation right away, say "ঠিক আছে, আমি একটু চুপ থাকছি", and ignore "Moyna" for a while. */
    private fun stopAndSleep(minutes: Int, why: String) {
        Log.i(tag, "stop + sleep $minutes min ($why)")
        main.removeCallbacks(giveUp); stopLookupSounds()
        val c = client
        client = null
        session++
        ready = false; pending.clear()
        c?.close()
        player.stopNow()
        player.play(Clips.get(context, "sleep") ?: Sounds.end)
        mic.start(echoCancel = false)
        recordTalk()
        wake.clear()
        sleepUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
        setState(MoynaState.SLEEPING, sleepText())
        main.removeCallbacks(sleepTick)
        main.postDelayed(sleepTick, 30_000)
    }

    private fun sleepText(): String {
        val mins = ((sleepUntil - SystemClock.elapsedRealtime() + 59_999) / 60_000).coerceAtLeast(1)
        val left = if (mins >= 60) "${mins / 60} ঘণ্টা ${if (mins % 60 > 0) "${mins % 60} মিনিট " else ""}" else "$mins মিনিট "
        return bengaliDigits("নিজে জাগবে ${left}পরে")
    }

    private fun bengaliDigits(s: String) = s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

    // ---------- commands from Gemini ----------

    private fun changeVolume(change: String): String {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val now = am.getStreamVolume(stream)
        val v = when (change) {
            "louder" -> (now + 3).coerceAtMost(max)
            "quieter" -> (now - 3).coerceAtLeast(3)      // never go fully silent
            else -> max
        }
        am.setStreamVolume(stream, v, 0)
        Log.i(tag, "volume $now -> $v of $max")
        return if (v == now) "volume was already ${if (v == max) "at maximum" else "at $v of $max"}" else "volume is now $v of $max"
    }

    fun shutdown() = main.post { endConversation(playEndChime = false); mic.stop() }

    // ---------- conversation lifecycle ----------

    private fun startConversation(why: String) {
        Log.i(tag, "conversation start ($why)")
        onWantScreen()
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) { fail("problem", "চাবি (API key) বসানো হয়নি"); return }
        if (!isOnline()) { Log.w(tag, "no internet"); fail("offline", "ইন্টারনেট কাজ করছে না"); return }

        session++
        ready = false; retried = false; pending.clear(); heardSpeech = false; gotWordsThisTurn = false
        modelTurnOpen = false; dropRestOfAnswer = false; exchanges = 0

        connect()
        talkStartedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(usageTick); main.postDelayed(usageTick, 10_000)
        mic.start(echoCancel = MoynaConfig.ECHO_CANCEL)   // conversation: filter Moyna's own voice
        setState(MoynaState.LISTENING)
        player.play(Sounds.start)
        // The speaker plays the chime a little late, so wait a bit longer before listening,
        // otherwise the end of the chime counts as "she is talking".
        sendAfter = SystemClock.elapsedRealtime() + Sounds.START_MS + 350
        conversationStartedAt = SystemClock.elapsedRealtime()
        followUpDeadline = conversationStartedAt + MoynaConfig.FIRST_LISTEN_SECONDS * 1000L
        armGiveUp(MoynaConfig.FIRST_LISTEN_SECONDS)
    }

    private fun connect() {
        client = GeminiLiveClient(BuildConfig.GEMINI_API_KEY, listenerFor(session)).also { it.connect() }
    }

    private fun endConversation(playEndChime: Boolean) {
        main.removeCallbacks(giveUp); stopLookupSounds()
        val c = client
        client = null
        session++                          // ignore any late messages from the old conversation
        ready = false; pending.clear()
        c?.close()
        if (state != MoynaState.IDLE && state != MoynaState.ERROR && playEndChime) player.play(Sounds.end)
        mic.start(echoCancel = false)   // back to the plain mic the wake word was tuned on
        recordTalk()
        wake.clear()
        setState(MoynaState.IDLE)
    }

    private fun stopSpeaking() {
        player.stopNow()
        dropRestOfAnswer = modelTurnOpen
        afterAnswer()
    }

    /** Back to listening for a follow-up question. */
    private fun afterAnswer() {
        val now = SystemClock.elapsedRealtime()
        if (now - conversationStartedAt > MoynaConfig.MAX_CONVERSATION_SECONDS * 1000L) {
            Log.i(tag, "conversation reached its maximum length")
            endConversation(playEndChime = true); return
        }
        if (exchanges >= MoynaConfig.MAX_EXCHANGES) {
            Log.i(tag, "conversation reached ${MoynaConfig.MAX_EXCHANGES} answers; ending")
            endConversation(playEndChime = true); return
        }
        heardSpeech = false; gotWordsThisTurn = false
        setState(MoynaState.LISTENING)
        followUpDeadline = now + MoynaConfig.FOLLOW_UP_SECONDS * 1000L
        armGiveUp(MoynaConfig.FOLLOW_UP_SECONDS)
    }

    private fun armGiveUp(seconds: Int) {
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, seconds * 1000L)
    }

    // ---------- microphone ----------

    private fun onMicChunk(pcm: ByteArray, rms: Int) {
        // Android hands an app pure silence if it isn't allowed to listen in the background.
        // If that lasts 30 s, show the screen: that brings Moyna to the front and restores the mic.
        if (rms == 0) {
            if (++silentChunks == 300) { Log.w(tag, "microphone is silent; bringing Moyna to the front"); onWantScreen() }
        } else silentChunks = 0

        echoOut?.write(pcm)
        if (state == MoynaState.TEST) { testOut?.write(pcm); testBytes += pcm.size; wake.feed(pcm, Listen.WAKE); return }
        if (state == MoynaState.SLEEPING) return  // asleep: ignore everything (a tap wakes her)
        val c = client
        if (c == null) {                          // waiting: only the on-phone wake word hears this
            if (state == MoynaState.IDLE || state == MoynaState.ERROR) {
                wake.feed(pcm, Listen.WAKE)
            }
            return
        }
        // In a conversation, the on-phone listener hears stop words, even while Moyna is talking.
        wake.feed(pcm, Listen.COMMAND)
        if (lookingUp) return                     // Gemini must not hear the "searching" clip and ticks
        if (state == MoynaState.SPEAKING) {       // don't send Moyna's own voice back to Gemini...
            // ...but a loud voice near the phone (far above her filtered echo) interrupts her.
            if (mic.echoCancelling && MoynaConfig.BARGE_IN_LOUDNESS > 0 && rms > MoynaConfig.BARGE_IN_LOUDNESS) {
                bargeIn.add(pcm)
                if (bargeIn.size >= 4) {
                    Log.i(tag, "barge-in: loud voice while Moyna talked (loudness $rms)")
                    stopSpeaking()
                    if (ready) bargeIn.forEach { c.sendAudio(it) } else pending.addAll(bargeIn)
                    bargeIn.clear()
                }
            } else bargeIn.clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < sendAfter) return               // skip the chime

        // Simple loudness check, only used for the screen and the give-up timer.
        // (Gemini itself decides when she has finished talking.)
        val loud = rms > max(MIN_SPEECH_RMS, (noiseFloor * 3).toInt())
        if (!loud) noiseFloor = noiseFloor * 0.95 + rms * 0.05
        if (loud) {
            heardSpeech = true; lastLoudAt = now
            if (state == MoynaState.THINKING) setState(MoynaState.LISTENING)
            // Talking keeps the window open a little, but never more than 8 s past the
            // follow-up deadline, so steady TV noise can't hold the conversation open.
            if (now + 4_000 <= followUpDeadline + 8_000) armGiveUp(4)
        } else if (state == MoynaState.LISTENING && heardSpeech && now - lastLoudAt > 900) {
            setState(MoynaState.THINKING)
            // Wait long for an answer only if Gemini actually heard words; otherwise it was just noise.
            armGiveUp(if (gotWordsThisTurn) MoynaConfig.ANSWER_TIMEOUT_SECONDS else 6)
        }

        if (ready) c.sendAudio(pcm) else pending.add(pcm)
    }

    // ---------- messages from Gemini ----------

    private fun listenerFor(mySession: Int) = object : GeminiLiveClient.Listener {
        private fun onMain(block: () -> Unit) { main.post { if (session == mySession) block() } }

        override fun onReady() = onMain {
            ready = true
            pending.forEach { client?.sendAudio(it) }
            pending.clear()
        }

        override fun onAudio(pcm24k: ByteArray) = onMain {
            modelTurnOpen = true
            if (lookingUp) { stopLookupSounds(); player.stopNow() }   // the answer is ready
            if (dropRestOfAnswer) return@onMain
            if (state != MoynaState.SPEAKING) {
                main.removeCallbacks(giveUp)
                setState(MoynaState.SPEAKING)
            }
            player.play(pcm24k)
        }

        override fun onInputText(text: String) = onMain {
            Log.i(tag, "heard: $text")
            gotWordsThisTurn = true
            if (state == MoynaState.THINKING) armGiveUp(MoynaConfig.ANSWER_TIMEOUT_SECONDS)
        }
        override fun onOutputText(text: String) { Log.i(tag, "said: $text") }

        override fun onInterrupted() = onMain { player.stopNow() }

        override fun onToolCall(id: String, name: String, args: org.json.JSONObject) = onMain {
            when (name) {
                "go_to_sleep" -> stopAndSleep(args.optInt("minutes", MoynaConfig.SLEEP_MINUTES).coerceIn(1, 12 * 60), "asked Gemini")
                "set_volume" -> client?.sendToolResponse(id, name, changeVolume(args.optString("change", "louder")))
                "end_conversation" -> endConversation(playEndChime = true)
                "look_up" -> {
                    val q = args.optString("question")
                    Log.i(tag, "looking up: $q")
                    setState(MoynaState.THINKING, "search")
                    armGiveUp(MoynaConfig.LOOKUP_TIMEOUT_SECONDS + MoynaConfig.ANSWER_TIMEOUT_SECONDS)
                    // Say "একটু দেখে নিচ্ছি।" at once, then tick softly until the answer starts.
                    stopLookupSounds(); lookingUp = true
                    val clip = Clips.get(context, "searching")
                    if (clip != null) player.play(clip)
                    main.postDelayed(lookupTick, (clip?.size ?: 0) / 48L + 400)
                    main.postDelayed(lookupSlow, MoynaConfig.LOOKUP_WAIT_CLIP_SECONDS * 1000L)
                    val asked = SystemClock.elapsedRealtime()
                    LookUp.ask(BuildConfig.GEMINI_API_KEY, q, MoynaConfig.LOOKUP_TIMEOUT_SECONDS * 1000L) { answer ->
                        main.post {
                            Log.i(tag, "look-up took ${(SystemClock.elapsedRealtime() - asked) / 100 / 10.0} s")
                            if (session != mySession) return@post
                            main.removeCallbacks(lookupSlow)
                            client?.sendToolResponse(id, name, answer ?: "Could not look it up right now (no answer).")
                        }
                    }
                }
                else -> client?.sendToolResponse(id, name, "unknown command")
            }
        }

        override fun onTurnComplete() = onMain {
            modelTurnOpen = false
            stopLookupSounds()
            if (state == MoynaState.SPEAKING) exchanges++
            if (dropRestOfAnswer) { dropRestOfAnswer = false; return@onMain }
            if (state == MoynaState.SPEAKING) waitUntilPlayedThen(mySession) { afterAnswer() }
            else afterAnswer()   // Gemini chose not to answer (e.g. it was just noise)
        }

        override fun onClosed(reason: String, isError: Boolean) = onMain {
            if (!isError) { endConversation(playEndChime = state != MoynaState.SPEAKING); return@onMain }
            Log.w(tag, "problem: $reason")
            val r = reason.lowercase()
            val quota = "exhausted" in r || "quota" in r || "429" in r
            val badKey = "api key" in r
            // A hiccup while connecting: quietly try once more, keeping what she already said.
            if (!ready && !retried && !quota && !badKey && isOnline()) {
                retried = true
                Log.i(tag, "retrying connection once")
                connect()
                return@onMain
            }
            client = null; session++
            when {
                quota -> fail("busy", "ময়না এখন একটু বিশ্রামে আছে।\nপরে আবার ডাকুন।")
                badKey -> fail("problem", "চাবি (API key) কাজ করছে না")
                !isOnline() || "unknownhost" in r || "connect" in r || "timeout" in r || "unreachable" in r ->
                    fail("offline", "ইন্টারনেট কাজ করছে না")
                else -> fail("problem", "একটু সমস্যা হয়েছে।\nআবার ডাকুন।")
            }
        }
    }

    /** Wait until the speaker has finished playing everything, then run [next]. */
    private fun waitUntilPlayedThen(mySession: Int, next: () -> Unit) {
        main.postDelayed({
            if (session != mySession) return@postDelayed
            if (player.isIdle()) main.postDelayed({ if (session == mySession) next() }, 250)
            else waitUntilPlayedThen(mySession, next)
        }, 100)
    }

    // ---------- problems ----------

    /** Say a short Bengali message (recorded in advance, works offline) and show it on screen. */
    private fun fail(clip: String, screenText: String) {
        main.removeCallbacks(giveUp); stopLookupSounds()
        val c = client; client = null; c?.close()
        session++
        ready = false; pending.clear()
        player.stopNow()
        player.play(Clips.get(context, clip) ?: Sounds.error)
        mic.start(echoCancel = false)
        if (clip == "busy") prefs.edit().putLong("quota_at", System.currentTimeMillis()).apply()
        recordTalk()
        wake.clear()
        setState(MoynaState.ERROR, screenText)
        main.postDelayed({ if (state == MoynaState.ERROR) setState(MoynaState.IDLE) }, 15_000)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ---------- wake word test mode (started from the PC; records to a file, no Gemini) ----------

    private var testOut: java.io.OutputStream? = null
    private var testUntil = 0L
    private var testHits = 0
    private var testBytes = 0L
    private var testPrompts = 0
    private var promptsDone = 0

    private val testTick = object : Runnable {
        override fun run() {
            if (state != MoynaState.TEST) return
            if (SystemClock.elapsedRealtime() >= testUntil) {
                testOut?.close(); testOut = null
                Log.i(tag, "TEST done: $testHits wake-ups")
                setState(MoynaState.IDLE)
            } else { showTest(); main.postDelayed(this, 1000) }
        }
    }

    private val promptTick = object : Runnable {
        override fun run() {
            if (state != MoynaState.TEST || promptsDone >= testPrompts) return
            promptsDone++
            Log.i(tag, "TEST PROMPT $promptsDone at byte $testBytes")
            player.play(Sounds.prompt)
            showTest()
            main.postDelayed(this, PROMPT_GAP_MS)
        }
    }

    /** Record [seconds] of microphone audio to [file]. If [prompts] > 0, beep that many times, 6 s apart. */
    fun startTest(seconds: Int, file: java.io.File, prompts: Int = 0, micSource: Int = 0) = main.post {
        if (client != null) endConversation(playEndChime = false)
        if (micSource > 0) { mic.idleSource = micSource; mic.start(echoCancel = false) }   // try another mic source
        testOut?.close()
        testOut = java.io.BufferedOutputStream(java.io.FileOutputStream(file))
        testHits = 0; testBytes = 0; promptsDone = 0; testPrompts = prompts
        val total = if (prompts > 0) (3 + prompts * PROMPT_GAP_MS / 1000 + 3).toInt() else seconds
        testUntil = SystemClock.elapsedRealtime() + total * 1000L
        Log.i(tag, "TEST start: ${total}s, prompts=$prompts -> ${file.absolutePath}")
        setState(MoynaState.TEST)
        showTest()
        main.postDelayed(testTick, 1000)
        if (prompts > 0) main.postDelayed(promptTick, 3000)
    }

    private fun showTest() {
        val left = ((testUntil - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
        val prompt = if (testPrompts > 0) "বিপ শুনলে \"ময়না\" বলুন ($promptsDone/$testPrompts)\n" else ""
        onState(MoynaState.TEST, "${prompt}বাকি $left সেকেন্ড\nশুনেছি $testHits বার")
    }

    // ---------- echo test (started from the PC) ----------
    // Plays one of Moyna's recorded sentences while recording, once with the normal mic and once
    // with the echo canceller, so we can measure how much of her own voice the mic still hears.

    private var echoOut: java.io.OutputStream? = null

    fun echoTest(dir: java.io.File) = main.post {
        if (client != null) endConversation(playEndChime = false)
        Log.i(tag, "ECHO TEST: echo canceller available=${android.media.audiofx.AcousticEchoCanceler.isAvailable()}, " +
            "noise suppressor=${android.media.audiofx.NoiseSuppressor.isAvailable()}, " +
            "auto gain=${android.media.audiofx.AutomaticGainControl.isAvailable()}")
        setState(MoynaState.TEST, "echo test")
        // Play echo-play.pcm (24 kHz) if the PC put one there, otherwise a built-in message.
        val custom = java.io.File(dir, "echo-play.pcm")
        val clip = if (custom.exists()) custom.readBytes() else (Clips.get(context, "offline") ?: return@post)
        val recordMs = clip.size / 48L + 3000
        fun round(name: String, aec: Boolean, then: () -> Unit) {
            mic.start(echoCancel = aec)
            main.postDelayed({
                echoOut = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dir, "echo-$name.pcm")))
                Log.i(tag, "ECHO TEST $name: recording ${recordMs / 1000}s")
                main.postDelayed({ player.play(clip) }, 1000)
                main.postDelayed({ echoOut?.close(); echoOut = null; Log.i(tag, "ECHO TEST $name: done"); then() }, recordMs)
            }, 1500)
        }
        round("normal", false) {
            round("aec", true) {
                mic.start(echoCancel = false)
                setState(MoynaState.IDLE)
            }
        }
    }

    // ---------- screen ----------

    private fun setState(s: MoynaState, detail: String? = null) {
        if (s != state) Log.i(tag, "state: $state -> $s")
        state = s
        onState(s, detail)
    }

    companion object {
        /** Quietest sound counted as "she is talking". Raise if TV noise counts as talking. */
        const val MIN_SPEECH_RMS = 350
        const val PROMPT_GAP_MS = 6000L
    }
}

/** The pre-recorded Bengali messages in assets/clips (24 kHz raw PCM). */
object Clips {
    private val cache = HashMap<String, ByteArray>()
    fun get(context: Context, name: String): ByteArray? = synchronized(cache) {
        cache[name] ?: try {
            context.assets.open("clips/$name.pcm").use { it.readBytes() }.also { cache[name] = it }
        } catch (e: Exception) { Log.e("Moyna", "missing clip $name"); null }
    }
}
