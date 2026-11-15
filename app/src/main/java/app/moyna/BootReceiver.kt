package app.moyna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts Moyna after the phone turns on (or after Moyna is updated).
 * It opens the Moyna screen, and the screen starts the background service. Starting from the
 * screen (not straight from here) is what lets Android allow the microphone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Moyna", "boot/update: ${intent.action}")
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Moyna", "could not open screen at boot, starting service directly: ${e.message}")
            MoynaService.start(context)
        }
    }
}
