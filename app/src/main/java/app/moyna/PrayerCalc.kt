package app.moyna

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calculated prayer times, used only for a month that has no calendar CSV.
 * Tested on 16 Nov 2026 against the MUNA "New York City" wall calendar for all 61 days of
 * November and December 2026: the North America (ISNA) method at New York City's centre, with
 * Hanafi Asr, matched every prayer within 1 minute. No other standard method came close
 * (the next best was 12 to 18 minutes off for Fajr and Isha).
 */
object PrayerCalc {
    fun day(d: LocalDate): PrayerDay? = try {
        val params = CalculationMethod.NORTH_AMERICA.parameters
        params.madhab = Madhab.HANAFI
        val pt = PrayerTimes(Coordinates(MoynaConfig.PRAYER_CALC_LAT, MoynaConfig.PRAYER_CALC_LON),
            DateComponents(d.year, d.monthValue, d.dayOfMonth), params)
        fun t(x: java.util.Date) = x.toInstant().plusSeconds(30).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
        PrayerDay(d, t(pt.fajr), t(pt.sunrise), t(pt.dhuhr), t(pt.asr), t(pt.maghrib), t(pt.isha), true)
    } catch (e: Exception) { android.util.Log.w("Moyna", "prayer calculation failed: ${e.message}"); null }
}
