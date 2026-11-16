package app.moyna

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** The big Bengali screen. Moyna itself runs in MoynaService; this only shows it and handles taps. */
class MainActivity : Activity() {

    private lateinit var big: TextView
    private lateinit var small: TextView
    private lateinit var root: android.widget.FrameLayout
    private lateinit var stop: TextView
    private lateinit var usageFill: android.view.View
    private lateinit var usageRest: android.view.View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        big = TextView(this).apply {
            textSize = 64f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        small = TextView(this).apply {
            textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(24, 48, 24, 0)
        }
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(32, 32, 32, 220)
            addView(big); addView(small)
        }
        // Big red stop sign at the bottom (shown while Moyna is listening or talking).
        val d = resources.displayMetrics.density
        stop = TextView(this).apply {
            text = "থামো"
            textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.rgb(200, 30, 30)); setStroke((4 * d).toInt(), Color.WHITE)
            }
            visibility = android.view.View.GONE
        }
        // Thin talk-time bar along the very bottom: fills as Moyna is used today, empties at midnight.
        usageFill = android.view.View(this)
        usageRest = android.view.View(this)
        val usageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(60, 255, 255, 255))
            addView(usageFill, LinearLayout.LayoutParams(0, -1, 0f))
            addView(usageRest, LinearLayout.LayoutParams(0, -1, 1f))
        }
        root = android.widget.FrameLayout(this).apply {
            addView(middle, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(usageBar, android.widget.FrameLayout.LayoutParams(-1, (8 * d).toInt(), Gravity.BOTTOM))
            addView(stop, android.widget.FrameLayout.LayoutParams((130 * d).toInt(), (130 * d).toInt(),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (36 * d).toInt() })
        }
        setContentView(root)
        handleTestIntent(intent)
    }

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

    override fun onStop() {
        MoynaHub.listener = null
        MoynaHub.usageListener = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) MoynaService.start(this)
    }

    // The red stop sign stops Moyna and puts her to sleep. Tapping anywhere else starts
    // listening (or, while Moyna talks, cuts her answer short; while asleep, wakes her).
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val r = android.graphics.Rect()
            val onStop = stop.visibility == android.view.View.VISIBLE &&
                stop.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())
            if (onStop) MoynaHub.engine?.stopButton() else MoynaHub.engine?.trigger()
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestIntent(intent)
    }

    // Wake word test, started from the PC (see README):
    //   adb shell am start -n app.moyna/.MainActivity --ei test_prompts 20
    private fun handleTestIntent(intent: Intent?) {
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

    private fun show(s: MoynaState, detail: String?) {
        val (bigText, smallText, color) = when (s) {
            MoynaState.IDLE -> Triple("ময়না শুনছে না", "\"ময়না\" বলে ডাকুন\nবা স্ক্রিনে ছুঁয়ে দিন", Color.rgb(25, 55, 50))
            MoynaState.LISTENING -> Triple("বলুন...", "ময়না শুনছে", Color.rgb(20, 110, 70))
            MoynaState.THINKING -> Triple(if (detail == "search") "একটু দেখে নিচ্ছি..." else "একটু ভাবছি...", "", Color.rgb(150, 100, 20))
            MoynaState.SPEAKING -> Triple("ময়না বলছে...", "\"ময়না থামো\" বলুন বা লাল বোতাম চাপুন", Color.rgb(30, 80, 150))
            MoynaState.ERROR -> Triple(detail ?: "সমস্যা হয়েছে", "আবার চেষ্টা করতে ছুঁয়ে দিন", Color.rgb(140, 40, 40))
            MoynaState.SLEEPING -> Triple("ময়না ঘুমাচ্ছে", "ছুঁয়ে জাগান\n\n${detail ?: ""}", Color.rgb(35, 35, 55))
            MoynaState.TEST -> Triple("পরীক্ষা চলছে", detail ?: "", Color.rgb(90, 40, 110))
        }
        big.text = bigText
        small.text = smallText
        root.setBackgroundColor(color)
        val active = s == MoynaState.LISTENING || s == MoynaState.THINKING || s == MoynaState.SPEAKING || s == MoynaState.ERROR
        stop.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
        // Keep the screen on only while something is happening; otherwise let it sleep.
        // (Also while asleep, so she can always see why Moyna is not answering.)
        if (active || s == MoynaState.TEST || s == MoynaState.SLEEPING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
