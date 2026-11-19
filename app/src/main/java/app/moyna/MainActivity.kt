package app.moyna

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The home hub on the wall ("day arc" look, colors in Look): the sun's path through today's prayers,
 * the clock with Dhaka time on a map of Bangladesh, the next prayer, buses, weather, and a row of
 * buttons (lights, Moyna, quiet). Moyna itself runs in MoynaService; this screen only shows things
 * and passes taps on. Sizes are pixels of a 480-pixel-wide screen (the design), scaled to the phone.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var arc: ArcView
    private lateinit var clock: TextView           // hold it for HOLD_SECONDS to open the admin screen
    private lateinit var ampm: TextView
    private lateinit var date: TextView
    private lateinit var map: MapView
    private lateinit var dhaka: TextView
    private lateinit var dhakaAmPm: TextView
    private lateinit var dhakaLabel: TextView
    private lateinit var status: TextView          // only while Moyna sleeps
    private lateinit var hub: LinearLayout         // prayer, bus, weather, buttons (hidden during a conversation)
    // The Moyna panel: slides up from the bottom while she listens, thinks or speaks; the hub dims behind it.
    private lateinit var dim: View
    private lateinit var sheet: LinearLayout
    private lateinit var orb: OrbView
    private lateinit var sheetTitle: TextView
    private lateinit var sheetSub: TextView
    private lateinit var stop: LinearLayout
    private lateinit var stopLabel: TextView
    private lateinit var hint: TextView
    private var sheetShown = false
    private var tileSub: String? = null           // "Bus times" etc. while Moyna reads a tile aloud
    private lateinit var prayerName: TextView
    private lateinit var prayerTime: TextView
    private lateinit var prayerAfter: TextView
    private val busCols = ArrayList<Triple<String, TextView, TextView>>()   // line, next bus, the ones after it
    private var wxIcon: IconView? = null
    private var wxTemp: TextView? = null
    private var wxDesc: TextView? = null
    private var wxRange: TextView? = null
    private val wxHours = ArrayList<Triple<TextView, IconView, TextView>>()   // hour, icon, temperature
    private val lightTiles = ArrayList<LightTile>()
    private lateinit var quietTile: LinearLayout
    private lateinit var quietLabel: TextView
    private lateinit var talkLabel: TextView

    private class LightTile(val index: Int, val box: LinearLayout, val icon: IconView, val name: TextView, val state: TextView)

    private var state = MoynaState.IDLE
    private var detail: String? = null

    private var scale = 1f
    private fun px(v: Float) = (v * scale).toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Words on the screen are English or Bengali (admin screen switch). What Moyna says is always Bengali. */
    private fun english() = getSharedPreferences("moyna", Context.MODE_PRIVATE)
        .getString("ui_lang", MoynaConfig.DEFAULT_SCREEN_LANGUAGE) == "en"
    private fun tr(en: String, bn: String) = if (english()) en else bn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // a wall clock never sleeps; it dims
        scale = resources.displayMetrics.widthPixels / 480f

        // ----- the sun's path through today's prayers -----
        arc = ArcView(this)

        // ----- the clock and date, and Dhaka time on a map of Bangladesh -----
        clock = t(140f, 700, 75, tight = true).apply { letterSpacing = -0.014f }
        ampm = t(30f, 600, color = Look.TURMERIC)
        date = t(29f, 600)
        val timeLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clock); addView(ampm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(6f) })
        }
        val clockSide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(timeLine); addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10f) })
        }
        map = MapView(this)
        dhaka = t(54f, 700, 80, Color.WHITE, center = true, tight = true)
        dhakaAmPm = t(30f, 700, color = Color.WHITE, center = true, tight = true).apply { setPadding(0, px(6f), 0, 0) }
        dhakaLabel = t(19f, 600, color = Look.MAP_LABEL, center = true).apply { text = "Dhaka" }
        val dhakaText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(dhaka); addView(dhakaAmPm)
        }
        val mapBox = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(dhakaText, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = px(58f) })
            addView(dhakaLabel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        val clockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4f), 0, px(2f), 0)
            addView(clockSide, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mapBox, LinearLayout.LayoutParams(px(136f), px(180f)))
        }

        // ----- a line only while Moyna sleeps -----
        status = t(22f, 500, color = Look.MUTED, center = true).apply { visibility = View.GONE }

        // ----- the next prayer -----
        prayerName = t(30f, 700); prayerTime = t(30f, 600, color = Look.TURMERIC); prayerAfter = t(21f, 500, color = Look.MUTED)
        val prayerRow = panel(LinearLayout.HORIZONTAL).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18f), px(10f), px(18f), px(10f))
            addView(prayerName)
            addView(prayerTime, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(10f) })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(prayerAfter)
            setOnClickListener { press(this, "prayer") }
        }

        hub = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hub.addView(prayerRow, LinearLayout.LayoutParams(-1, -2))

        // ----- buses: one column per line (the second line of BUS_STOPS on the left) -----
        if (Bus.ready()) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, stop) in Bus.stops.reversed().withIndex()) {
                if (i > 0) cols.addView(View(this).apply { setBackgroundColor(Look.LINE) },
                    LinearLayout.LayoutParams(px(2f), -1).apply { setMargins(px(16f), px(4f), px(16f), px(4f)) })
                val name = t(22f, 700, color = Look.TEAL).apply { text = stop.first.replace("BX", "Bx") }
                val first = t(54f, 700, 85, tight = true).apply { setPadding(0, px(4f), 0, px(4f)) }
                val rest = t(21f, 500, color = Look.MUTED)
                busCols.add(Triple(stop.first, first, rest))
                cols.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name); addView(first); addView(rest)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val bus = panel(LinearLayout.VERTICAL).apply {
                setPadding(px(18f), px(12f), px(18f), px(12f))
                addView(cols, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { press(this, "bus") }
            }
            hub.addView(bus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        }

        // ----- weather: now, and the next 6 hours -----
        if (Weather.ready()) {
            val icon = IconView(this, "sun").apply { color = Look.TURMERIC }
            val temp = t(56f, 700, tight = true); val desc = t(23f, 600); val range = t(20f, 500, color = Look.MUTED)
            wxIcon = icon; wxTemp = temp; wxDesc = desc; wxRange = range
            val now = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
                addView(icon, LinearLayout.LayoutParams(px(44f), px(44f)))
                addView(temp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; addView(desc); addView(range)
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
            }
            val hours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(6) {
                val h = t(18f, 500, color = Look.MUTED, center = true)
                val i = IconView(this, "moon").apply { color = Look.MUTED }
                val v = t(24f, 700, center = true)
                wxHours.add(Triple(h, i, v))
                hours.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(h); addView(i, LinearLayout.LayoutParams(px(20f), px(20f)).apply { setMargins(0, px(2f), 0, px(2f)) }); addView(v)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val wx = panel(LinearLayout.VERTICAL).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18f), px(12f), px(18f), px(12f))
                addView(now, LinearLayout.LayoutParams(-1, -2))
                addView(View(this@MainActivity).apply { setBackgroundColor(Look.LINE) }, LinearLayout.LayoutParams(-1, px(2f)).apply { topMargin = px(10f); bottomMargin = px(10f) })
                addView(hours, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { press(this, "weather") }
            }
            hub.addView(wx, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(12f) })
        } else hub.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // ----- buttons: each light, Moyna, quiet -----
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(v: View) = bottom.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (bottom.childCount > 0) leftMargin = px(10f) })
        if (Lights.ready()) for (i in Lights.all.indices) {
            val icon = IconView(this, "bulb")
            val name = t(21f, 700, center = true); val st = t(17f, 600, center = true)
            val box = tile(Look.PANEL, 20f).apply {
                addView(icon, LinearLayout.LayoutParams(px(28f), px(28f)))
                addView(name); addView(st)
            }
            box.setOnClickListener { press(box, "light:$i") }
            lightTiles.add(LightTile(i, box, icon, name, st)); add(box)
        }
        talkLabel = t(21f, 700, color = Look.INDIGO, center = true)
        add(tile(Look.COTTON, 50f).apply {
            addView(IconView(this@MainActivity, "mic").apply { color = Look.INDIGO }, LinearLayout.LayoutParams(px(34f), px(34f)))
            addView(talkLabel)
            setOnClickListener { press(this, "talk") }
        })
        quietLabel = t(21f, 700, center = true)
        quietTile = tile(Look.PANEL, 20f).apply {
            addView(IconView(this@MainActivity, "moon"), LinearLayout.LayoutParams(px(28f), px(28f)).apply { bottomMargin = px(4f) })
            addView(quietLabel)
            setOnClickListener { press(this, "quiet") }
        }
        add(quietTile)
        hub.addView(bottom, LinearLayout.LayoutParams(-1, px(100f)).apply { topMargin = px(12f) })

        // ----- the Moyna panel: orb and words, then the Stop button (or a hint while she listens) -----
        orb = OrbView(this)
        sheetTitle = t(42f, 700); sheetSub = t(22f, 500, color = Look.MUTED)
        val sheetTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(orb, LinearLayout.LayoutParams(px(112f), px(112f)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(sheetTitle); addView(sheetSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4f) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(22f) })
        }
        stopLabel = t(32f, 700, color = Color.WHITE)
        stop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#D64545"), 42f)
            addView(View(this@MainActivity).apply { background = rounded(Color.WHITE, 5f) }, LinearLayout.LayoutParams(px(24f), px(24f)))
            addView(stopLabel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
            setOnClickListener { MoynaHub.engine?.stopButton() }
        }
        hint = t(22f, 500, color = Look.MUTED, center = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 42f * scale; setStroke(px(2f), Look.DASH, 7f * scale, 6f * scale) }
        }
        val action = FrameLayout(this).apply {
            addView(stop, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-1, -1))
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2B3560")); val r = 28f * scale
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(px(26f), px(30f), px(26f), px(26f))
            isClickable = true   // taps on the panel don't reach the tiles under it
            addView(sheetTop, LinearLayout.LayoutParams(-1, -2))
            addView(action, LinearLayout.LayoutParams(-1, px(84f)).apply { topMargin = px(26f) })
        }
        dim = View(this).apply { setBackgroundColor(Color.argb(158, 27, 35, 64)); visibility = View.GONE; isClickable = true }

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16f), px(14f), px(16f), px(18f))
            addView(arc, LinearLayout.LayoutParams(-1, px(136f)))
            addView(clockRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(hub, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(12f) })
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Look.INDIGO)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(dim, FrameLayout.LayoutParams(-1, -1))
            addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        buildPage()
        root.apply {
            addView(pageDim, FrameLayout.LayoutParams(-1, -1))
            addView(page, FrameLayout.LayoutParams(-1, -1).apply { topMargin = px(52f) })
        }
        setContentView(root)
        MoynaHub.locked = getSharedPreferences("moyna", Context.MODE_PRIVATE).getBoolean("kid_lock", false)
        handleTestIntent(intent)
    }

    /** Text in Anek Bangla: size in design pixels, weight 100-800, width 75-125. */
    private fun t(size: Float, weight: Int, width: Int = 100, color: Int = Look.COTTON, center: Boolean = false, tight: Boolean = false) = TightText(this, tight).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        typeface = Look.font(this@MainActivity, weight, width)
        setTextColor(color); includeFontPadding = false
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        isFallbackLineSpacing = false
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { cornerRadius = radius * scale; setColor(color) }

    private fun panel(orientation: Int) = LinearLayout(this).apply { this.orientation = orientation; background = rounded(Look.PANEL, 20f) }

    private fun tile(color: Int, radius: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = rounded(color, radius)
    }

    /** A short dip so a press is seen, then tell Moyna. */
    private fun press(v: View, id: String) {
        v.alpha = 0.5f; v.animate().alpha(1f).setDuration(350).start()
        tileSub = when (id) {
            "bus" -> tr("Bus times", "বাসের সময়"); "weather" -> tr("Weather", "আবহাওয়া")
            "prayer" -> tr("Prayer times", "নামাজের সময়"); else -> null
        }
        // Prayer, bus and weather open a page; the others (talk, quiet, lights) go to Moyna.
        if (id == "prayer" || id == "bus" || id == "weather") { openPage(id); return }
        MoynaHub.engine?.tileTapped(id)
    }

    private val hourFmt = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH)

    /** "4:37 PM" in English; "৪:৩৭" in Bengali (short = no AM/PM). */
    private fun clockText(x: LocalTime, en: Boolean, short: Boolean = false): String {
        val h = if (x.hour % 12 == 0) 12 else x.hour % 12
        return if (en) "$h:${"%02d".format(x.minute)}${if (short) "" else if (x.hour < 12) " AM" else " PM"}"
        else "${HubData.bn(h)}:${HubData.bn(x.minute).padStart(2, '০')}"
    }

    /** The next prayer, its time, and the one after it. Null when prayer times are not known. */
    private fun prayerLine(en: Boolean): Triple<String, String, String>? {
        val now = Prayer.now()
        val today = Prayer.day(now.toLocalDate()) ?: return null
        val tomorrow = Prayer.day(today.date.plusDays(1))
        val list = today.prayers().map { it to today.date } + (tomorrow?.prayers()?.map { it to tomorrow.date } ?: emptyList())
        val i = list.indexOfFirst { LocalDateTime.of(it.second, it.first.third).isAfter(now) }
        if (i < 0) return null
        fun name(p: Triple<String, String, LocalTime>) = if (en) p.first.replaceFirstChar { it.uppercase() } else p.second
        val next = list[i].first
        val star = if (today.calculated) "*" else ""
        val after = list.getOrNull(i + 1)?.first?.let { if (en) "then ${name(it)} ${clockText(it.third, true, true)}" else "তারপর ${name(it)} ${clockText(it.third, false)}" } ?: ""
        return Triple(name(next), clockText(next.third, en) + star, after)
    }

    /** Put the latest prayer, bus and weather information on the screen, in the screen language. */
    private fun fillTiles() {
        val en = english()
        arc.english = en; arc.invalidate()
        // In Ramadan the prayer row counts down to the end of sehri and to iftar.
        val cd = Prayer.countdown(en)
        if (cd != null) {
            prayerName.text = cd.first.substringBefore(' '); prayerTime.text = cd.first.substringAfter(' ')
            prayerAfter.text = if (en) "in ${cd.second}" else "আর ${cd.second}"
        } else {
            val p = prayerLine(en)
            prayerName.text = p?.first ?: tr("Prayer", "নামাজ"); prayerTime.text = p?.second ?: ""; prayerAfter.text = p?.third ?: ""
        }
        val fresh = Bus.ageSeconds() <= 180
        for ((line, first, rest) in busCols) {
            val times = if (fresh) Bus.tileTimes(line, en) else emptyList()
            val big = times.firstOrNull() ?: if (fresh) tr("No bus", "বাস নেই") else "…"
            // The hourglass (bus has not left the terminal yet) is drawn small beside the minutes.
            first.text = android.text.SpannableString(big).apply {
                val i = big.indexOf('⏳')
                if (i >= 0) setSpan(android.text.style.RelativeSizeSpan(0.5f), i - 1, big.length, 0)
            }
            rest.text = if (times.size > 1) tr("then ", "তারপর ") + times.drop(1).joinToString(", ") + tr("", " মি") else ""
        }
        if (Weather.has()) {
            wxIcon?.set(Weather.kind(Weather.code, Weather.isDay))
            wxTemp?.text = "${Weather.tempF}°"
            wxDesc?.text = Weather.label(Weather.code, Weather.isDay, en)
            wxRange?.text = tr("high ${Weather.highF}°, low ${Weather.lowF}°", "সর্বোচ্চ ${Weather.highF}°, সর্বনিম্ন ${Weather.lowF}°")
            val hours = Weather.hours
            wxHours.forEachIndexed { i, (h, icon, v) ->
                val w = hours.getOrNull(i)
                h.text = w?.time?.format(hourFmt)?.lowercase(Locale.ENGLISH) ?: ""
                icon.visibility = if (w == null) View.INVISIBLE else View.VISIBLE
                if (w != null) icon.set(Weather.kind(w.code, w.isDay))
                v.text = if (w == null) "" else "${w.tempF}°"
            }
        } else {
            wxTemp?.text = "…"; wxDesc?.text = tr("Weather", "আবহাওয়া"); wxRange?.text = ""
        }
        talkLabel.text = tr("Moyna", "ময়না")
        quietLabel.text = tr("Quiet", "চুপ")
        dhakaLabel.text = tr("Dhaka", "ঢাকা")
        fillLights()
    }

    /** Light buttons: turmeric with "ON" when the light is on, plain with "OFF" when off, "…" while unknown. */
    private fun fillLights() {
        val en = english()
        for (lt in lightTiles) {
            val l = Lights.get(lt.index) ?: continue
            val on = l.on == true
            lt.name.text = if (en) l.nameEn else l.nameBn
            lt.state.text = when { l.busy -> "…"; l.on == true -> tr("ON", "চালু"); l.on == false -> tr("OFF", "বন্ধ"); else -> "?" }
            lt.name.setTextColor(if (on) Look.INDIGO else Look.COTTON)
            lt.state.setTextColor(if (on) Look.INDIGO else Look.MUTED)
            lt.icon.color = if (on) Look.INDIGO else Look.MUTED
            (lt.box.background as GradientDrawable).setColor(if (on) Look.TURMERIC else Look.PANEL)
        }
    }
    private var lightsCheckedAt = 0L

    // ---------- pages that slide up when the prayer, bus or weather tile is tapped ----------

    private lateinit var pageDim: View
    private lateinit var page: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageSub: TextView
    private lateinit var pageBody: LinearLayout
    private var openPageId: String? = null
    private var duaEnglish = false

    /** Build the (empty) page; it is filled when a tile is tapped. */
    private fun buildPage() {
        pageTitle = t(36f, 700); pageSub = t(20f, 500, color = Look.MUTED)
        val close = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.CARD) }
            addView(IconView(this@MainActivity, "close"), FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
            contentDescription = "Close"
            setOnClickListener { closePage() }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; addView(pageTitle); addView(pageSub)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close, LinearLayout.LayoutParams(px(56f), px(56f)))
        }
        pageBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(pageBody, FrameLayout.LayoutParams(-1, -2))
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true
            background = GradientDrawable().apply {
                setColor(Look.SHEET); val r = 28f * scale
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(px(20f), px(22f), px(20f), px(20f))
            addView(head, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(16f) })
        }
        pageDim = View(this).apply {
            setBackgroundColor(Color.argb(158, 27, 35, 64)); visibility = View.GONE
            setOnClickListener { closePage() }
        }
    }

    private fun openPage(id: String) {
        Events.log("tile", "id" to id)
        if (id == "bus" && Bus.ready() && Bus.ageSeconds() > 30) Bus.refresh()
        if (id == "weather" && Weather.ready() && Weather.ageMinutes() > 10) Weather.refresh()
        val wasOpen = openPageId != null
        openPageId = id
        if (!wasOpen) duaEnglish = false   // the dua always opens in Bengali
        fillPage()
        if (wasOpen) return
        page.animate().cancel(); pageDim.animate().cancel()
        page.visibility = View.VISIBLE; pageDim.visibility = View.VISIBLE
        page.translationY = root.height.toFloat(); pageDim.alpha = 0f
        page.animate().translationY(0f).setDuration(240).start()
        pageDim.animate().alpha(1f).setDuration(240).start()
    }

    private fun closePage() {
        if (openPageId == null) return
        openPageId = null
        page.animate().translationY(root.height.toFloat()).setDuration(200).withEndAction { page.visibility = View.GONE }.start()
        pageDim.animate().alpha(0f).setDuration(200).withEndAction { pageDim.visibility = View.GONE }.start()
    }

    private fun card(color: Int = Look.CARD, radius: Float = 22f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(color, radius)
        setPadding(px(20f), px(16f), px(20f), px(16f))
    }

    private fun divider() = View(this).apply { setBackgroundColor(Look.CARD_LINE) }

    private fun addBlock(v: View, top: Float = 14f) =
        pageBody.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (pageBody.childCount > 0) topMargin = px(top) })

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
        views.forEach { addView(it) }
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    /** Fill the open page with fresh information. */
    private fun fillPage() {
        pageBody.removeAllViews()
        when (openPageId) {
            "prayer" -> fillPrayerPage()
            "bus" -> fillBusPage()
            "weather" -> fillWeatherPage()
        }
    }

    private fun fillPrayerPage() {
        val en = english()
        pageTitle.text = tr("Prayer", "নামাজ"); pageSub.text = Hijri.today(en)
        // In Ramadan: the sehri / iftar countdown first.
        Prayer.countdown(en)?.let { (what, left) ->
            addBlock(card(Look.TURMERIC).apply {
                addView(t(26f, 700, color = Look.INDIGO).apply { text = "$what · ${if (en) "in" else "আর"} $left" })
            })
        }
        val now = Prayer.now()
        val day = Prayer.day(now.toLocalDate())
        if (day == null) { addBlock(t(22f, 500).apply { text = tr("Prayer times are not known.", "নামাজের সময় জানা নেই।") }); return }
        // After Isha the next prayer is tomorrow's Fajr: show tomorrow's time there.
        val afterIsha = !now.toLocalTime().isBefore(day.isha)
        val fajr = if (afterIsha) Prayer.day(day.date.plusDays(1))?.fajr ?: day.fajr else day.fajr
        val times = listOf(
            Triple(tr("Fajr", "ফজর"), fajr, true), Triple(tr("Sunrise", "সূর্যোদয়"), day.sunrise, false),
            Triple(tr("Zuhr", "যোহর"), day.zuhr, true), Triple(tr("Asr", "আসর"), day.asr, true),
            Triple(tr("Maghrib", "মাগরিব"), day.maghrib, true), Triple(tr("Isha", "এশা"), day.isha, true))
        val nextIdx = if (afterIsha) 0 else times.indexOfFirst { it.third && it.second.isAfter(now.toLocalTime()) }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 3) {
                val i = r * 3 + c
                val (name, time, _) = times[i]
                val next = i == nextIdx
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; background = rounded(if (next) Look.TURMERIC else Look.CARD, 16f)
                    setPadding(px(14f), px(10f), px(14f), px(10f))
                    addView(t(18f, if (next) 600 else 500, color = if (next) Look.INDIGO else Look.MUTED).apply {
                        text = if (next) "$name · ${tr("next", "পরের")}" else name
                    })
                    addView(t(27f, 700, color = if (next) Look.INDIGO else Look.COTTON).apply { text = clockText(time, en, short = true) })
                }
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = px(10f) })
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = px(10f) })
        }
        addBlock(grid)

        // Dua of the day: Arabic, how to say it, what it means. A switch changes the last two to English.
        val dua = Duas.today() ?: return
        val box = card()
        box.addView(row(
            t(22f, 700, color = Look.TURMERIC).apply { text = tr("Dua of the day", "আজকের দোয়া") },
            spacer(),
            t(18f, 500, color = Look.MUTED).apply { text = "${dua.surah} ${dua.ref}" }))
        box.addView(TextView(this).apply {
            text = dua.arabic; setTextColor(Look.COTTON)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * scale); setLineSpacing(0f, 1.35f)
            textDirection = View.TEXT_DIRECTION_RTL; gravity = Gravity.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        box.addView(t(22f, 500).apply { text = if (duaEnglish) dua.sayEn else dua.sayBn; setLineSpacing(0f, 0.85f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        box.addView(divider(), LinearLayout.LayoutParams(-1, px(2f)).apply { topMargin = px(12f) })
        box.addView(t(20f, 500, color = Look.SOFT).apply { text = if (duaEnglish) dua.en else dua.bn; setLineSpacing(0f, 0.85f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        fun pill(label: String, on: Boolean, english: Boolean) = t(19f, 700, color = if (on) Look.INDIGO else Look.MUTED).apply {
            text = label; setPadding(px(18f), px(8f), px(18f), px(8f))
            if (on) background = rounded(Look.TURMERIC, 18f)
            setOnClickListener { duaEnglish = english; fillPage() }
        }
        val switch = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(Look.INDIGO, 22f); setPadding(px(4f), px(4f), px(4f), px(4f))
            addView(pill("বাংলা", !duaEnglish, false)); addView(pill("English", duaEnglish, true))
        }
        box.addView(row(switch, spacer(), t(15f, 500, color = Color.parseColor("#8F96B2")).apply { text = "Quran text: tanzil.net" }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addBlock(box, 16f)
    }

    /** "2 stops away" as MTA says it, or in Bengali. */
    private fun awayText(a: BusArrival, en: Boolean): String {
        if (a.atTerminal) return tr("at the first stop", "প্রথম স্টপে, এখনো ছাড়েনি")
        val s = a.away.lowercase(Locale.ENGLISH)
        if (en || s.isBlank()) return a.away
        Regex("([0-9.]+) stops? away").find(s)?.let { return "আর ${it.groupValues[1]} স্টপ" }
        Regex("([0-9.]+) miles? away").find(s)?.let { return "${it.groupValues[1]} মাইল দূরে" }
        return when { "approaching" in s -> "এসে পড়ছে"; "at stop" in s -> "স্টপে আছে"; else -> a.away }
    }

    private fun fillBusPage() {
        val en = english()
        pageTitle.text = tr("Buses", "বাস")
        val age = Bus.ageSeconds()
        pageSub.text = when {
            age == Long.MAX_VALUE -> tr("Checking…", "দেখছি…")
            age < 60 -> tr("Live from MTA Bus Time, updated just now", "এইমাত্র দেখা")
            else -> tr("Updated ${age / 60} min ago", "${HubData.bn((age / 60).toInt())} মিনিট আগে দেখা")
        }
        for ((line, _) in Bus.stops) {
            val list = Bus.next[line] ?: emptyList()
            val box = card()
            // MTA writes destinations in capitals ("BEDFORD PARK PAUL AV"): show them in normal case.
            val dest = (list.firstOrNull { it.destination.isNotBlank() }?.destination ?: "").lowercase(Locale.ENGLISH)
                .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            box.addView(row(
                t(26f, 700, color = Look.TEAL).apply { text = line.replace("BX", "Bx") },
                t(20f, 500, color = Look.MUTED).apply { text = if (dest.isBlank()) "" else "  → $dest"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }))
            box.addView(divider(), LinearLayout.LayoutParams(-1, px(2f)).apply { topMargin = px(8f); bottomMargin = px(4f) })
            if (list.isEmpty()) box.addView(t(22f, 500, color = Look.MUTED).apply { text = tr("No bus coming soon", "এখন কোনো বাস আসছে না") },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            list.forEachIndexed { i, a ->
                val first = i == 0
                val mins = if (a.minutes <= 0 && !a.atTerminal) tr("Now", "এখন")
                    else if (en) "${a.minutes} min" else "${HubData.bn(a.minutes)} মি"
                box.addView(row(
                    t(if (first) 40f else 30f, 700, 85, tight = true).apply { text = mins },
                    spacer(),
                    t(if (first) 21f else 20f, if (first) 600 else 500, color = if (first) Look.TURMERIC else Look.MUTED).apply { text = awayText(a, en) }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            }
            addBlock(box)
        }
    }

    private fun fillWeatherPage() {
        val en = english()
        pageTitle.text = tr("Weather", "আবহাওয়া")
        if (!Weather.has()) { pageSub.text = tr("Checking…", "দেখছি…"); return }
        pageSub.text = tr("Feels like ${Weather.feelsF}°", "অনুভূত হচ্ছে ${Weather.feelsF}°")
        val icon = IconView(this, Weather.kind(Weather.code, Weather.isDay)).apply { color = Look.TURMERIC; under = Look.SHEET }
        val desc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(t(23f, 600).apply { text = Weather.label(Weather.code, Weather.isDay, en) })
            addView(t(19f, 500, color = Look.MUTED).apply { text = tr("high ${Weather.highF}°, low ${Weather.lowF}°", "সর্বোচ্চ ${Weather.highF}°, সর্বনিম্ন ${Weather.lowF}°") })
        }
        addBlock(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(icon, LinearLayout.LayoutParams(px(54f), px(54f)))
            addView(t(60f, 700, tight = true).apply { text = "${Weather.tempF}°" }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
            addView(desc, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14f) })
        })

        // The next 12 hours, every 2 hours, with the chance of rain.
        val hours = card().apply { setPadding(px(18f), px(10f), px(18f), px(10f)) }
        fun hourRow(time: View, ic: View?, temp: View, bar: View, pct: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
            addView(time, LinearLayout.LayoutParams(px(70f), -2))
            addView(ic ?: View(this@MainActivity), LinearLayout.LayoutParams(px(22f), px(22f)).apply { rightMargin = px(12f) })
            addView(temp, LinearLayout.LayoutParams(px(56f), -2))
            addView(bar, LinearLayout.LayoutParams(0, if (bar is BarView) px(10f) else -2, 1f).apply { rightMargin = px(10f) })
            addView(pct, LinearLayout.LayoutParams(px(52f), -2))
        }
        hours.addView(hourRow(t(16f, 500, color = Look.MUTED).apply { text = tr("Time", "সময়") }, null,
            t(16f, 500, color = Look.MUTED).apply { text = tr("Temp", "তাপ") },
            t(16f, 500, color = Look.MUTED).apply { text = tr("Chance of rain", "বৃষ্টির সম্ভাবনা") },
            View(this)), LinearLayout.LayoutParams(-1, px(36f)))
        for (i in 0..12 step 2) {
            val w = Weather.hours.getOrNull(i) ?: break
            val h = w.time.hour; val h12 = if (h % 12 == 0) 12 else h % 12
            val wet = w.rainChance >= 40
            hours.addView(hourRow(
                t(20f, 500, color = Look.MUTED).apply { text = "$h12 ${if (h < 12) "am" else "pm"}" },
                IconView(this, Weather.kind(w.code, w.isDay)).apply { color = if (wet) Look.TEAL else Look.MUTED; under = Look.CARD },
                t(23f, 700).apply { text = "${w.tempF}°" },
                BarView(this).apply { percent = w.rainChance },
                t(19f, if (wet) 700 else 500, color = if (wet) Look.COTTON else Look.MUTED).apply { text = "${w.rainChance}%"; gravity = Gravity.END }),
                LinearLayout.LayoutParams(-1, px(40f)))
        }
        addBlock(hours)

        // Tomorrow, and sunrise / sunset.
        val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        fun small(label: String, big: String, under: String) = card(radius = 18f).apply {
            setPadding(px(16f), px(12f), px(16f), px(12f))
            addView(t(18f, 500, color = Look.MUTED).apply { text = label })
            addView(t(26f, 700).apply { text = big })
            addView(t(19f, 500).apply { text = under })
        }
        two.addView(small(tr("Tomorrow", "আগামীকাল"), "${Weather.tomorrowHighF}° / ${Weather.tomorrowLowF}°",
            Weather.label(Weather.tomorrowCode, true, en)), LinearLayout.LayoutParams(0, -2, 1f))
        val rise = Weather.sunrise; val set = Weather.sunset
        if (rise != null && set != null) {
            val light = java.time.Duration.between(rise, set).toMinutes()
            two.addView(small(tr("Sunrise · sunset", "সূর্যোদয় · সূর্যাস্ত"), "${clockText(rise, en, true)} · ${clockText(set, en, true)}",
                tr("${light / 60} h ${light % 60} min of daylight", "দিনের আলো ${HubData.bn((light / 60).toInt())} ঘ ${HubData.bn((light % 60).toInt())} মি")),
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(12f) })
        }
        addBlock(two)

        Weather.tip(en)?.let { tip ->
            addBlock(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
                background = rounded(Look.INDIGO, 18f); setPadding(px(18f), px(14f), px(18f), px(14f))
                addView(IconView(this@MainActivity, "rain").apply { color = Look.TEAL; under = Look.INDIGO }, LinearLayout.LayoutParams(px(30f), px(30f)))
                addView(t(21f, 500).apply { text = tip; setLineSpacing(0f, 1.15f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(14f) })
            })
        }
    }

    // ---------- life cycle ----------

    override fun onStart() {
        super.onStart()
        MoynaHub.listener = { s, d -> runOnUiThread { show(s, d) } }
        show(MoynaHub.state, MoynaHub.detail)
        HubData.onChange = { runOnUiThread { fillTiles(); if (openPageId == "bus" || openPageId == "weather") fillPage() } }
        Lights.listener = { runOnUiThread { fillLights() } }
        lightsCheckedAt = 0L
        Prayer.load(applicationContext); Duas.load(applicationContext)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MoynaService.start(this)   // (re)start while visible, so the mic is allowed in the background
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        lastTouch = SystemClock.elapsedRealtime()
        ui.removeCallbacks(tick); tick.run()
    }

    override fun onResume() {
        super.onResume()
        Kiosk.enter(this)
    }

    override fun onStop() {
        MoynaHub.listener = null
        HubData.onChange = null
        Lights.listener = null
        ui.removeCallbacks(tick)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) MoynaService.start(this)
    }

    // ---------- clock, and keeping the tiles fresh ----------

    private val hmFmt = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)
    private val aFmt = DateTimeFormatter.ofPattern("a", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)

    private val tick = object : Runnable {
        override fun run() {
            val now = ZonedDateTime.now()
            clock.text = now.format(hmFmt); ampm.text = now.format(aFmt)
            date.text = now.format(dateFmt)
            val dk = now.withZoneSameInstant(ZoneId.of("Asia/Dhaka"))
            dhaka.text = dk.format(hmFmt); dhakaAmPm.text = dk.format(aFmt)
            applyLook()
            // Live data only while the screen is lit in the daytime: no polling in the dark night look.
            // Kid lock: if the power button woke the screen, switch it off again after a few seconds.
            if (MoynaHub.locked && screenOffWorks &&
                SystemClock.elapsedRealtime() - lastTouch > MoynaConfig.KID_LOCK_AWAKE_SECONDS * 1000L) {
                lastTouch = SystemClock.elapsedRealtime(); screenOffWorks = Kiosk.screenOff(this@MainActivity)
            }
            if (!(night && !awake()) && !MoynaHub.locked) {
                if (Bus.ready() && Bus.ageSeconds() >= MoynaConfig.BUS_REFRESH_SECONDS) Bus.refresh()
                if (Weather.ready() && Weather.ageMinutes() >= MoynaConfig.WEATHER_REFRESH_MINUTES) Weather.refresh()
                // Lights can change from Alexa, their own timers or the wall: check every 30 s.
                if (Lights.ready() && SystemClock.elapsedRealtime() - lightsCheckedAt > 30_000) {
                    lightsCheckedAt = SystemClock.elapsedRealtime(); Lights.refreshAll()
                }
            }
            fillTiles()
            ui.postDelayed(this, 5_000)
        }
    }

    // ---------- day / night and dimming ----------

    private var night = false
    private var lastTouch = 0L
    private var forceNight: Boolean? = null   // set from the PC for screenshots (see handleTestIntent)

    /** Night = by the clock, from NIGHT_FROM_HOUR until NIGHT_UNTIL_HOUR. */
    private fun updateNight() {
        val was = night
        val h = ZonedDateTime.now().hour
        val from = MoynaConfig.NIGHT_FROM_HOUR; val until = MoynaConfig.NIGHT_UNTIL_HOUR
        night = forceNight ?: if (from > until) (h >= from || h < until) else (h in from until until)
        if (was != night) Log.i("Moyna", "screen: ${if (night) "night" else "day"} look")
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
        val bright = if (MoynaHub.locked) MoynaConfig.BRIGHT_NIGHT else b
        if (lp.screenBrightness != bright) { lp.screenBrightness = bright; window.attributes = lp }
        showSystemBars(!MoynaHub.locked)
        // Kid lock: let the phone's own screen timeout switch the screen off (also a backup for screenOff()).
        if (MoynaHub.locked) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (MoynaHub.locked) {
            // Kid lock: all black, nothing to see or press.
            root.setBackgroundColor(Color.BLACK)
            closePage()
            column.visibility = View.INVISIBLE; dim.visibility = View.GONE; sheet.visibility = View.GONE; sheetShown = false
            return
        }
        column.visibility = View.VISIBLE

        // The dark night look: only the clock and Dhaka time, dim, on black.
        root.setBackgroundColor(if (darkLook) Color.BLACK else background())
        val ink = if (darkLook) Color.rgb(110, 110, 120) else Look.COTTON
        clock.setTextColor(ink); date.setTextColor(ink)
        ampm.setTextColor(if (darkLook) ink else Look.TURMERIC)
        dhaka.setTextColor(if (darkLook) ink else Color.WHITE); dhakaAmPm.setTextColor(if (darkLook) ink else Color.WHITE)
        dhakaLabel.setTextColor(if (darkLook) ink else Look.MAP_LABEL)
        map.dim = darkLook
        arc.visibility = if (darkLook) View.INVISIBLE else View.VISIBLE
        status.visibility = if (state == MoynaState.SLEEPING && !darkLook && !inTalk) View.VISIBLE else View.GONE
        hub.visibility = if (darkLook) View.INVISIBLE else View.VISIBLE
        showSheet(inTalk)
        // A page closes when Moyna starts talking, at night, or after 2 minutes untouched.
        if (openPageId != null && (inTalk || darkLook || SystemClock.elapsedRealtime() - lastTouch > 120_000)) closePage()
    }

    /** The background stays the hub's indigo; what Moyna is doing shows in the bottom panel. */
    private fun background() = Look.INDIGO

    /** Slide the Moyna panel up (and dim the hub), or back down. */
    private fun showSheet(on: Boolean) {
        if (on == sheetShown) return
        sheetShown = on
        sheet.animate().cancel(); dim.animate().cancel()
        if (on) {
            sheet.visibility = View.VISIBLE; dim.visibility = View.VISIBLE
            sheet.translationY = px(320f).toFloat(); dim.alpha = 0f
            sheet.animate().translationY(0f).setDuration(220).start()
            dim.animate().alpha(1f).setDuration(220).start()
        } else {
            sheet.animate().translationY(px(320f).toFloat()).setDuration(200).withEndAction { sheet.visibility = View.GONE }.start()
            dim.animate().alpha(0f).setDuration(200).withEndAction { dim.visibility = View.GONE }.start()
            tileSub = null
        }
    }

    // A touch while the screen is in its dark night look only wakes the screen; it does not press anything.
    private var swallow = false
    private val openAdmin = Runnable { askPin() }
    private val unlock = Runnable { setKidLock(false) }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val hold = MoynaConfig.HOLD_SECONDS * 1000L
        if (MoynaHub.locked) {
            // Kid lock: every touch is ignored. Only if the screen can't be switched off (no device owner),
            // a finger held anywhere for HOLD_SECONDS unlocks instead of the volume button.
            if (!screenOffWorks) when (ev.action) {
                MotionEvent.ACTION_DOWN -> ui.postDelayed(unlock, hold)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(unlock)
            }
            return true
        }
        // Hidden admin screen: keep a finger on the big clock for HOLD_SECONDS.
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                val r = android.graphics.Rect()
                if (clock.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())) ui.postDelayed(openAdmin, hold)
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
        status.text = if (s == MoynaState.SLEEPING)
            tr("Moyna is sleeping. Tap Quiet to wake her.", "ময়না ঘুমাচ্ছে, \"চুপ\" ছুঁয়ে জাগান। ${d ?: ""}") else ""
        // The Moyna panel: orb, big words, a smaller line, then Stop (while she makes sound) or a hint.
        orb.mode = when (s) {
            MoynaState.LISTENING, MoynaState.TEST -> "listen"
            MoynaState.THINKING -> "think"
            MoynaState.ERROR -> "error"
            else -> "speak"
        }
        sheetTitle.text = when (s) {
            MoynaState.LISTENING -> tr("Listening…", "শুনছি…")
            MoynaState.THINKING -> if (d == "search") tr("Looking it up…", "দেখে নিচ্ছি…") else tr("Thinking…", "ভাবছি…")
            MoynaState.SPEAKING -> if (d == "azan") tr("Azan", "আজান") else tr("Speaking…", "বলছে…")
            MoynaState.ERROR -> tr("Problem", "সমস্যা")
            MoynaState.TEST -> tr("Test", "পরীক্ষা")
            else -> sheetTitle.text.toString()
        }
        sheetSub.text = when {
            s == MoynaState.ERROR || s == MoynaState.TEST -> d ?: ""
            d == "azan" -> tr("Prayer time", "নামাজের সময়")
            tileSub != null -> tileSub
            s == MoynaState.LISTENING -> tr("Ask in Bengali or English", "বাংলা বা ইংরেজিতে বলুন")
            d == "search" -> tr("Searching Google", "গুগলে খুঁজছি")
            else -> "Moyna"
        }
        // The red button shows only while Moyna is making sound (talking, the azan, or the look-up sounds).
        val making = s == MoynaState.SPEAKING || s == MoynaState.THINKING && d == "search"
        stop.visibility = if (making) View.VISIBLE else View.INVISIBLE
        hint.visibility = if (s == MoynaState.LISTENING) View.VISIBLE else View.INVISIBLE
        stopLabel.text = tr("Stop", "থামো")
        hint.text = tr("Say “thamo” to stop", "থামাতে বলুন “থামো”")
        // The quiet button lights up while she is asleep.
        (quietTile.background as GradientDrawable).setColor(if (s == MoynaState.SLEEPING) Look.SLEEP else Look.PANEL)
        applyLook()
    }

    /** Kid lock on or off (kept across restarts). */
    private fun setKidLock(on: Boolean) {
        MoynaHub.locked = on
        getSharedPreferences("moyna", Context.MODE_PRIVATE).edit().putBoolean("kid_lock", on).apply()
        Log.i("Moyna", "kid lock ${if (on) "on" else "off"}")
        Events.log("kid_lock", "on" to on)
        ui.removeCallbacks(openAdmin)
        lastTouch = SystemClock.elapsedRealtime()
        show(state, detail); fillTiles()
        if (on) ui.postDelayed({ if (MoynaHub.locked) screenOffWorks = Kiosk.screenOff(this) }, 500)
    }
    /** False when the phone won't let Moyna switch the screen off; the kid lock is then a black screen. */
    private var screenOffWorks = true

    /** Hide the back button and status bar during the kid lock (a swipe from the edge shows them briefly). */
    private var barsShown = true
    private fun showSystemBars(show: Boolean) {
        if (show == barsShown) return
        barsShown = show
        val c = window.insetsController ?: return
        val bars = WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars()
        if (show) c.show(bars) else {
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(bars)
        }
    }

    // Kid lock: volume-up pressed UNLOCK_PRESSES times within 4 seconds unlocks. All keys do nothing else.
    private val volumePresses = ArrayList<Long>()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!MoynaHub.locked) return super.dispatchKeyEvent(event)
        lastTouch = SystemClock.elapsedRealtime()
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            volumePresses.add(now); volumePresses.removeAll { now - it > 4_000 }
            if (volumePresses.size >= MoynaConfig.UNLOCK_PRESSES) { volumePresses.clear(); setKidLock(false) }
        }
        return true
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
                append("Screen words: ${if (english()) "English" else "Bengali"}\n")
                append("Screen: ${if (night) "night" else "day"} look\n")
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
        button("Screen words: switch English / বাংলা") {
            prefs.edit().putString("ui_lang", if (english()) "bn" else "en").apply()
            show(state, detail); fillTiles()
        }
        button("Kid lock: screen off, taps ignored (press volume-up ${MoynaConfig.UNLOCK_PRESSES} times to undo)") { dialog.dismiss(); setKidLock(true) }
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
        // --es look night|day|auto : force the night or day look (for screenshots), or go back to the clock.
        intent?.getStringExtra("look")?.let {
            intent.removeExtra("look")
            forceNight = when (it) { "night" -> true; "day" -> false; else -> null }
            if (it == "night") lastTouch = 0
            root.post { applyLook() }
        }
        // --es panel listen|think|search|speak|bus|azan|error|off : show the Moyna panel in that state (screenshots only).
        intent?.getStringExtra("panel")?.let {
            intent.removeExtra("panel")
            tileSub = if (it == "bus") tr("Bus times", "বাসের সময়") else null
            root.post {
                when (it) {
                    "listen" -> show(MoynaState.LISTENING, null); "think" -> show(MoynaState.THINKING, null)
                    "search" -> show(MoynaState.THINKING, "search"); "speak", "bus" -> show(MoynaState.SPEAKING, null)
                    "azan" -> show(MoynaState.SPEAKING, "azan"); "error" -> show(MoynaState.ERROR, "ইন্টারনেট কাজ করছে না")
                    "sleep" -> show(MoynaState.SLEEPING, "আর ৫৯ মিনিট")
                    else -> show(MoynaHub.state, MoynaHub.detail)
                }
            }
        }
        // --es page prayer|bus|weather|close : open or close a tile's page (screenshots).
        intent?.getStringExtra("page")?.let {
            intent.removeExtra("page")
            root.post { lastTouch = SystemClock.elapsedRealtime(); if (it == "close") closePage() else openPage(it) }
        }
        // --es fake_time 2027-02-20T15:30 : show the prayer tile as at that moment (try Ramadan); "off" to stop.
        intent?.getStringExtra("fake_time")?.let {
            intent.removeExtra("fake_time")
            Prayer.testNow = if (it == "off") null else try { java.time.LocalDateTime.parse(it) } catch (e: Exception) { null }
            root.post { fillTiles() }
        }
        // --es lang bn|en : switch the words on the screen (same as the admin screen switch).
        intent?.getStringExtra("lang")?.let {
            intent.removeExtra("lang")
            getSharedPreferences("moyna", Context.MODE_PRIVATE).edit().putString("ui_lang", if (it == "bn") "bn" else "en").apply()
            root.post { show(state, detail); fillTiles() }
        }
        // --es azan test : play the azan now.   --ez admin true : open the admin screen (asks for the PIN).
        intent?.getStringExtra("azan")?.let { intent.removeExtra("azan"); MoynaHub.engine?.playAzan(it) }
        if (intent?.getBooleanExtra("admin", false) == true) { intent.removeExtra("admin"); root.post { askPin() } }
        // --ez kid_lock true|false : switch the kid lock on or off (same as the admin screen button / volume-up).
        if (intent?.hasExtra("kid_lock") == true) {
            val on = intent.getBooleanExtra("kid_lock", false); intent.removeExtra("kid_lock"); root.post { setKidLock(on) }
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
