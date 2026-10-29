package eus.bihurri.kora.tailnet

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * The idle deadline as an alarm, so it fires while the device sleeps (coroutine delays and `System.nanoTime` stand
 * still in deep sleep).
 *
 * It uses `setAndAllowWhileIdle` with `ELAPSED_REALTIME_WAKEUP`: it wakes the device, it also fires in Doze, and it
 * needs no exact-alarm permission. The price is precision. The alarm is inexact; in Doze the system lets an app's
 * allow-while-idle alarms fire at most about once every nine minutes, so the session can outlive its deadline by that
 * much. That is the bound of the backstop; the check on every request, refused request and address event keeps a
 * session that expired from being served in the meantime. Scheduling the same alarm again replaces it.
 */
internal class AndroidDeadlineAlarm(private val context: Context) : DeadlineAlarm {
    private val manager = context.getSystemService(AlarmManager::class.java)

    override fun scheduleAt(elapsedRealtimeNanos: Long) {
        // Rounded up: an alarm a millisecond early would only find the session not yet idle and re-arm itself.
        val triggerAtMillis = (elapsedRealtimeNanos + NANOS_PER_MS - 1) / NANOS_PER_MS
        try {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, operation())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not schedule the idle alarm; the idle timer then runs while the device is awake only", e)
        }
    }

    override fun cancel() {
        try {
            manager.cancel(operation())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not cancel the idle alarm", e)
        }
    }

    private fun operation(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TailnetIdleReceiver::class.java).setAction(ACTION_IDLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal companion object {
        const val ACTION_IDLE = "eus.bihurri.kora.tailnet.action.IDLE"
        private const val TAG = "KoraTailnet"
        private const val NANOS_PER_MS = 1_000_000L
    }
}

/** Receives the idle alarm and lets the controller end the session if it really is idle (or re-arm the alarm). Not exported. */
internal class TailnetIdleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = KoraTailnet.current() ?: return
        // Stopping the server can block for a moment: not on the main thread, but keep the process awake until done.
        val pending: PendingResult? = goAsync()
        runtime.scope.launch {
            try {
                runtime.controller.checkIdle()
            } finally {
                pending?.finish()
            }
        }
    }
}
