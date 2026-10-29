package eus.bihurri.kora.android

import eus.bihurri.kora.android.ipc.IKoraNode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** What a [BinderConnector] reports about one binding. Callbacks may arrive on any thread. */
internal interface BindingEvents {
    /** The peer's node is ready to use. */
    fun onConnected(node: IKoraNode)

    /** The binding could not be established or was lost (peer died, was removed or refused to bind). */
    fun onLost(reason: String)
}

/** A started binding; [unbind] releases it. */
internal fun interface Binding {
    fun unbind()
}

/** Port: starts binding to the node served by a peer package. */
internal fun interface BinderConnector {
    /** Starts binding and reports through [events]. Returns null when the system refuses to bind at all. */
    fun bind(packageName: String, events: BindingEvents): Binding?
}

/**
 * One lazily created connection per peer package, shared by every caller.
 *
 * - The first [acquire] starts the binding; concurrent callers wait for the same pending connection.
 * - A connection that does not come up within [connectTimeout] fails as `provider_unavailable`.
 * - When the binding is lost, every [Lease.onLost] listener runs, the connection is dropped and the next
 *   [acquire] reconnects.
 * - A connection nobody holds (no in-flight call, no open subscription) is unbound after [idleTimeout].
 *
 * The state machine has no Android types: the system side is behind [BinderConnector], and timers run on
 * [scope] with `delay`, so tests drive them with virtual time.
 */
internal class ConnectionPool(
    private val connector: BinderConnector,
    private val scope: CoroutineScope,
    private val connectTimeout: Duration,
    private val idleTimeout: Duration,
) {
    private val lock = Any()
    private val connections = HashMap<String, Connection>()
    private var closed = false

    /** Number of live (pending or connected) connections. */
    val size: Int get() = synchronized(lock) { connections.size }

    /**
     * Returns a [Lease] on the node of [packageName], binding first if needed. The lease keeps the connection
     * open until [Lease.release]. Throws `KoraException(provider_unavailable)` when the peer cannot be reached.
     */
    suspend fun acquire(packageName: String): Lease {
        val (connection, created) = hold(packageName)
        if (created) connection.start()
        try {
            return Lease(connection.connected.await(), connection)
        } catch (e: Throwable) {
            connection.unhold()
            throw e
        }
    }

    /** Drops every connection: in-flight calls fail as unavailable and the pool refuses new ones. */
    fun close() {
        val open = synchronized(lock) {
            closed = true
            connections.values.toList()
        }
        open.forEach { it.fail(unavailable("Transport is closed")) }
    }

    private fun hold(packageName: String): Pair<Connection, Boolean> = synchronized(lock) {
        if (closed) throw unavailable("Transport is closed")
        val existing = connections[packageName]
        val connection = existing ?: Connection(packageName).also { connections[packageName] = it }
        connection.holders++
        connection.cancelIdleTimer()
        connection to (existing == null)
    }

    private fun unavailable(message: String) = KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, message))

    /** A holder of a connected node; releasing it lets the connection go idle. */
    inner class Lease internal constructor(val node: IKoraNode, private val connection: Connection) {
        private var released = false

        /**
         * Runs [listener] once if the connection is lost while it is held (immediately if it already was).
         * Dispose the result when the work it protects is done.
         */
        fun onLost(listener: () -> Unit): AutoCloseable = connection.addLostListener(listener)

        fun release() {
            synchronized(this) {
                if (released) return
                released = true
            }
            connection.unhold()
        }
    }

    internal inner class Connection(private val packageName: String) : BindingEvents {
        val connected = CompletableDeferred<IKoraNode>()

        // All fields below are guarded by [lock].
        var holders = 0
        private var idleTimer: Job? = null
        private var idleGeneration = 0
        private var connectTimer: Job? = null
        private var binding: Binding? = null
        private var ready = false
        private var finished = false
        private var lost = false
        private val lostListeners = LinkedHashSet<() -> Unit>()

        fun start() {
            val started = try {
                connector.bind(packageName, this)
            } catch (e: RuntimeException) { // e.g. SecurityException from bindService
                fail(unavailable("Cannot bind to '$packageName': ${e.javaClass.simpleName}"))
                return
            }
            if (started == null) {
                fail(unavailable("The system refused to bind to '$packageName'"))
                return
            }
            val alreadyFinished = synchronized(lock) {
                binding = started
                finished
            }
            if (alreadyFinished) {
                started.unbind()
                return
            }
            val timer = scope.launch {
                delay(connectTimeout)
                fail(unavailable("'$packageName' did not connect within $connectTimeout"), onlyIfPending = true)
            }
            val stale = synchronized(lock) {
                if (ready || finished) true else { connectTimer = timer; false }
            }
            if (stale) timer.cancel()
        }

        override fun onConnected(node: IKoraNode) {
            val accepted = synchronized(lock) {
                if (finished) return@synchronized false
                ready = true
                connectTimer?.cancel()
                true
            }
            if (!accepted) return
            connected.complete(node)
            scheduleIdleIfUnused()
        }

        override fun onLost(reason: String) = fail(unavailable("$reason ('$packageName')"))

        fun addLostListener(listener: () -> Unit): AutoCloseable {
            val runNow = synchronized(lock) {
                if (lost) true else { lostListeners += listener; false }
            }
            if (runNow) listener()
            return AutoCloseable { synchronized(lock) { lostListeners -= listener } }
        }

        fun unhold() {
            val idle = synchronized(lock) {
                holders--
                holders == 0 && ready && !finished
            }
            if (idle) scheduleIdleIfUnused()
        }

        /** Ends the connection because it was lost or never came up: notifies waiters and listeners. */
        fun fail(error: KoraException, onlyIfPending: Boolean = false) {
            val toNotify = synchronized(lock) {
                if (finished || (onlyIfPending && ready)) return
                finished = true
                lost = true
                if (connections[packageName] === this) connections.remove(packageName)
                connectTimer?.cancel()
                cancelIdleTimerLocked()
                lostListeners.toList().also { lostListeners.clear() }
            }
            connected.completeExceptionally(error)
            binding?.unbind()
            toNotify.forEach { it() }
        }

        fun cancelIdleTimer() = synchronized(lock) { cancelIdleTimerLocked() }

        private fun cancelIdleTimerLocked() {
            idleGeneration++
            idleTimer?.cancel()
            idleTimer = null
        }

        private fun scheduleIdleIfUnused() {
            synchronized(lock) {
                if (finished || holders > 0 || idleTimer != null) return
                val generation = ++idleGeneration
                idleTimer = scope.launch {
                    delay(idleTimeout)
                    unbindIfIdle(generation)
                }
            }
        }

        private fun unbindIfIdle(generation: Int) {
            val toUnbind = synchronized(lock) {
                if (finished || holders > 0 || generation != idleGeneration) return
                finished = true
                if (connections[packageName] === this) connections.remove(packageName)
                idleTimer = null
                binding
            }
            toUnbind?.unbind()
        }
    }
}
