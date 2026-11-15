package app.moyna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/** Shares the running engine and its latest state between the service and the screen. */
object MoynaHub {
    @Volatile var engine: MoynaEngine? = null
    @Volatile var listener: ((MoynaState, String?) -> Unit)? = null
    @Volatile var state: MoynaState = MoynaState.IDLE
    @Volatile var detail: String? = null

    fun publish(s: MoynaState, d: String?) {
        state = s; detail = d
        listener?.invoke(s, d)
    }

    // Talk-time bar: share of today's DAILY_TALK_MINUTES used (can go above 1), and whether
    // Google refused a request for quota reasons recently.
    @Volatile var usage = 0f
    @Volatile var usageAlert = false
    @Volatile var usageListener: ((Float, Boolean) -> Unit)? = null

    fun publishUsage(u: Float, alert: Boolean) {
        usage = u; usageAlert = alert
        usageListener?.invoke(u, alert)
    }
}

/**
 * Keeps Moyna running all the time as a "foreground service" (with a small notification),
 * so Android doesn't stop it and lets it use the microphone while the screen is off.
 */
class MoynaService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        // Keep the processor and Wi-Fi awake. The phone is always plugged in, so this costs nothing.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moyna:listening").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "moyna:wifi").apply { acquire() }

        val engine = MoynaEngine(applicationContext, MoynaHub::publish) { showScreen() }
        MoynaHub.engine = engine
        engine.start()
        Log.i("Moyna", "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called every time the screen asks us to run. Calling startForeground again while the
        // screen is visible is what gives the service permission to use the mic in the background.
        goForeground()
        MoynaHub.engine?.start()
        if (intent == null) {
            // Android restarted us after a crash: bring the screen up so the mic works again.
            Log.w("Moyna", "service restarted by Android")
            showScreen()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w("Moyna", "service stopping")
        MoynaHub.engine?.shutdown()
        MoynaHub.engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ময়না", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ময়না চালু আছে")
            .setContentText("\"ময়না\" বলে ডাকুন")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /** Turn the screen on and show Moyna (allowed because of the "display over other apps" setting). */
    private fun showScreen() {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
            Log.w("Moyna", "could not show screen: ${e.message}")
        }
    }

    companion object {
        private const val CHANNEL = "moyna"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MoynaService::class.java).putExtra("from", "screen"))
        }
    }
}
