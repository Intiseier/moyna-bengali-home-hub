package app.moyna

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The home hub on the wall: clock on top, a status line, and big tiles (see MoynaConfig.TILES).
 * Moyna itself runs in MoynaService; this screen only shows things and passes taps on.
 */
class MainActivity : Activity(), SensorEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var dhaka: TextView
    private lateinit var status: TextView
    private lateinit var grid: LinearLayout
    private lateinit var talk: LinearLayout        // shown instead of the tiles during a conversation
    private lateinit var talkText: TextView
    private lateinit var stop: TextView
    private lateinit var usageFill: View
    private lateinit var usageRest: View
    private val tileViews = HashMap<String, LinearLayout>()
    private val tileSubs = HashMap<String, TextView>()

    private var state = MoynaState.IDLE
    private var detail: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a wall clock never sleeps; it dims

        // ----- top: clock (English), date, Bangladesh time -----
        clock = text(60f, bold = true)
        date = text(21f)
        dhaka = text(18f).apply { setPadding(0, dp(2), 0, 0) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(6))
            addView(clock); addView(date); addView(dhaka)
        }

        // ----- status line (Bengali) -----
        status = text(21f).apply { setPadding(dp(8), dp(6), dp(8), dp(8)) }

        // ----- tiles: 2 columns, as many rows as MoynaConfig.TILES needs -----
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), dp(12)) }
        MoynaConfig.TILES.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { row.addView(tileView(it), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }) }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        // ----- conversation panel: big words, and the red stop button while Moyna makes sound -----
        talkText = text(46f, bold = true)
        stop = text(34f, bold = true).apply {
            text = "থামো"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.rgb(205, 30, 30)); setStroke(dp(4), Color.WHITE)
            }
            setOnClickListener { MoynaHub.engine?.stopButton() }
        }
        talk = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            addView(talkText, LinearLayout.LayoutParams(-1, -2))
            addView(stop, LinearLayout.LayoutParams(dp(170), dp(170)).apply { topMargin = dp(36) })
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(talk, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        // Thin talk-time bar along the very bottom: fills as Moyna is used today, empties at midnight.
        usageFill = View(this); usageRest = View(this)
        val usageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(40, 255, 255, 255))
            addView(usageFill, LinearLayout.LayoutParams(0, -1, 0f))
            addView(usageRest, LinearLayout.LayoutParams(0, -1, 1f))
        }
        root = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(usageBar, FrameLayout.LayoutParams(-1, dp(5), Gravity.BOTTOM))
        }
        setContentView(root)
        handleTestIntent(intent)
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** One tile: big emoji, Bengali label, and a small line for live info (bus minutes, temperature). */
    private fun tileView(t: Tile): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(if (t.id.isEmpty()) 12 else 38, 255, 255, 255)) }
        }
        if (t.id.isEmpty()) return box                       // reserved slot: a faint empty tile
        val sub = text(17f).apply { setTextColor(Color.rgb(255, 225, 150)); visibility = View.GONE }
        box.addView(text(46f).apply { text = t.emoji })
        box.addView(text(24f, bold = true).apply { text = t.label })
        box.addView(sub)
        box.setOnClickListener {
            box.alpha = 0.5f; box.animate().alpha(1f).setDuration(350).start()
            MoynaHub.engine?.tileTapped(t.id)
        }
        tileViews[t.id] = box; tileSubs[t.id] = sub
        return box
    }

    // ---------- life cycle ----------

    override fun onStart() {
        super.onStart()
        MoynaHub.listener = { s, d -> runOnUiThread { show(s, d) } }
        show(MoynaHub.state, MoynaHub.detail)
        MoynaHub.usageListener = { u, alert -> runOnUiThread { showUsage(u, alert) } }
        showUsage(MoynaHub.usage, MoynaHub.usageAlert)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MoynaService.start(this)   // (re)start while visible, so the mic is allowed in the background
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lastTouch = SystemClock.elapsedRealtime()
        ui.removeCallbacks(tick); tick.run()
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
    }

    override fun onStop() {
        MoynaHub.listener = null
        MoynaHub.usageListener = null
        getSystemService(SensorManager::class.java).unregisterListener(this)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) MoynaService.start(this)
    }

    // ---------- clock ----------

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

    private val tick = object : Runnable {
        override fun run() {
            val now = ZonedDateTime.now()
            clock.text = now.format(timeFmt)
            date.text = now.format(dateFmt)
            dhaka.text = "Bangladesh " + now.withZoneSameInstant(ZoneId.of("Asia/Dhaka")).format(timeFmt)
            applyLook()
            ui.postDelayed(this, 5_000)
        }
    }

    // ---------- day / night and dimming ----------

    private var lux = -1f                 // latest light sensor reading (-1 = none yet)
    private var darkSince = 0L            // when the room became dark (0 = it is not dark)
    private var night = false
    private var lastTouch = 0L
    private var forceNight: Boolean? = null   // set from the PC for screenshots (see handleTestIntent)

    override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Night = the room has been dark for a while (light sensor), or by the clock if there is no sensor reading. */
    private fun updateNight() {
        val now = SystemClock.elapsedRealtime()
        val was = night
        val forced = forceNight
        if (forced != null) night = forced
        else if (lux < 0) {
            val h = ZonedDateTime.now().hour
            night = h >= MoynaConfig.NIGHT_FROM_HOUR || h < MoynaConfig.NIGHT_UNTIL_HOUR
        } else if (lux <= MoynaConfig.NIGHT_LUX) {
            if (darkSince == 0L) darkSince = now
            if (now - darkSince > 20_000) night = true
        } else {
            darkSince = 0
            if (lux >= MoynaConfig.DAY_LUX) night = false
        }
        if (was != night) Log.i("Moyna", "screen: ${if (night) "night" else "day"} look (light ${lux.toInt()} lux)")
    }

    private fun busy() = state == MoynaState.LISTENING || state == MoynaState.THINKING ||
        state == MoynaState.SPEAKING || state == MoynaState.ERROR || state == MoynaState.TEST

    /** Awake = someone touched it lately, or Moyna is doing something. */
    private fun awake(): Boolean {
        val idle = SystemClock.elapsedRealtime() - lastTouch
        return busy() || idle < (if (night) MoynaConfig.NIGHT_AWAKE_SECONDS else MoynaConfig.IDLE_DIM_SECONDS) * 1000L
    }

    private fun applyLook() {
        updateNight()
        val awake = awake()
        val inTalk = busy()
        val darkLook = night && !awake
        // Brightness: full while in use; low when idle in the day; barely on at night.
        val b = when {
            awake && !night -> MoynaConfig.BRIGHT_DAY
            awake -> MoynaConfig.BRIGHT_NIGHT_AWAKE
            night -> MoynaConfig.BRIGHT_NIGHT
            else -> MoynaConfig.BRIGHT_DAY_IDLE
        }
        val lp = window.attributes
        if (lp.screenBrightness != b) { lp.screenBrightness = b; window.attributes = lp }

        root.setBackgroundColor(if (darkLook) Color.BLACK else background())
        val ink = if (darkLook) Color.rgb(120, 120, 120) else Color.WHITE
        clock.setTextColor(ink); date.setTextColor(ink); dhaka.setTextColor(if (darkLook) ink else Color.rgb(190, 225, 215))
        status.setTextColor(ink)
        status.visibility = if (darkLook && state == MoynaState.IDLE) View.INVISIBLE else View.VISIBLE
        grid.visibility = if (inTalk) View.GONE else if (darkLook) View.INVISIBLE else View.VISIBLE
        talk.visibility = if (inTalk) View.VISIBLE else View.GONE
    }

    private fun background() = when (state) {
        MoynaState.LISTENING -> Color.rgb(20, 110, 70)
        MoynaState.THINKING -> Color.rgb(150, 100, 20)
        MoynaState.SPEAKING -> Color.rgb(30, 80, 150)
        MoynaState.ERROR -> Color.rgb(140, 40, 40)
        MoynaState.SLEEPING -> Color.rgb(35, 35, 55)
        MoynaState.TEST -> Color.rgb(90, 40, 110)
        else -> Color.rgb(18, 44, 42)
    }

    // A touch while the screen is in its dark night look only wakes the screen; it does not press anything.
    private var swallow = false
    private val openAdmin = Runnable { askPin() }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Hidden admin screen: hold the top-left corner for 5 seconds.
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> if (ev.x < dp(70) && ev.y < dp(70)) ui.postDelayed(openAdmin, 5_000)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(openAdmin)
            MotionEvent.ACTION_MOVE -> if (ev.x > dp(90) || ev.y > dp(90)) ui.removeCallbacks(openAdmin)
        }
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val wasDark = night && !awake()
            lastTouch = SystemClock.elapsedRealtime()
            applyLook()
            swallow = wasDark
        }
        return if (swallow) true else super.dispatchTouchEvent(ev)
    }

    // ---------- what Moyna is doing ----------

    private fun show(s: MoynaState, d: String?) {
        val changed = s != state
        state = s; detail = d
        if (changed && s != MoynaState.IDLE) lastTouch = SystemClock.elapsedRealtime()   // an event brightens the screen
        status.text = when (s) {
            MoynaState.IDLE -> "\"ময়না\" বলে ডাকুন"
            MoynaState.SLEEPING -> "ময়না ঘুমাচ্ছে, ছুঁয়ে জাগান\n${d ?: ""}"
            else -> ""
        }
        talkText.text = when (s) {
            MoynaState.LISTENING -> "বলুন...\nময়না শুনছে"
            MoynaState.THINKING -> if (d == "search") "একটু দেখে\nনিচ্ছি..." else "একটু\nভাবছি..."
            MoynaState.SPEAKING -> "ময়না\nবলছে..."
            MoynaState.ERROR -> d ?: "সমস্যা হয়েছে"
            MoynaState.TEST -> d ?: "পরীক্ষা চলছে"
            else -> ""
        }
        talkText.textSize = if (s == MoynaState.ERROR || s == MoynaState.TEST) 28f else 46f
        // The red button shows only while Moyna is making sound (talking, or the look-up sounds).
        stop.visibility = if (s == MoynaState.SPEAKING || s == MoynaState.THINKING && d == "search") View.VISIBLE else View.INVISIBLE
        // The quiet tile lights up while she is asleep.
        tileViews["quiet"]?.let {
            (it.background as GradientDrawable).setColor(if (s == MoynaState.SLEEPING) Color.rgb(90, 80, 160) else Color.argb(38, 255, 255, 255))
        }
        applyLook()
    }

    /** Green normally, amber when close to the daily amount, red past it or after a quota refusal. */
    private fun showUsage(used: Float, alert: Boolean) {
        val f = used.coerceIn(0f, 1f)
        val color = when {
            alert || used >= 1f -> Color.rgb(230, 60, 50)
            used >= 0.8f -> Color.rgb(240, 170, 40)
            else -> Color.rgb(90, 200, 120)
        }
        usageFill.setBackgroundColor(color)
        (usageFill.layoutParams as LinearLayout.LayoutParams).weight = if (alert) 1f else f
        (usageRest.layoutParams as LinearLayout.LayoutParams).weight = if (alert) 0f else 1f - f
        usageFill.requestLayout()
    }

    // ---------- hidden admin screen (for the family member who looks after Moyna) ----------

    private fun askPin() {
        val pin = BuildConfig.ADMIN_PIN
        if (pin.length < 4) {
            android.widget.Toast.makeText(this, "Admin screen is closed: set ADMIN_PIN in personal.properties and rebuild.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val box = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 28f; gravity = Gravity.CENTER
        }
        android.app.AlertDialog.Builder(this).setTitle("Admin PIN").setView(box)
            .setPositiveButton("OK") { _, _ ->
                if (box.text.toString() == pin) showAdmin()
                else { Log.i("Moyna", "admin: wrong PIN"); android.widget.Toast.makeText(this, "Wrong PIN", android.widget.Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAdmin() {
        Log.i("Moyna", "admin screen opened")
        val pad = dp(14)
        val info = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 0, 0, pad) }
        val log = TextView(this).apply { textSize = 11f; setTextColor(Color.rgb(200, 220, 200)); typeface = Typeface.MONOSPACE }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.rgb(20, 24, 28))
            addView(info); addView(buttons); addView(log)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(page) }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen).apply { setContentView(scroll) }
        fun refresh() {
            val owner = Kiosk.isOwner(this)
            info.text = "Moyna ${BuildConfig.VERSION_NAME}\n" +
                "Talk time today: ${(MoynaHub.usage * MoynaConfig.DAILY_TALK_MINUTES).toInt()} min\n" +
                "Light sensor: ${lux.toInt()} lux (${if (night) "night" else "day"} look)\n" +
                "Kiosk: " + when {
                    !owner -> "not set up (Moyna is not the device owner)"
                    Kiosk.isOff(this) -> "OFF"
                    else -> "ON"
                }
        }
        fun button(label: String, action: () -> Unit) = buttons.addView(android.widget.Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action(); refresh() }
        })
        button("Exit kiosk (use the phone normally)") {
            Kiosk.exit(this)
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        button("Turn kiosk back on") { Kiosk.turnOn(this) }
        button("Close") { dialog.dismiss() }
        refresh()
        // Recent activity, newest first (the phone's own temporary log; Phase 5 adds a proper events log).
        Thread {
            val lines = try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "400", "-s", "Moyna:*"))
                    .inputStream.bufferedReader().readLines().filter { "Moyna" in it && "wake health" !in it }
                    .map { it.substring(6).replace(Regex("I/Moyna\\s*\\(\\s*\\d+\\): "), "") }.reversed()
            } catch (e: Exception) { listOf("could not read the log: ${e.message}") }
            runOnUiThread { log.text = "Recent activity (newest first)\n\n" + lines.joinToString("\n") }
        }.start()
        dialog.show()
    }

    // ---------- tests started from the PC (see README) ----------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    //   adb shell am start -n app.moyna/.MainActivity --ei test_prompts 20
    private fun handleTestIntent(intent: Intent?) {
        // --es look night|day|auto : force the night or day look (for screenshots), or go back to the sensor.
        intent?.getStringExtra("look")?.let {
            intent.removeExtra("look")
            forceNight = when (it) { "night" -> true; "day" -> false; else -> null }
            if (it == "night") lastTouch = 0
            root.post { applyLook() }
        }
        if (intent?.getBooleanExtra("echo_test", false) == true) {
            intent.removeExtra("echo_test")
            root.postDelayed({ MoynaHub.engine?.echoTest(getExternalFilesDir(null)!!) }, 500)
            return
        }
        val secs = intent?.getIntExtra("test_seconds", 0) ?: 0
        val prompts = intent?.getIntExtra("test_prompts", 0) ?: 0
        val src = intent?.getIntExtra("mic_source", 0) ?: 0   // optional: try another microphone source
        if (secs > 0 || prompts > 0) {
            intent?.removeExtra("test_seconds"); intent?.removeExtra("test_prompts")
            root.postDelayed({
                MoynaHub.engine?.startTest(secs, java.io.File(getExternalFilesDir(null), "wake-test.pcm"), prompts, src)
            }, 500)
        }
    }
}
