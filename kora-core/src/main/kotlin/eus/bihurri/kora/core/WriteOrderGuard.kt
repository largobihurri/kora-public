package eus.bihurri.kora.core

import eus.bihurri.kora.contract.media.WriteOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Last-write-wins for providers whose writes carry an optional [WriteOrder], such as `media.volume`'s `set_volume`.
 *
 * Kora dispatches the calls of one caller in parallel (Binder and the wear relay), so a burst of writes can arrive out
 * of order. The guard implements the rule of the contract, per caller key [K] (normally the [Caller] of
 * [CallContext.caller]):
 * - no `order`: always admitted;
 * - a new writer replaces the caller's current one, which is retired, and its first write is admitted;
 * - the current writer is admitted only when its sequence is greater than the last admitted one;
 * - a retired writer is refused, so a delayed write of a previous session never overrides the new session.
 *
 * ```kotlin
 * private val volumeOrder = WriteOrderGuard<Caller>()
 *
 * on(MediaVolume.SetVolume) { request ->
 *     volumeOrder.applyInOrder(caller, request.order, apply = { player.setVolume(request) }, current = { player.volume() })
 * }
 * ```
 *
 * [admit] only decides; a provider that applies the write outside the guard can still interleave two admitted writes.
 * [applyInOrder] decides and applies under one lock, which is what most providers want.
 *
 * Thread-safe. Memory is bounded: the guard remembers at most [maxCallers] callers, forgetting the least recently seen
 * one beyond that, and the last [RETIRED_WRITERS] retired writers per caller. A forgotten caller starts over, so its
 * next ordered write is admitted as a first write; with the default capacity that needs [maxCallers] other callers
 * writing in between, far more than the handful of apps on one device.
 */
public class WriteOrderGuard<K : Any>(public val maxCallers: Int = DEFAULT_MAX_CALLERS) {
    init {
        require(maxCallers > 0) { "maxCallers must be positive: $maxCallers" }
    }

    private val lock = Any()
    private val applying = Mutex()

    /** The current writer of a caller, its last admitted order, and the writers it replaced (oldest first). */
    private class Writers(var current: WriteOrder) {
        val retired = ArrayDeque<String>()
    }

    /** Writers per caller, in access order so the eldest entry is the least recently seen caller. */
    private val callers = object : LinkedHashMap<K, Writers>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Writers>): Boolean = size > maxCallers
    }

    /**
     * Whether the write of [caller] ordered by [order] is to be applied. An admitted ordered write becomes the
     * caller's latest (retiring the previous writer when the writer changes); a refused one changes nothing.
     */
    public fun admit(caller: K, order: WriteOrder?): Boolean {
        if (order == null) return true
        synchronized(lock) {
            val writers = callers[caller] ?: return true.also { callers[caller] = Writers(order) }
            val current = writers.current
            return when {
                order.writer == current.writer -> (order.sequence > current.sequence).also { if (it) writers.current = order }
                order.writer in writers.retired -> false
                else -> {
                    writers.retired.addLast(current.writer)
                    if (writers.retired.size > RETIRED_WRITERS) writers.retired.removeFirst()
                    writers.current = order
                    true
                }
            }
        }
    }

    /**
     * Applies the write when [admit] admits it and returns [apply]'s result; otherwise returns [current], the state
     * unchanged. Decisions and applications run one at a time, so the applied state always follows the admitted order.
     * An admitted write counts as the caller's latest even when [apply] throws.
     */
    public suspend fun <T> applyInOrder(
        caller: K,
        order: WriteOrder?,
        apply: suspend () -> T,
        current: suspend () -> T,
    ): T = applying.withLock { if (admit(caller, order)) apply() else current() }

    public companion object {
        /** Default number of callers remembered. */
        public const val DEFAULT_MAX_CALLERS: Int = 64

        /** Retired writers remembered per caller; an older one is forgotten and would be treated as new again. */
        public const val RETIRED_WRITERS: Int = 8
    }
}
