package eus.bihurri.kora.tailnet

import eus.bihurri.kora.mcp.http.DeniedAttempt
import eus.bihurri.kora.mcp.http.McpHttpListener
import eus.bihurri.kora.mcp.http.McpHttpServer
import eus.bihurri.kora.mcp.http.Pairing
import eus.bihurri.kora.mcp.http.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * A wake-up at a point in time that fires even while the device sleeps (on Android an `AlarmManager` alarm). The idle
 * deadline is handed to it as a backstop: coroutine delays do not advance in deep sleep, an alarm does.
 */
internal interface DeadlineAlarm {
    /** Calls [TailnetController.checkIdle] at or after [elapsedRealtimeNanos] on the controller's clock. */
    fun scheduleAt(elapsedRealtimeNanos: Long)

    fun cancel()
}

/**
 * The listener's life: follows the Tailscale address, keeps an [McpHttpServer] bound to exactly that address, and
 * switches everything off after [idleTimeout] without an authenticated request. No Android types, so it is tested on
 * the JVM with a fake address source, a test clock and (for real sockets) loopback.
 *
 * ## States
 * Off, then [start]: [TailnetStatus.WaitingForTailnet] until the source reports an address, then
 * [TailnetStatus.Listening] (or [TailnetStatus.Error] when the bind fails, e.g. the port is taken). When the address
 * changes the server is stopped and bound again on the new one; when it disappears the status goes back to waiting.
 * [stop] and the idle timeout end it in [TailnetStatus.Off].
 *
 * ## Sessions
 * Each [start] opens a session with its own [epoch]. Everything that can outlive a session (an address callback of a
 * stopped source, a reconcile that was waiting for [lock], an idle job, a service instance) carries the epoch it
 * belongs to and is dropped when it no longer matches, so a quick stop and start cannot bind an old address or end the
 * new session.
 *
 * ## Idle
 * The idle period is measured with [clock], which must keep counting while the device sleeps (on Android
 * `SystemClock.elapsedRealtimeNanos`). Three things enforce it: a coroutine that waits while the device is awake, an
 * [alarm] that wakes the device at the deadline (best effort, see the Android implementation), and a check on every
 * event the controller sees: a request is refused when the session already expired (the pairing lookup returns
 * nothing) and the session is ended instead of served, and an address change or a refused request ends it too.
 *
 * ## Threads
 * The address source calls back from any thread and only updates a conflated flow; reconciling (stop, bind) runs on
 * [scope] in order, so a burst of changes ends with the latest address. Server hooks never take [lock]: a request
 * thread must not wait for a stop that is waiting for that request.
 *
 * @param clock monotonic time in nanoseconds that includes deep sleep; the idle timer is measured with it.
 * @param alarm wakes the device at the idle deadline; [DeadlineAlarm] has no-op implementations for tests.
 * @param pairings where tokens are looked up; the server gets it wrapped so that an expired session finds nobody.
 * @param serverFactory builds the server, handing it the wrapped pairings and the listener that feeds the idle timer
 *   and the denied flow.
 */
internal class TailnetController(
    private val addresses: TailnetAddressSource,
    private val port: Int,
    private val path: String,
    private val idleTimeout: Duration,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val alarm: DeadlineAlarm,
    pairings: PairingStore,
    private val log: (String) -> Unit = {},
    serverFactory: (PairingStore, McpHttpListener) -> McpHttpServer,
) {
    private val statusFlow = MutableStateFlow<TailnetStatus>(TailnetStatus.Off)
    private val runningFlow = MutableStateFlow(false)
    private val deniedFlow = MutableSharedFlow<DeniedAttempt>(extraBufferCapacity = DENIED_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The current status. */
    val status: StateFlow<TailnetStatus> = statusFlow.asStateFlow()

    /** Whether a session is on: true from [start] until [stop], an auto-off or a fatal failure. */
    val running: StateFlow<Boolean> = runningFlow.asStateFlow()

    /** Refused requests (the oldest are dropped when nobody collects). */
    val denied: SharedFlow<DeniedAttempt> = deniedFlow.asSharedFlow()

    private val lock = Any()
    private val lastActivity = AtomicLong()

    @Volatile
    private var activeFlag = false

    @Volatile
    private var sessionEpoch = 0L
    private var watcher: Job? = null
    private var idleWatch: Job? = null
    private var bound: InetAddress? = null

    /** Test seam: runs at the start of a reconcile, before it waits for [lock]. */
    @Volatile
    internal var beforeReconcile: (() -> Unit)? = null

    /** The number of the current (or last) session; grows by one at every [start] that opens a session. */
    val epoch: Long get() = sessionEpoch

    private val server = serverFactory(
        object : PairingStore {
            override fun find(tokenHash: ByteArray): Pairing? {
                if (expired()) {
                    endIdleAsync()
                    return null
                }
                return pairings.find(tokenHash)
            }
        },
        object : McpHttpListener {
            override fun onRequest(pairing: Pairing) {
                if (expired()) endIdleAsync() else lastActivity.set(clock())
            }

            override fun onDenied(attempt: DeniedAttempt) {
                if (expired()) endIdleAsync()
                deniedFlow.tryEmit(attempt)
            }
        },
    )

    /** Starts a session. Does nothing when one is on. */
    fun start() {
        synchronized(lock) {
            if (activeFlag) return
            activeFlag = true
            val session = ++sessionEpoch
            val flow = MutableStateFlow<InetAddress?>(null)
            runningFlow.value = true
            statusFlow.value = TailnetStatus.WaitingForTailnet
            lastActivity.set(clock())
            alarm.scheduleAt(lastActivity.get() + idleTimeout.inWholeNanoseconds)
            idleWatch = scope.launch { watchIdle(session) }
            try {
                // A callback of this session writes to this session's flow only; a stopped source cannot reach the next.
                addresses.start { if (sessionEpoch == session) flow.value = it }
            } catch (e: Exception) {
                endSession(TailnetStatus.Error("Could not watch the network: ${e.message ?: e.javaClass.simpleName}"))
                return
            }
            watcher = scope.launch { flow.collect { reconcile(session, it) } }
        }
    }

    /** Ends the session. Does nothing when none is on. */
    fun stop() {
        synchronized(lock) {
            if (activeFlag) endSession(TailnetStatus.Off)
        }
    }

    /** Ends the session only if it is still [session]; for a service instance that belongs to that session. */
    fun stopSession(session: Long) {
        synchronized(lock) {
            if (activeFlag && sessionEpoch == session) endSession(TailnetStatus.Off)
        }
    }

    /** Ends the session because something outside the controller failed, leaving [reason] as the status. */
    fun fail(reason: String) {
        synchronized(lock) {
            if (activeFlag) endSession(TailnetStatus.Error(reason))
        }
    }

    /** Like [fail] but only when the session is still [session]. */
    fun failSession(session: Long, reason: String) {
        synchronized(lock) {
            if (activeFlag && sessionEpoch == session) endSession(TailnetStatus.Error(reason))
        }
    }

    /** Reports that a session could not even start (nothing was started), leaving [reason] as the status. */
    fun reject(reason: String) {
        synchronized(lock) {
            if (!activeFlag) statusFlow.value = TailnetStatus.Error(reason)
        }
    }

    /**
     * Ends the session if it has been idle for [idleTimeout], otherwise re-arms the alarm for the new deadline. What
     * the alarm calls when it fires.
     */
    fun checkIdle() {
        synchronized(lock) {
            if (!activeFlag) return
            if (expired()) {
                log("No authenticated request for $idleTimeout; switching off")
                endSession(TailnetStatus.Off)
            } else {
                alarm.scheduleAt(lastActivity.get() + idleTimeout.inWholeNanoseconds)
            }
        }
    }

    /** Forgets the MCP sessions of a revoked pairing. */
    fun evictSession(pairingId: String) = server.evictSession(pairingId)

    // Called with [lock] held.
    private fun endSession(final: TailnetStatus) {
        activeFlag = false
        watcher?.cancel()
        idleWatch?.cancel()
        watcher = null
        idleWatch = null
        alarm.cancel()
        try {
            addresses.stop()
        } catch (_: Exception) {
            // Nothing more to release.
        }
        stopServer()
        statusFlow.value = final
        runningFlow.value = false
    }

    private fun stopServer() {
        if (server.isRunning) server.stop()
        bound = null
    }

    /** Whether the active session has outlived its idle period; false when none is on. */
    private fun expired(): Boolean =
        activeFlag && lastActivity.get() + idleTimeout.inWholeNanoseconds - clock() <= 0

    /**
     * Ends an expired session from a thread that must not do it itself (a request thread: stopping the server joins
     * the worker threads, itself included).
     */
    private fun endIdleAsync() {
        scope.launch { checkIdle() }
    }

    /** Brings the server in line with [target], the latest address [session] reported (null when there is none). */
    private fun reconcile(session: Long, target: InetAddress?) {
        beforeReconcile?.invoke()
        synchronized(lock) {
            // An old session's work, or a waiter that outlived its session, must not touch the current one.
            if (!activeFlag || sessionEpoch != session) return
            if (expired()) {
                checkIdle()
                return
            }
            try {
                when {
                    target == null -> {
                        stopServer()
                        statusFlow.value = TailnetStatus.WaitingForTailnet
                    }
                    server.isRunning && bound == target -> Unit
                    else -> {
                        stopServer()
                        val listening = server.start(target, port)
                        bound = target
                        statusFlow.value = TailnetStatus.Listening(target.textualAddress(), listening, tailnetUrl(target, listening, path))
                        log("Listening on ${target.textualAddress()}:$listening")
                    }
                }
            } catch (e: Exception) {
                stopServer()
                val why = e.message ?: e.javaClass.simpleName
                log("Cannot listen on ${target?.textualAddress()}:$port: $why")
                statusFlow.value = TailnetStatus.Error("Cannot listen on ${target?.textualAddress()}:$port: $why")
            }
        }
    }

    /** Ends [session] once idle while the device is awake; the alarm covers the time it sleeps. */
    private suspend fun watchIdle(session: Long) {
        while (true) {
            val remaining = lastActivity.get() + idleTimeout.inWholeNanoseconds - clock()
            if (remaining <= 0) {
                synchronized(lock) {
                    if (!activeFlag || sessionEpoch != session) return
                    if (expired()) {
                        checkIdle()
                        return
                    }
                }
                continue
            }
            // Round up: waking a hair early would only loop once more, but never wake late.
            delay((remaining + NANOS_PER_MS - 1) / NANOS_PER_MS)
        }
    }

    private companion object {
        const val DENIED_BUFFER = 16
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** The address without a zone suffix (`%tun0`), which is not valid in a URL. */
internal fun InetAddress.textualAddress(): String = hostAddress.orEmpty().substringBefore('%')

/** What an agent is configured with: IPv6 addresses are bracketed. */
internal fun tailnetUrl(address: InetAddress, port: Int, path: String): String {
    val host = if (address is java.net.Inet6Address) "[${address.textualAddress()}]" else address.textualAddress()
    return "http://$host:$port$path"
}
