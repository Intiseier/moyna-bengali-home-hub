package app.moyna

import android.Manifest
import android.app.Activity
import android.content.Context
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
 * The home hub on the wall: clock on top, a status line, big tiles (see MoynaConfig.TILES) and a
 * weather strip. Moyna itself runs in MoynaService; this screen only shows things and passes taps on.
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
    private val tileEmoji = HashMap<String, TextView>()
    private val tileSubs = HashMap<String, TextView>()
    // Weather strip
    private var wxNow: TextView? = null
    private var wxTemp: TextView? = null
    private var wxRange: TextView? = null
    private val wxHours = ArrayList<Triple<TextView, TextView, TextView>>()   // hour, icon, temperature

    private var state = MoynaState.IDLE
    private var detail: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a wall clock never sleeps; it dims

        // ----- top: clock (English), then the date and Bangladesh time, both large -----
        clock = text(46f, bold = true)
        date = text(29f, bold = true)
        dhaka = text(27f, bold = true)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(2))
            addView(clock); addView(date); addView(dhaka)
        }

        // ----- status line (Bengali) -----
        status = text(22f).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }

        // ----- tiles, two per row; the weather tile is a wide strip of its own -----
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), dp(10)) }
        val margin = dp(4)
        fun cell(v: View) = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(margin, margin, margin, margin) }
        var row: LinearLayout? = null
        fun flush() { row?.let { if (it.childCount == 1) it.addView(View(this), cell(View(this))); grid.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }; row = null }
        for (t in MoynaConfig.TILES) {
            if (t.id == "weather") {
                flush()
                grid.addView(weatherStrip(), LinearLayout.LayoutParams(-1, 0, 1.1f).apply { setMargins(margin, margin, margin, margin) })
                continue
            }
            val r = row ?: LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { row = it }
            r.addView(tileView(t), cell(r))
            if (r.childCount == 2) flush()
        }
        flush()

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
            addView(stop, LinearLayout.LayoutParams(dp(170), dp(170)).apply { topMargin = dp(30) })
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
            addView(usageBar, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))
        }
        setContentView(root)
        handleTestIntent(intent)
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun tileBackground() = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(38, 255, 255, 255)) }

    /** One tile: big emoji, Bengali label, and a line or two of live info (bus minutes, next prayer). */
    private fun tileView(t: Tile): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = tileBackground() }
        if (t.id.isEmpty()) { box.alpha = 0.3f; return box }   // reserved slot: a faint empty tile
        val emoji = text(40f).apply { text = t.emoji }
        val sub = text(19f, bold = true).apply { setTextColor(Color.rgb(255, 225, 150)); visibility = View.GONE; setPadding(0, dp(2), 0, 0) }
        box.addView(emoji)
        box.addView(text(25f, bold = true).apply { text = t.label; setPadding(0, dp(2), 0, 0) })
        box.addView(sub)
        box.setOnClickListener {
            box.alpha = 0.5f; box.animate().alpha(1f).setDuration(350).start()
            MoynaHub.engine?.tileTapped(t.id)
        }
        tileViews[t.id] = box; tileEmoji[t.id] = emoji; tileSubs[t.id] = sub
        return box
    }

    /** The weather strip: today on top (picture, temperature, high and low), the next 7 hours below. */
    private fun weatherStrip(): View {
        val now = text(38f); val temp = text(36f, bold = true); val range = text(19f).apply { setTextColor(Color.rgb(255, 225, 150)) }
        wxNow = now; wxTemp = temp; wxRange = range
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(now); addView(temp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10); rightMargin = dp(14) }); addView(range)
        }
        val hours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(7) {
            val h = text(15f).apply { setTextColor(Color.rgb(200, 215, 215)) }; val i = text(24f); val t = text(17f, bold = true)
            wxHours.add(Triple(h, i, t))
            hours.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(h); addView(i); addView(t)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = tileBackground()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(head, LinearLayout.LayoutParams(-1, -2)); addView(hours, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            setOnClickListener { alpha = 0.5f; animate().alpha(1f).setDuration(350).start(); MoynaHub.engine?.tileTapped("weather") }
        }
        tileViews["weather"] = box
        return box
    }

    /** Put the latest bus, prayer and weather information on the tiles. */
    private fun fillTiles() {
        tileSubs["bus"]?.let {
            val fresh = Bus.ageSeconds() <= 180
            it.text = if (fresh) Bus.tileText() else ""; it.visibility = if (fresh) View.VISIBLE else View.GONE
            tileEmoji["bus"]?.textSize = if (fresh) 24f else 40f
        }
        tileSubs["prayer"]?.let {
            val t = Prayer.tileText()
            it.text = t; it.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            tileEmoji["prayer"]?.textSize = if (t.isEmpty()) 40f else 34f
        }
        if (Weather.has()) {
            wxNow?.text = Weather.icon(Weather.code, Weather.isDay)
            wxTemp?.text = "${Weather.tempF}°"
            wxRange?.text = "↑${Weather.highF}°  ↓${Weather.lowF}°"
            val hours = Weather.hours
            wxHours.forEachIndexed { i, (h, icon, t) ->
                val w = hours.getOrNull(i)
                h.text = w?.time?.format(hourFmt)?.lowercase(Locale.ENGLISH) ?: ""
                icon.text = if (w == null) "" else Weather.icon(w.code, w.isDay)
                t.text = if (w == null) "" else "${w.tempF}°"
            }
        } else {
            wxNow?.text = "🌦️"; wxTemp?.text = "আবহাওয়া"; wxRange?.text = ""
        }
    }
    private val hourFmt = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH)

    // ---------- life cycle ----------

    override fun onStart() {
        super.onStart()
        MoynaHub.listener = { s, d -> runOnUiThread { show(s, d) } }
        show(MoynaHub.state, MoynaHub.detail)
        MoynaHub.usageListener = { u, alert -> runOnUiThread { showUsage(u, alert) } }
        showUsage(MoynaHub.usage, MoynaHub.usageAlert)
        HubData.onChange = { runOnUiThread { fillTiles() } }
        Prayer.load(applicationContext)
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
        HubData.onChange = null
        getSystemService(SensorManager::class.java).unregisterListener(this)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) MoynaService.start(this)
    }

    // ---------- clock, and keeping the tiles fresh ----------

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

    private val tick = object : Runnable {
        override fun run() {
            val now = ZonedDateTime.now()
            clock.text = now.format(timeFmt)
            date.text = now.format(dateFmt)
            dhaka.text = "Bangladesh " + now.withZoneSameInstant(ZoneId.of("Asia/Dhaka")).format(timeFmt)
            applyLook()
            // Live data only while the screen is lit in the daytime: no polling in the dark night look.
            if (!(night && !awake())) {
                if (Bus.ready() && Bus.ageSeconds() >= MoynaConfig.BUS_REFRESH_SECONDS) Bus.refresh()
                if (Weather.ready() && Weather.ageMinutes() >= MoynaConfig.WEATHER_REFRESH_MINUTES) Weather.refresh()
            }
            fillTiles()
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
        clock.setTextColor(ink); date.setTextColor(ink); dhaka.setTextColor(if (darkLook) ink else Color.rgb(170, 235, 215))
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
        // Hidden admin screen: keep a finger on the big clock for 10 seconds.
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                val r = android.graphics.Rect()
                if (clock.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())) ui.postDelayed(openAdmin, 10_000)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(openAdmin)
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
            MoynaState.THINKING -> when (d) { "search" -> "একটু দেখে\nনিচ্ছি..."; "tile" -> "বলছি..."; else -> "একটু\nভাবছি..." }
            MoynaState.SPEAKING -> if (d == "azan") "🕌\nআজান" else "ময়না\nবলছে..."
            MoynaState.ERROR -> d ?: "সমস্যা হয়েছে"
            MoynaState.TEST -> d ?: "পরীক্ষা চলছে"
            else -> ""
        }
        talkText.textSize = if (s == MoynaState.ERROR || s == MoynaState.TEST) 28f else 46f
        // The red button shows only while Moyna is making sound (talking, the azan, or the look-up sounds).
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
        // Our own number pad, because the phone's keyboard may not open in kiosk mode.
        var typed = ""
        val dots = TextView(this).apply { textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); text = "- - - -" }
        val pad = android.widget.GridLayout(this).apply { columnCount = 3 }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(dots, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }); addView(pad, LinearLayout.LayoutParams(-2, -2))
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Admin PIN").setView(page).setNegativeButton("Cancel", null).create()
        fun press(key: String) {
            typed = if (key == "⌫") typed.dropLast(1) else typed + key
            dots.text = (typed.map { "●" } + List((4 - typed.length).coerceAtLeast(0)) { "-" }).joinToString(" ")
            if (typed.length >= pin.length) {
                dialog.dismiss()
                if (typed == pin) showAdmin()
                else { Log.i("Moyna", "admin: wrong PIN"); android.widget.Toast.makeText(this, "Wrong PIN", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
        for (key in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")) {
            pad.addView(android.widget.Button(this).apply {
                text = key; textSize = 24f; visibility = if (key.isEmpty()) View.INVISIBLE else View.VISIBLE
                setOnClickListener { press(key) }
            }, android.widget.GridLayout.LayoutParams().apply { width = dp(76); height = dp(60) })
        }
        dialog.show()
    }

    private fun showAdmin() {
        Log.i("Moyna", "admin screen opened")
        val prefs = getSharedPreferences("moyna", Context.MODE_PRIVATE)
        val pad = dp(14)
        val info = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 0, 0, pad) }
        val log = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(205, 225, 205)); setPadding(0, pad, 0, 0) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.rgb(20, 24, 28))
            addView(info); addView(controls); addView(log)
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.setContentView(android.widget.ScrollView(this).apply { addView(page) })

        fun refresh() {
            val ev = Events.today()
            fun count(type: String) = ev.count { it.optString("type") == type }
            val answers = ev.filter { it.optString("type") == "answer_start" && !it.optBoolean("tile") }.map { it.optLong("ms") }
            val today = Prayer.day(java.time.LocalDate.now())
            val left = Prayer.calendarDaysLeft()
            info.text = buildString {
                append("Moyna ${BuildConfig.VERSION_NAME}\n\n")
                if (left != null && left <= 7) append("⚠ ADD NEXT MONTH'S PRAYER CALENDAR (${if (left < 0) "ran out" else "$left days left"})\n\n")
                append("TODAY\n")
                append("Wakes: ${count("wake")}   Questions: ${count("turn")}   Tile taps: ${count("tile")}\n")
                append("Look-ups: ${count("look_up")}   Azans: ${count("azan")}   Stops: ${count("stop")}   Errors: ${count("error")}\n")
                append("Average answer time: ${if (answers.isEmpty()) "—" else "%.1f s".format(answers.average() / 1000)}\n")
                append("Talk time: ${(MoynaHub.usage * MoynaConfig.DAILY_TALK_MINUTES).toInt()} min\n\n")
                if (today != null) append("Prayer times${if (today.calculated) " (CALCULATED, no calendar)" else " (calendar)"}: " +
                    "Fajr ${today.fajr}, Zuhr ${today.zuhr}, Asr ${today.asr}, Maghrib ${today.maghrib}, Isha ${today.isha}\n")
                append("Calendar ends: ${Prayer.lastCalendarDate ?: "no calendar loaded"}\n")
                append("Light: ${lux.toInt()} lux (${if (night) "night" else "day"} look)\n")
                append("Kiosk: " + when { !Kiosk.isOwner(this@MainActivity) -> "not set up"; Kiosk.isOff(this@MainActivity) -> "OFF"; else -> "ON" })
            }
            log.text = "TODAY'S EVENTS (newest first)\n\n" + ev.reversed().take(300).joinToString("\n") { e ->
                val t = e.optString("t").substringAfter('T')
                val rest = e.keys().asSequence().filter { it != "t" && it != "type" }.joinToString("  ") { "$it=${e.opt(it)}" }
                "$t  ${e.optString("type")}  $rest"
            }
        }
        fun button(label: String, action: () -> Unit) = controls.addView(android.widget.Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action(); refresh() }
        })
        controls.addView(TextView(this).apply { text = "Azan on / off"; textSize = 15f; setTextColor(Color.WHITE) })
        for ((key, name) in listOf("fajr" to "Fajr", "zuhr" to "Zuhr", "asr" to "Asr", "maghrib" to "Maghrib", "isha" to "Isha")) {
            controls.addView(android.widget.CheckBox(this).apply {
                text = name; setTextColor(Color.WHITE); isChecked = prefs.getBoolean("azan_$key", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("azan_$key", on).apply(); Log.i("Moyna", "admin: azan $key ${if (on) "on" else "off"}") }
            })
        }
        button("Play the azan now (test)") { dialog.dismiss(); MoynaHub.engine?.playAzan("test") }
        button("Refresh") { }
        button("Exit kiosk (use the phone normally)") {
            Kiosk.exit(this)
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        button("Turn kiosk back on") { Kiosk.turnOn(this) }
        button("Close") { dialog.dismiss() }
        refresh()
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
        // --es azan test : play the azan now.   --ez admin true : open the admin screen (asks for the PIN).
        intent?.getStringExtra("azan")?.let { intent.removeExtra("azan"); MoynaHub.engine?.playAzan(it) }
        if (intent?.getBooleanExtra("admin", false) == true) { intent.removeExtra("admin"); root.post { askPin() } }
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
