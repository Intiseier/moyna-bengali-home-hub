package app.moyna

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The look of the hub ("day arc" in kantha colors): colors, the font, and the hand-drawn pieces
 * (the sun's path through the prayers, the map of Bangladesh, line icons).
 * Sizes are written in pixels of a 480-pixel-wide screen and scaled to the real width.
 */
object Look {
    val INDIGO = Color.parseColor("#1B2340")      // background
    val PANEL = Color.parseColor("#252E52")       // tiles
    val LINE = Color.parseColor("#3A4466")        // dividers, the horizon
    val DASH = Color.parseColor("#5C6688")        // the part of the day still to come
    val COTTON = Color.parseColor("#F1ECE2")      // text
    val MUTED = Color.parseColor("#A9AFC6")       // quieter text
    val TURMERIC = Color.parseColor("#E9B949")    // the sun, a light that is on, AM/PM
    val TEAL = Color.parseColor("#4FB3BF")        // bus line names, the map's edge
    val MAP = Color.parseColor("#2B5F6E")         // the map's fill
    val MAP_LABEL = Color.parseColor("#A9C9CF")
    val SLEEP = Color.parseColor("#5A4C99")       // the quiet tile while Moyna sleeps
    val SHEET = Color.parseColor("#232C4E")       // pages that slide up over the hub
    val CARD = Color.parseColor("#2E3866")        // cards on those pages
    val CARD_LINE = Color.parseColor("#46507A")
    val SOFT = Color.parseColor("#C3C8DA")        // meanings, longer text

    private val faces = HashMap<String, Typeface>()

    /** Anek Bangla (assets/fonts, SIL Open Font License) at a weight (100-800) and width (75-125). */
    fun font(c: Context, weight: Int, width: Int = 100): Typeface = faces.getOrPut("$weight/$width") {
        try {
            Typeface.Builder(c.assets, "fonts/AnekBangla.ttf")
                .setFontVariationSettings("'wght' $weight, 'wdth' $width").build()
        } catch (e: Exception) { Typeface.DEFAULT_BOLD }
    }
}

/** The sun's path from Fajr to Isha, with the five prayers on it and the sun where it is now. */
class ArcView(c: Context) : View(c) {
    var english = true
    var dim = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val regular = Look.font(c, 500)
    private val bold = Look.font(c, 700)

    private fun pt(t: Float): Pair<Float, Float> {
        val u = 1 - t
        return (20 + 400 * t) to (u * u * 120 + 2 * u * t * -60 + t * t * 120)
    }

    override fun onDraw(cv: Canvas) {
        val k = width / 448f
        cv.save(); cv.scale(k, k); cv.translate(4f, -12f)   // the drawing spans y = 12 (the sun at noon) to 148 (the names)
        val day = Prayer.day(Prayer.now().toLocalDate())
        val now = Prayer.now().toLocalTime()
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.pathEffect = null
        p.color = Look.LINE; p.strokeWidth = 2f
        cv.drawLine(-4f, 120f, 444f, 120f, p)
        val whole = Path().apply { moveTo(20f, 120f); quadTo(220f, -60f, 420f, 120f) }
        p.color = Look.DASH; p.strokeWidth = 3f; p.pathEffect = DashPathEffect(floatArrayOf(6f, 7f), 0f)
        cv.drawPath(whole, p)
        if (day == null) { cv.restore(); return }

        val ps = day.prayers()
        fun mins(t: LocalTime) = t.hour * 60f + t.minute
        val start = mins(day.fajr); val end = mins(day.isha)
        fun frac(t: LocalTime) = ((mins(t) - start) / (end - start)).coerceIn(0f, 1f)
        val tNow = frac(now)
        val nextIdx = ps.indexOfFirst { it.third.isAfter(now) }   // -1 after Isha

        // The part of the day that has passed.
        if (now.isAfter(day.fajr)) {
            val (ex, ey) = pt(tNow)
            val done = Path().apply { moveTo(20f, 120f); quadTo(20f + 200f * tNow, 120f - 180f * tNow, ex, ey) }
            p.pathEffect = null; p.color = Look.TURMERIC; p.strokeWidth = 6f
            cv.drawPath(done, p)
        }
        // Thin dotted lines from the prayers in the sky down to the horizon.
        p.color = Look.LINE; p.strokeWidth = 1.5f; p.pathEffect = DashPathEffect(floatArrayOf(3f, 4f), 0f)
        for (i in 1..3) { val (x, y) = pt(frac(ps[i].third)); cv.drawLine(x, y, x, 120f, p) }
        p.pathEffect = null
        // The five prayers: filled once passed, a ring for the next one.
        ps.forEachIndexed { i, pr ->
            val (x, y) = pt(frac(pr.third))
            val passed = !pr.third.isAfter(now)
            if (passed) { p.style = Paint.Style.FILL; p.color = Look.TURMERIC; cv.drawCircle(x, y, 6f, p) }
            else {
                p.style = Paint.Style.FILL; p.color = Look.INDIGO; cv.drawCircle(x, y, if (i == nextIdx) 7f else 6f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = if (i == nextIdx) Look.COTTON else Look.MUTED
                cv.drawCircle(x, y, if (i == nextIdx) 7f else 6f, p)
            }
        }
        // The sun, between Fajr and Isha.
        if (!now.isBefore(day.fajr) && now.isBefore(day.isha)) {
            val (sx, sy) = pt(tNow)
            p.style = Paint.Style.FILL; p.color = Look.TURMERIC
            p.alpha = 64; cv.drawCircle(sx, sy, 17f, p); p.alpha = 255
            cv.drawCircle(sx, sy, 12f, p)
        }
        // Names under the horizon, pushed apart so they never overlap.
        val names = ps.map { if (english) it.first.replaceFirstChar { c -> c.uppercase() } else it.second }
        val widths = names.mapIndexed { i, n -> text.typeface = if (i == nextIdx) bold else regular; text.textSize = if (i == nextIdx) 18f else 17f; text.measureText(n) }
        val lefts = FloatArray(5) { i -> pt(frac(ps[i].third)).first - widths[i] / 2 }
        lefts[0] = maxOf(lefts[0], -4f)
        for (i in 1 until 5) lefts[i] = maxOf(lefts[i], lefts[i - 1] + widths[i - 1] + 10f)
        if (lefts[4] + widths[4] > 444f) {
            lefts[4] = 444f - widths[4]
            for (i in 3 downTo 0) lefts[i] = minOf(lefts[i], lefts[i + 1] - widths[i] - 10f)
        }
        names.forEachIndexed { i, n ->
            text.typeface = if (i == nextIdx) bold else regular; text.textSize = if (i == nextIdx) 18f else 17f
            text.color = if (i == nextIdx) Look.COTTON else Look.MUTED
            cv.drawText(n, lefts[i], 144f, text)
        }
        cv.restore()
    }
}

/** The map of Bangladesh (Natural Earth, public domain), drawn to fit the view. */
class MapView(c: Context) : View(c) {
    var dim = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val shape: Path = parse(BANGLADESH)

    override fun onDraw(cv: Canvas) {
        val k = minOf(width / 108f, height / 145f)
        cv.save(); cv.translate((width - 108f * k) / 2, (height - 145f * k) / 2); cv.scale(k, k); cv.translate(4f, 4f)
        p.style = Paint.Style.FILL; p.color = if (dim) Color.rgb(22, 30, 34) else Look.MAP
        cv.drawPath(shape, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = if (dim) Color.rgb(50, 60, 66) else Look.TEAL
        cv.drawPath(shape, p)
        cv.restore()
    }

    companion object {
        /** A path of M x y / L x y / Z commands in a 100 x 137 box (north up). */
        fun parse(d: String): Path {
            val path = Path()
            Regex("([MLZ])\\s*([-0-9.]+)?\\s*([-0-9.]+)?").findAll(d).forEach { m ->
                when (m.groupValues[1]) {
                    "M" -> path.moveTo(m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
                    "L" -> path.lineTo(m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
                    "Z" -> path.close()
                }
            }
            return path
        }

        const val BANGLADESH = "M22.3 106.1L22.4 103.9L22.3 101.8L20.6 96.2L19.5 93.4L19.6 92.4L19.5 92.0L19.0 88.4L18.3 86.1L17.9 83.7L19.6 80.2L19.0 79.7L17.0 79.2L15.2 78.6L14.8 77.7L15.6 74.3L14.6 73.0L13.3 71.6L12.9 71.1L12.4 70.4L11.8 68.7L13.0 65.1L14.7 60.9L15.0 59.3L15.3 56.5L15.4 55.5L15.2 54.4L13.4 53.2L10.3 52.7L8.1 51.7L6.8 50.2L5.7 49.6L4.4 50.0L2.6 49.4L1.2 47.9L0.0 46.1L0.1 45.2L0.5 44.0L2.7 39.3L3.6 39.1L5.6 40.0L6.3 40.0L7.6 38.2L9.4 32.8L11.9 32.8L14.2 33.0L15.7 33.2L17.2 33.1L18.8 32.6L19.7 32.0L20.1 31.1L20.0 30.4L18.0 29.3L17.3 28.6L16.8 26.4L16.2 25.6L12.4 25.5L10.4 24.5L9.3 23.6L7.4 20.7L5.0 18.5L2.7 18.0L1.8 17.3L1.3 16.2L1.6 14.6L2.3 13.1L2.8 11.5L4.6 9.3L6.7 7.4L7.7 6.2L9.0 4.8L9.2 4.0L9.0 3.2L7.9 2.4L7.1 2.1L7.0 1.6L7.5 0.2L8.6 0.0L10.7 1.3L12.9 3.3L14.3 5.2L14.3 6.6L15.2 6.9L16.0 6.9L17.5 7.6L18.9 7.4L19.9 7.7L20.5 7.6L20.8 6.8L20.1 5.5L19.5 4.7L20.1 3.8L20.8 3.6L21.6 3.8L22.6 4.6L23.4 6.2L23.5 8.8L25.2 11.0L27.5 12.7L29.2 13.4L31.3 13.9L33.1 13.4L34.0 11.8L33.6 10.4L33.9 9.1L34.6 8.4L35.8 8.5L36.6 9.5L39.1 14.9L38.5 17.3L39.1 24.0L38.5 28.3L38.6 29.3L38.9 30.0L39.3 30.3L40.0 30.3L43.0 31.1L45.5 32.0L48.3 32.9L52.4 33.5L54.9 33.3L56.2 33.3L58.7 33.5L65.4 33.1L71.0 33.0L73.2 33.7L75.0 33.9L81.2 33.4L87.4 33.2L90.7 34.6L94.4 36.9L96.5 38.6L96.8 39.5L96.6 40.4L95.9 40.8L94.6 40.8L91.7 39.7L91.2 40.1L91.3 42.3L91.2 42.7L90.6 44.7L88.8 49.2L88.5 51.3L88.1 51.8L87.7 52.1L86.3 52.2L85.2 52.5L84.8 53.2L84.1 54.8L83.6 56.3L83.0 56.8L81.4 56.0L80.4 56.1L79.1 56.4L77.9 57.3L77.0 58.4L76.0 58.8L73.1 58.6L72.6 58.7L72.2 59.5L71.9 60.5L69.6 62.8L68.8 66.6L68.1 69.0L68.2 70.9L70.1 75.8L71.4 82.2L71.9 82.8L72.4 83.0L72.6 82.9L72.6 81.6L72.6 79.9L73.3 79.6L74.1 79.9L74.8 81.3L75.7 83.8L76.6 84.8L78.0 85.1L79.7 84.5L80.9 83.4L81.4 82.1L81.0 79.7L81.0 77.8L81.7 76.1L84.5 73.5L84.9 72.7L84.8 70.5L84.8 68.4L85.8 68.2L87.2 68.6L89.1 67.6L89.6 67.5L90.4 68.6L91.6 68.4L92.6 73.0L93.5 77.0L93.5 78.9L93.7 83.0L94.1 86.3L94.8 87.1L95.6 88.9L96.4 90.9L97.0 92.1L97.4 95.9L97.8 98.6L98.5 107.2L98.8 108.9L98.9 109.8L99.0 117.6L99.2 121.0L99.9 123.7L100.0 124.8L99.3 125.6L98.6 125.8L98.0 124.5L96.5 123.4L94.4 122.3L93.5 121.6L92.4 121.9L90.8 123.6L90.2 125.1L90.5 127.2L91.0 129.4L92.0 130.6L92.1 131.9L92.5 133.7L93.1 135.3L93.3 137.0L93.0 137.0L91.7 134.8L90.5 132.4L87.5 127.9L86.5 119.8L86.5 115.8L84.4 111.1L83.0 104.6L82.5 102.9L83.2 100.8L83.3 100.0L82.9 100.2L81.9 101.3L80.5 98.7L79.6 96.4L76.1 91.6L75.1 89.4L75.0 87.4L73.5 89.5L71.4 90.9L69.3 93.1L67.9 93.8L63.4 94.2L60.8 91.2L57.1 84.0L56.6 82.4L57.1 78.2L56.3 74.2L56.3 72.0L56.0 70.6L55.3 70.9L55.1 71.9L55.2 73.4L55.0 74.7L51.7 74.4L48.7 73.8L51.4 75.9L54.2 76.4L55.7 78.3L55.9 79.8L55.8 81.5L54.3 82.6L53.0 83.4L53.3 85.0L54.9 86.9L52.9 87.4L52.4 88.7L52.3 90.5L53.3 92.1L53.7 93.3L53.5 94.4L54.4 95.6L55.8 98.0L56.3 99.8L55.7 102.2L54.9 103.2L53.6 104.1L50.6 107.2L49.1 110.7L47.9 112.4L46.3 112.7L45.7 112.0L44.4 111.0L44.4 109.3L44.8 107.9L47.4 104.6L46.0 105.1L44.4 106.0L41.9 107.8L41.1 105.6L40.6 103.5L40.6 101.0L42.6 97.3L40.3 99.2L39.7 101.5L40.0 104.2L39.7 106.2L38.8 108.7L37.6 110.3L35.7 111.2L34.8 112.7L33.5 113.9L33.5 111.6L33.1 108.7L31.7 101.8L31.4 103.3L32.1 107.6L32.1 110.4L31.0 112.6L28.9 115.0L27.2 115.3L26.3 114.9L24.8 113.5L23.2 111.4L23.0 108.0L22.3 106.1ZM59.8 106.2L56.0 107.1L54.1 106.8L57.7 100.6L57.5 97.8L57.0 95.5L55.2 93.7L55.1 92.4L54.2 90.6L53.8 88.5L55.8 87.9L57.5 89.1L57.7 89.7L58.1 91.4L58.9 93.2L61.7 96.9L61.7 99.1L60.9 104.6L59.8 106.2Z"
    }
}

/** Simple line icons drawn on a 24 x 24 grid: mic, bulb, moon, sun, cloud, partly, rain, snow, storm, fog. */
class IconView(c: Context, var kind: String) : View(c) {
    var color = Look.COTTON
        set(v) { field = v; invalidate() }
    var under = Look.PANEL        // the color behind the icon (used to hide the sun behind a cloud)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    fun set(k: String) { if (k != kind) { kind = k; invalidate() } }

    private fun circle(x: Float, y: Float, r: Float) = Path().apply { addCircle(x, y, r, Path.Direction.CW) }

    private fun cloud(dy: Float): Path {
        val a = circle(8f, 15f + dy, 3.5f)
        a.op(circle(13f, 12f + dy, 5f), Path.Op.UNION)
        a.op(circle(17.5f, 15f + dy, 3.5f), Path.Op.UNION)
        a.op(Path().apply { addRect(8f, 13f + dy, 17.5f, 18.5f + dy, Path.Direction.CW) }, Path.Op.UNION)
        return a
    }

    private fun sun(cv: Canvas, x: Float, y: Float, r: Float, ray: Float) {
        cv.drawCircle(x, y, r, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
            cv.drawLine(x + c * (r + 2.5f), y + s * (r + 2.5f), x + c * (r + 2.5f + ray), y + s * (r + 2.5f + ray), p)
        }
    }

    override fun onDraw(cv: Canvas) {
        val k = minOf(width, height) / 24f
        cv.save(); cv.translate((width - 24 * k) / 2, (height - 24 * k) / 2); cv.scale(k, k)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = color
        when (kind) {
            "close" -> { cv.drawLine(6f, 6f, 18f, 18f, p); cv.drawLine(18f, 6f, 6f, 18f, p) }
            "mic" -> {
                cv.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, p)
                cv.drawArc(RectF(5f, 4f, 19f, 18f), 0f, 180f, false, p)
                cv.drawLine(12f, 18f, 12f, 21f, p)
            }
            "bulb" -> {
                cv.drawArc(RectF(6f, 3f, 18f, 15f), 135f, 270f, false, p)
                cv.drawLine(7.76f, 13.24f, 9.5f, 15.5f, p); cv.drawLine(16.24f, 13.24f, 14.5f, 15.5f, p)
                cv.drawLine(9.5f, 15.5f, 14.5f, 15.5f, p)
                cv.drawLine(9f, 18.5f, 15f, 18.5f, p); cv.drawLine(10f, 21.5f, 14f, 21.5f, p)
            }
            "moon" -> {
                val m = circle(12f, 12f, 8f); m.op(circle(16.5f, 7.5f, 6.5f), Path.Op.DIFFERENCE)
                cv.drawPath(m, p)
            }
            "sun" -> sun(cv, 12f, 12f, 4.5f, 2.5f)
            "cloud" -> cv.drawPath(cloud(0f), p)
            "partly" -> {
                sun(cv, 8f, 8f, 3f, 1.5f)
                val cl = cloud(2f)
                p.style = Paint.Style.FILL; p.color = under; cv.drawPath(cl, p)
                p.style = Paint.Style.STROKE; p.color = color; cv.drawPath(cl, p)
            }
            "rain", "snow", "storm", "fog" -> {
                cv.drawPath(cloud(-3f), p)
                when (kind) {
                    "rain" -> { cv.drawLine(9f, 19f, 8f, 22f, p); cv.drawLine(13f, 19f, 12f, 22f, p); cv.drawLine(17f, 19f, 16f, 22f, p) }
                    "snow" -> { p.style = Paint.Style.FILL; for (x in listOf(8.5f, 12.5f, 16.5f)) cv.drawCircle(x, 20.5f, 1.1f, p) }
                    "storm" -> { val b = Path().apply { moveTo(13.5f, 16.5f); lineTo(11f, 20f); lineTo(14f, 20f); lineTo(12f, 23.5f) }; cv.drawPath(b, p) }
                    "fog" -> { cv.drawLine(5f, 19.5f, 19f, 19.5f, p); cv.drawLine(7f, 22.5f, 17f, 22.5f, p) }
                }
            }
        }
        cv.restore()
    }
}

/**
 * A one-line text that trims the font's extra room above and below the letters (Anek Bangla leaves
 * space for Bengali vowel signs). "tight" is for big numbers, which need almost none.
 */
class TightText(c: Context, private val tight: Boolean) : TextView(c) {
    private var cutTop = 0
    private var cutBottom = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (lineCount > 1) { cutTop = 0; cutBottom = 0; return }
        val fm = paint.fontMetrics
        val size = textSize
        cutTop = maxOf(0f, -fm.ascent - size * (if (tight) 0.76f else 0.98f)).toInt()
        cutBottom = maxOf(0f, fm.descent - size * (if (tight) 0.04f else 0.3f)).toInt()
        // A parent may measure again with the exact (already trimmed) height: don't trim twice.
        // A view given a fixed height (e.g. a button label) keeps TextView's own centering instead.
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            if (layoutParams?.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) { cutTop = 0; cutBottom = 0 }
            return
        }
        setMeasuredDimension(measuredWidth, maxOf(0, measuredHeight - cutTop - cutBottom))
    }

    override fun getBaseline(): Int = super.getBaseline() - cutTop

    override fun onDraw(canvas: Canvas) {
        if (cutTop == 0 && cutBottom == 0) { super.onDraw(canvas); return }
        // TextView would clip to the trimmed height, so draw the text's layout directly.
        val l = layout ?: return
        paint.color = currentTextColor
        paint.drawableState = drawableState
        canvas.save(); canvas.translate(compoundPaddingLeft.toFloat(), (extendedPaddingTop - cutTop).toFloat())
        l.draw(canvas)
        canvas.restore()
    }
}

/**
 * Moyna's "orb" in the bottom panel: what she is doing, at a glance.
 * listen = teal with a microphone, breathing; think = turmeric dots going round;
 * speak = turmeric with moving sound bars; error = red with "!".
 */
class OrbView(c: Context) : View(c) {
    var mode = "listen"
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val start = android.os.SystemClock.elapsedRealtime()

    override fun onDraw(cv: Canvas) {
        val k = minOf(width, height) / 112f
        cv.save(); cv.translate((width - 112 * k) / 2, (height - 112 * k) / 2); cv.scale(k, k)
        val t = (android.os.SystemClock.elapsedRealtime() - start) / 1000f
        val color = when (mode) { "listen" -> Look.TEAL; "error" -> Color.parseColor("#D64545"); else -> Look.TURMERIC }
        val breathe = if (mode == "listen") (Math.sin(t * 3.0).toFloat() + 1) / 2 else 0.5f
        p.style = Paint.Style.FILL; p.color = color
        p.alpha = (26 + 20 * breathe).toInt(); cv.drawCircle(56f, 56f, 50f + 4 * breathe, p)
        p.alpha = (60 + 25 * breathe).toInt(); cv.drawCircle(56f, 56f, 41f + 2 * breathe, p)
        p.alpha = 255; cv.drawCircle(56f, 56f, 31f, p)
        p.color = Look.INDIGO
        when (mode) {
            "listen" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.9f
                cv.save(); cv.translate(40f, 40f); cv.scale(1.333f, 1.333f); p.strokeWidth = 2.2f
                cv.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, p)
                cv.drawArc(RectF(5f, 4f, 19f, 18f), 0f, 180f, false, p)
                cv.drawLine(12f, 18f, 12f, 21f, p)
                cv.restore()
            }
            "speak" -> {
                p.style = Paint.Style.FILL
                for (i in 0 until 5) {
                    val h = 10f + 24f * ((Math.sin(t * 7.0 + i * 1.3).toFloat() + 1) / 2) * (if (i == 0 || i == 4) 0.5f else 1f)
                    val x = 40f + i * 8f
                    cv.drawRoundRect(RectF(x, 56f - h / 2, x + 5f, 56f + h / 2), 2.5f, 2.5f, p)
                }
            }
            "think" -> {
                p.style = Paint.Style.FILL
                for (i in 0 until 3) {
                    val a = t * 4f + i * (2 * Math.PI / 3).toFloat()
                    cv.drawCircle(56f + 13f * Math.cos(a.toDouble()).toFloat(), 56f + 13f * Math.sin(a.toDouble()).toFloat(), 4.5f, p)
                }
            }
            "error" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.WHITE
                cv.drawLine(56f, 42f, 56f, 60f, p)
                p.style = Paint.Style.FILL; cv.drawCircle(56f, 70f, 3.2f, p)
            }
        }
        cv.restore()
        if (isShown && mode != "error") postInvalidateOnAnimation()
    }
}

/** A small horizontal bar filled to [percent] (chance of rain). */
class BarView(c: Context) : View(c) {
    var percent = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val r = height / 2f
        p.color = Look.INDIGO; cv.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, p)
        if (percent > 0) {
            p.color = Look.TEAL
            cv.drawRoundRect(RectF(0f, 0f, maxOf(height.toFloat(), width * percent / 100f), height.toFloat()), r, r, p)
        }
    }
}
