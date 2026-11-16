package app.moyna

import android.content.Context
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Shared helpers for the live information on the hub: bus, weather and prayer times. */
object HubData {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }
    /** Called (on any thread) whenever bus or weather data changed, so the screen can redraw. */
    @Volatile var onChange: (() -> Unit)? = null

    fun getJson(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body!!.string()) else { Log.w("Moyna", "http ${r.code} from ${url.substringBefore('?')}"); null }
        }
    } catch (e: Exception) { Log.w("Moyna", "network problem: ${e.javaClass.simpleName} (${url.substringBefore('?')})"); null }

    fun bn(n: Int) = n.toString().map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

    /** A clock time the way it is spoken: "৪টা", "সাড়ে ৪টা", "৪টা ৩৯". */
    fun spoken(t: LocalTime): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return when (t.minute) {
            0 -> "${bn(h)}টা"
            30 -> "সাড়ে ${bn(h)}টা"
            else -> "${bn(h)}টা ${bn(t.minute)}"
        }
    }
    /** Part of the day in Bengali, for times read aloud. */
    fun dayPart(t: LocalTime) = when (t.hour) {
        in 4..5 -> "ভোর"; in 6..11 -> "সকাল"; in 12..14 -> "দুপুর"; in 15..17 -> "বিকাল"; in 18..19 -> "সন্ধ্যা"; else -> "রাত"
    }
}

// ============================== BUS ==============================

/** One upcoming bus at our stop. */
class BusArrival(val line: String, val minutes: Int, val atTerminal: Boolean, val departsIn: Int)

/**
 * Live bus times from MTA Bus Time (SIRI StopMonitoring), straight from the phone.
 * Which buses and stops: BUS_STOPS in personal.properties, as LINE:stopcode,LINE:stopcode (spoken in that order).
 */
object Bus {
    /** line -> stop code, in the order they are spoken. */
    val stops: List<Pair<String, String>> = BuildConfig.BUS_STOPS.split(',').mapNotNull {
        val p = it.trim().split(':'); if (p.size == 2 && p[0].isNotBlank()) p[0].trim().uppercase() to p[1].trim() else null
    }
    @Volatile var next: Map<String, BusArrival?> = emptyMap()   // null = no bus coming soon
    @Volatile var fetchedAt = 0L                                // elapsedRealtime of the last good answer
    @Volatile private var busy = false

    fun ready() = BuildConfig.MTA_BUSTIME_KEY.isNotBlank() && stops.isNotEmpty()
    fun ageSeconds() = if (fetchedAt == 0L) Long.MAX_VALUE else (SystemClock.elapsedRealtime() - fetchedAt) / 1000

    /** Fetch in the background; [done] gets true if every stop answered. */
    fun refresh(done: ((Boolean) -> Unit)? = null) {
        if (!ready() || busy) { done?.invoke(false); return }
        busy = true
        thread(name = "moyna-bus", isDaemon = true) {
            val out = HashMap<String, BusArrival?>()
            var ok = true
            for ((line, stop) in stops) {
                val o = HubData.getJson("https://bustime.mta.info/api/siri/stop-monitoring.json?key=${BuildConfig.MTA_BUSTIME_KEY}" +
                    "&MonitoringRef=$stop&LineRef=MTA%20NYCT_$line&MaximumStopVisits=2")
                if (o == null) { ok = false; continue }
                out[line] = try { parse(line, o) } catch (e: Exception) { Log.w("Moyna", "bus answer not understood: ${e.message}"); ok = false; null }
            }
            if (ok) { next = out; fetchedAt = SystemClock.elapsedRealtime(); HubData.onChange?.invoke() }
            busy = false
            done?.invoke(ok)
        }
    }

    private fun parse(line: String, o: JSONObject): BusArrival? {
        val visits = o.getJSONObject("Siri").getJSONObject("ServiceDelivery")
            .getJSONArray("StopMonitoringDelivery").getJSONObject(0).optJSONArray("MonitoredStopVisit") ?: return null
        val now = OffsetDateTime.now()
        for (i in 0 until visits.length()) {
            val j = visits.getJSONObject(i).getJSONObject("MonitoredVehicleJourney")
            val call = j.getJSONObject("MonitoredCall")
            val whenStr = call.optString("ExpectedArrivalTime").ifBlank { call.optString("ExpectedDepartureTime") }
                .ifBlank { call.optString("AimedArrivalTime") }
            if (whenStr.isBlank()) continue
            val mins = Duration.between(now, OffsetDateTime.parse(whenStr)).seconds
            if (mins < -30) continue                                  // already gone
            // "layover" = sitting at the terminal; "prevTrip" = still finishing its previous run.
            val progress = j.optString("ProgressStatus")
            val notLeft = "layover" in progress || "prevTrip" in progress
            val dep = j.optString("OriginAimedDepartureTime")
            val depIn = if (dep.isBlank()) 0 else (Duration.between(now, OffsetDateTime.parse(dep)).seconds / 60).toInt().coerceAtLeast(0)
            return BusArrival(line, ((mins + 30) / 60).toInt().coerceAtLeast(0), notLeft, depIn)
        }
        return null
    }

    /** "M15" -> "এম 15" (how a line name is said out loud). */
    private fun spokenLine(line: String): String {
        val words = mapOf("22" to "টুয়েন্টি টু", "36" to "থার্টি সিক্স", "5" to "ফাইভ", "27" to "টুয়েন্টি সেভেন", "39" to "থার্টি নাইন")
        val num = line.removePrefix("BX")
        return "বিএক্স " + (words[num] ?: num)
    }

    /** What Moyna says when the bus tile is tapped. */
    fun sentence(): String = stops.joinToString(" ") { (line, _) ->
        val a = next[line]; val name = spokenLine(line)
        when {
            a == null -> "$name এখন আসছে না।"
            a.atTerminal && a.departsIn > 0 -> "$name এখনো স্টেশনে আছে, ${HubData.bn(a.departsIn)} মিনিট পরে ছাড়ার কথা।"
            a.atTerminal -> "$name এখনই স্টেশন থেকে ছাড়ছে, ${HubData.bn(a.minutes)} মিনিটে আসবে।"
            a.minutes <= 0 -> "$name এখনই এসে গেছে।"
            else -> "$name আসছে ${HubData.bn(a.minutes)} মিনিটে।"
        }
    }

    /** Small text on the bus tile: one line per bus. */
    fun tileText(): String = stops.joinToString("\n") { (line, _) ->
        val a = next[line]; val n = line.removePrefix("BX")
        when {
            a == null -> "$n: —"
            a.atTerminal -> "$n: ${HubData.bn(a.minutes)} মি ⏳"
            a.minutes <= 0 -> "$n: এখন"
            else -> "$n: ${HubData.bn(a.minutes)} মি"
        }
    }

    /** The same facts in English, for Gemini when she asks about the bus by voice. */
    fun facts(): String = stops.joinToString(" ") { (line, _) ->
        val a = next[line]
        when {
            a == null -> "$line: no bus coming soon."
            a.atTerminal -> "$line: still at the terminal, scheduled to leave in ${a.departsIn} minutes, at her stop in about ${a.minutes} minutes."
            else -> "$line: arrives at her stop in ${a.minutes} minutes."
        }
    }
}

// ============================== WEATHER ==============================

class WeatherHour(val time: LocalDateTime, val tempF: Int, val code: Int, val isDay: Boolean, val rainChance: Int, val snow: Boolean)

/** Weather from Open-Meteo (free, no key) for HOME_LAT / HOME_LON in personal.properties. */
object Weather {
    @Volatile var tempF = 0
    @Volatile var feelsF = 0
    @Volatile var code = 0
    @Volatile var isDay = true
    @Volatile var highF = 0
    @Volatile var lowF = 0
    @Volatile var hours: List<WeatherHour> = emptyList()      // from the next full hour on
    @Volatile var fetchedAt = 0L
    @Volatile private var busy = false

    fun ready() = BuildConfig.HOME_LAT.isNotBlank() && BuildConfig.HOME_LON.isNotBlank()
    fun has() = fetchedAt != 0L
    fun ageMinutes() = if (fetchedAt == 0L) Long.MAX_VALUE else (SystemClock.elapsedRealtime() - fetchedAt) / 60_000

    fun refresh(done: ((Boolean) -> Unit)? = null) {
        if (!ready() || busy) { done?.invoke(false); return }
        busy = true
        thread(name = "moyna-weather", isDaemon = true) {
            val o = HubData.getJson("https://api.open-meteo.com/v1/forecast?latitude=${BuildConfig.HOME_LAT}&longitude=${BuildConfig.HOME_LON}" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,snowfall,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&temperature_unit=fahrenheit&timezone=auto&forecast_days=2")
            var ok = false
            if (o != null) try {
                val c = o.getJSONObject("current")
                val h = o.getJSONObject("hourly"); val d = o.getJSONObject("daily")
                val now = LocalDateTime.parse(c.getString("time"))
                val list = ArrayList<WeatherHour>()
                val times = h.getJSONArray("time")
                for (i in 0 until times.length()) {
                    val t = LocalDateTime.parse(times.getString(i))
                    if (!t.isAfter(now)) continue
                    list.add(WeatherHour(t, Math.round(h.getJSONArray("temperature_2m").getDouble(i)).toInt(),
                        h.getJSONArray("weather_code").getInt(i), h.getJSONArray("is_day").getInt(i) == 1,
                        h.getJSONArray("precipitation_probability").optInt(i, 0), h.getJSONArray("snowfall").optDouble(i, 0.0) > 0))
                    if (list.size >= 13) break
                }
                tempF = Math.round(c.getDouble("temperature_2m")).toInt(); feelsF = Math.round(c.getDouble("apparent_temperature")).toInt()
                code = c.getInt("weather_code"); isDay = c.getInt("is_day") == 1
                highF = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
                lowF = Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
                hours = list; fetchedAt = SystemClock.elapsedRealtime(); ok = true
                HubData.onChange?.invoke()
            } catch (e: Exception) { Log.w("Moyna", "weather answer not understood: ${e.message}") }
            busy = false
            done?.invoke(ok)
        }
    }

    private fun wet(code: Int) = code in 51..67 || code in 80..82 || code in 95..99
    private fun snowy(code: Int) = code in 71..77 || code in 85..86

    /** A picture for a weather code (WMO codes, as used by Open-Meteo). */
    fun icon(code: Int, day: Boolean) = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "☁️"
    }

    private fun feel(f: Int) = when {
        f < 32 -> "খুব ঠান্ডা"; f < 45 -> "বেশ ঠান্ডা"; f < 60 -> "একটু ঠান্ডা"; f < 75 -> "আরামদায়ক"; f < 85 -> "গরম"; else -> "খুব গরম"
    }

    /** What Moyna says when the weather strip is tapped: how it feels, then rain or snow in the next 12 hours. */
    fun sentence(): String {
        val first = "এখন ${feel(feelsF)}, ${HubData.bn(tempF)} ডিগ্রি।"
        val wetNow = wet(code); val snowNow = snowy(code)
        val next = hours.take(12).firstOrNull { snowy(it.code) || wet(it.code) && it.rainChance >= 40 }
        val second = when {
            snowNow -> "এখন বরফ পড়ছে, সাবধানে চলবেন।"
            wetNow -> "এখন বৃষ্টি হচ্ছে, ছাতা নিয়ে যাবেন।"
            next != null && snowy(next.code) -> "${HubData.dayPart(next.time.toLocalTime())} ${HubData.spoken(next.time.toLocalTime())} থেকে বরফ পড়তে পারে, সাবধানে চলবেন।"
            next != null -> "${HubData.dayPart(next.time.toLocalTime())} ${HubData.spoken(next.time.toLocalTime())} থেকে বৃষ্টি, ছাতা নিয়ে যাবেন।"
            feelsF < 45 -> "বৃষ্টি নেই, তবে জ্যাকেট পরবেন।"
            else -> "আজ বৃষ্টি নেই।"
        }
        return "$first $second"
    }

    /** The same facts in English, for Gemini when she asks about the weather by voice. */
    fun facts(): String {
        val next = hours.take(12).firstOrNull { snowy(it.code) || wet(it.code) && it.rainChance >= 40 }
        return "Now $tempF°F (feels like $feelsF°F), ${describe(code)}. Today high $highF°F, low $lowF°F. " +
            if (next == null) "No rain or snow in the next 12 hours."
            else "${if (snowy(next.code)) "Snow" else "Rain"} expected from ${next.time.toLocalTime()} (${next.rainChance}% chance)."
    }
    private fun describe(code: Int) = when (code) {
        0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "cloudy"; 45, 48 -> "foggy"
        in 51..67, in 80..82 -> "rain"; in 71..77, 85, 86 -> "snow"; in 95..99 -> "thunderstorm"; else -> "cloudy"
    }
}

// ============================== PRAYER ==============================

class PrayerDay(val date: LocalDate, val fajr: LocalTime, val sunrise: LocalTime, val zuhr: LocalTime,
                val asr: LocalTime, val maghrib: LocalTime, val isha: LocalTime, val calculated: Boolean) {
    /** The five prayers, in order (name used in settings and logs, Bengali name, time). */
    fun prayers() = listOf(Triple("fajr", "ফজর", fajr), Triple("zuhr", "যোহর", zuhr), Triple("asr", "আসর", asr),
        Triple("maghrib", "মাগরিব", maghrib), Triple("isha", "এশা", isha))
}

/**
 * Prayer times. The source of truth is the family's wall calendar, typed into CSV files that the
 * build copies into the app from private/prayer-times/ (never committed). For a month with no CSV,
 * the times are calculated (see PrayerCalc) and marked as calculated.
 */
object Prayer {
    private val table = HashMap<LocalDate, PrayerDay>()
    @Volatile private var loaded = false
    /** The last date that has calendar (not calculated) times. */
    @Volatile var lastCalendarDate: LocalDate? = null

    fun load(context: Context) {
        if (loaded) return
        try {
            for (name in context.assets.list("prayer") ?: emptyArray()) {
                if (!name.endsWith(".csv")) continue
                context.assets.open("prayer/$name").bufferedReader().forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("date")) return@forEachLine
                    val p = line.split(',')
                    if (p.size < 8) return@forEachLine
                    try {
                        fun t(s: String, pm: Boolean): LocalTime {
                            val (h, m) = s.trim().split(':').map { it.toInt() }
                            return LocalTime.of(if (pm && h != 12) h + 12 else if (!pm && h == 12) 0 else h, m)
                        }
                        val d = LocalDate.parse(p[0].trim())
                        // The calendar has no AM/PM: fajr and sunrise are AM; zuhr is AM unless it is 12:xx or 1:xx; the rest are PM.
                        val zh = p[3].trim().substringBefore(':').toInt()
                        table[d] = PrayerDay(d, t(p[1], false), t(p[2], false),
                            if (zh == 12) LocalTime.of(12, p[3].trim().substringAfter(':').toInt()) else t(p[3], zh < 10),
                            t(p[5], true), t(p[6], true), t(p[7], true), false)      // p[5] = Hanafi asr
                    } catch (e: Exception) { Log.w("Moyna", "prayer line skipped in $name: $line") }
                }
            }
        } catch (e: Exception) { Log.w("Moyna", "prayer calendar could not be read: ${e.message}") }
        lastCalendarDate = table.keys.maxOrNull()
        loaded = true
        Log.i("Moyna", "prayer calendar: ${table.size} days loaded, last ${lastCalendarDate ?: "none"}")
    }

    fun day(d: LocalDate): PrayerDay? = table[d] ?: PrayerCalc.day(d)

    /** Days of calendar left (for the "add next month" reminder in the admin screen). */
    fun calendarDaysLeft(): Long? = lastCalendarDate?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), it) }

    /** What Moyna says when the prayer tile is tapped. */
    fun sentence(now: LocalDateTime = LocalDateTime.now()): String {
        val today = day(now.toLocalDate()) ?: return "নামাজের সময় এখন জানা নেই।"
        val t = now.toLocalTime()
        val ps = today.prayers()
        fun at(x: LocalTime) = "${HubData.dayPart(x)} ${HubData.spoken(x)}"
        fun left(x: LocalDateTime): String {
            val m = Duration.between(now, x).toMinutes().toInt()
            return if (m >= 60) "আর ${HubData.bn(m / 60)} ঘণ্টা${if (m % 60 > 0) " ${HubData.bn(m % 60)} মিনিট" else ""}" else "আর ${HubData.bn(m)} মিনিট"
        }
        // Fajr is special: it ends at sunrise.
        if (!t.isBefore(today.fajr) && t.isBefore(today.sunrise)) return "এখন ফজর চলছে, সূর্য ওঠার সময় ${HubData.spoken(today.sunrise)}।"
        val next = ps.firstOrNull { it.third.isAfter(t) }
        if (next != null) {
            val current = ps.lastOrNull { !it.third.isAfter(t) && !(it.first == "fajr" && !t.isBefore(today.sunrise)) }
            val head = if (current != null) "এখন ${current.second} চলছে। " else ""
            return "${head}পরের নামাজ ${next.second}, ${at(next.third)}, ${left(LocalDateTime.of(today.date, next.third))}।"
        }
        val tomorrow = day(today.date.plusDays(1)) ?: return "এখন এশা চলছে।"
        return "এখন এশা চলছে। পরের নামাজ কাল ফজর, ${at(tomorrow.fajr)}।"
    }

    /** Small text on the prayer tile: the next prayer and its time. */
    fun tileText(now: LocalDateTime = LocalDateTime.now()): String {
        val today = day(now.toLocalDate()) ?: return ""
        val next = today.prayers().firstOrNull { it.third.isAfter(now.toLocalTime()) }
            ?: day(today.date.plusDays(1))?.prayers()?.first() ?: return ""
        val h = if (next.third.hour % 12 == 0) 12 else next.third.hour % 12
        return "${next.second} ${HubData.bn(h)}:${HubData.bn(next.third.minute).padStart(2, '০')}${if (today.calculated) "*" else ""}"
    }

    /** Today's times in English, for Gemini when she asks by voice. */
    fun facts(): String {
        val d = day(LocalDate.now()) ?: return "Prayer times are not available."
        return "Today's prayer times where she lives (${if (d.calculated) "calculated" else "from her mosque calendar"}): Fajr ${d.fajr}, sunrise ${d.sunrise}, " +
            "Zuhr ${d.zuhr}, Asr ${d.asr}, Maghrib ${d.maghrib}, Isha ${d.isha} (24-hour clock). Now it is ${LocalTime.now().withSecond(0).withNano(0)}."
    }
}
