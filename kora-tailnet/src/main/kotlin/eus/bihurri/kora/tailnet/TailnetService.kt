package eus.bihurri.kora.tailnet

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps the process alive while the tailnet listener is on. It owns no listening state:
 * that lives in [TailnetController], so the status is the same whether or not the service is up. Its jobs are to
 * show the notification (the status text, refreshed when it changes, and a "Stop" action), to stop itself when the
 * session ends (switch turned off, auto-off, failure), and to report a failure to become a foreground service.
 *
 * Type `specialUse` (Android 14+), declared in the library manifest with the subtype property; see the module README.
 * Not exported; started through `KoraTailnet.start()` and its own notification action.
 */
internal class TailnetService : Service() {
    private var jobs = emptyList<Job>()

    /** The session this instance serves; [NO_SESSION] when it was started for none. Only touched on the main thread. */
    private var session = NO_SESSION

    /** The id of the notification this instance made its foreground notification. */
    @Volatile
    private var foregroundId = TailnetNotification.DEFAULT_NOTIFICATION_ID

    /**
     * Guards the notification against being posted after the instance let go of it: a status update that races the end
     * of the session would otherwise re-post it as a plain ongoing notification that nothing removes. Held while
     * posting and while releasing.
     */
    private val notificationLock = Any()
    private var released = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = KoraTailnet.current()
        if (intent?.action == ACTION_STOP) {
            runtime?.stop()
            release(startId)
            return START_NOT_STICKY
        }
        // Must happen promptly after startForegroundService, whatever else is wrong.
        val controller = runtime?.controller
        val epoch = controller?.epoch ?: NO_SESSION
        synchronized(notificationLock) { released = false }
        try {
            promote(runtime)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not become a foreground service", e)
            controller?.failSession(epoch, "Could not run in the foreground: ${e.message ?: e.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        if (runtime == null || !runtime.controller.running.value) {
            session = NO_SESSION
            release(startId)
            return START_NOT_STICKY
        }
        session = epoch
        jobs = listOf(
            runtime.scope.launch {
                // Ends the service when this session ends. If another session has begun by then, this instance
                // belongs to it now (its own start command re-arms the job), and a stale stop must not hit it; and
                // stopSelf(startId) is a no-op when a newer start command has arrived.
                runtime.controller.running.first { !it }
                if (runtime.controller.epoch == epoch) release(startId)
            },
            runtime.scope.launch {
                var shown = runtime.controller.status.value
                runtime.controller.status.collect { status ->
                    if (status != shown) {
                        shown = status
                        notify(runtime, epoch, status)
                    }
                }
            },
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        val controller = KoraTailnet.current()?.controller
        // A session newer than the one this instance served is running: its notification is not ours to remove.
        val newerSession = controller != null && controller.running.value && controller.epoch != session
        // Destroyed while its session is still on (the system, or anything else, ended the service): the listener
        // must not go on without the foreground notification. Only the session this instance served: after a quick
        // stop and start the destroy of the old instance must not end the new session.
        if (session != NO_SESSION) controller?.stopSession(session)
        session = NO_SESSION
        // Safety net: whatever happened, no notification of ours outlives the service (a re-posted one is no longer
        // tied to it, so the system would not remove it).
        synchronized(notificationLock) { released = true }
        if (!newerSession) removeNotification()
        super.onDestroy()
    }

    /** Lets go of the foreground state and the notification, then stops this start request. */
    private fun release(startId: Int) {
        synchronized(notificationLock) { released = true }
        removeNotification()
        stopSelf(startId)
    }

    private fun removeNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(foregroundId)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove the notification", e)
        }
    }

    private fun promote(runtime: TailnetRuntime?) {
        val notification = if (runtime == null) {
            TailnetNotifications.placeholder(this)
        } else {
            TailnetNotifications.ensureChannel(this, runtime.config.notification)
            TailnetNotifications.build(this, runtime.config.notification, runtime.controller.status.value)
        }
        val id = runtime?.config?.notification?.notificationId ?: TailnetNotification.DEFAULT_NOTIFICATION_ID
        foregroundId = id
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    /** Updates the notification, unless this instance already let go of it or its session is no longer the running one. */
    private fun notify(runtime: TailnetRuntime, epoch: Long, status: TailnetStatus) {
        synchronized(notificationLock) {
            if (released || !runtime.controller.running.value || runtime.controller.epoch != epoch) return
            try {
                val notification = TailnetNotifications.build(this, runtime.config.notification, status)
                getSystemService(NotificationManager::class.java).notify(runtime.config.notification.notificationId, notification)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not update the notification", e)
            }
        }
    }

    internal companion object {
        const val ACTION_STOP = "eus.bihurri.kora.tailnet.action.STOP"
        private const val TAG = "KoraTailnet"
        private const val NO_SESSION = -1L
    }
}
