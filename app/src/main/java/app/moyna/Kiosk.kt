package app.moyna

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/** Needed so Android can make Moyna the "device owner" (set once from the PC, see README). */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Kiosk mode: Moyna is the home screen and the only app, so she can't be left by accident.
 * Only works after the PC command  adb shell dpm set-device-owner app.moyna/.AdminReceiver
 * Without it, everything here quietly does nothing and Moyna is a normal app.
 */
object Kiosk {
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun prefs(c: Context) = c.getSharedPreferences("moyna", Context.MODE_PRIVATE)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)
    /** Kiosk was switched off from the admin screen (stays off until switched on again). */
    fun isOff(c: Context) = prefs(c).getBoolean("kiosk_off", false)
    fun isLocked(a: Activity) =
        a.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** Call whenever the screen comes to the front. */
    fun enter(a: Activity) {
        if (!isOwner(a) || isOff(a)) return
        try {
            val d = dpm(a); val me = admin(a)
            d.setLockTaskPackages(me, arrayOf(a.packageName))
            d.setLockTaskFeatures(me, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)   // no home, recents or notifications
            d.addPersistentPreferredActivity(me,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) },
                ComponentName(a, MainActivity::class.java))
            d.setKeyguardDisabled(me, true)
            if (!isLocked(a)) { a.startLockTask(); Log.i("Moyna", "kiosk: locked to Moyna") }
        } catch (e: Exception) { Log.w("Moyna", "kiosk could not start: ${e.message}") }
    }

    /** From the admin screen: let the phone be used normally again. */
    fun exit(a: Activity) {
        prefs(a).edit().putBoolean("kiosk_off", true).apply()
        try {
            if (isLocked(a)) a.stopLockTask()
            if (isOwner(a)) dpm(a).clearPackagePersistentPreferredActivities(admin(a), a.packageName)
            Log.i("Moyna", "kiosk: switched off from the admin screen")
        } catch (e: Exception) { Log.w("Moyna", "kiosk exit problem: ${e.message}") }
    }

    fun turnOn(a: Activity) { prefs(a).edit().putBoolean("kiosk_off", false).apply(); enter(a) }
}
