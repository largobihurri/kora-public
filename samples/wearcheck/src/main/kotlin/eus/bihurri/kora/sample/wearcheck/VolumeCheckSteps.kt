package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.contract.media.SetVolumeRequest
import eus.bihurri.kora.contract.media.VolumeState
import eus.bihurri.kora.contract.media.WriteOrder
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The volume steps towards [peerId], reported through [step] (the runner's `KoraWearCheck step=...` line):
 * `volume_cold` right after the refresh, then `volume_latency`, `volume_burst` and `volume_event` ([measure]).
 *
 * Every write carries a [WriteOrder] of one writer session: a random UUID per run and a sequence that increases with
 * every write, as the Kardio crown would send them.
 */
internal class VolumeCheckSteps(
    private val node: KoraNode,
    private val peerId: String,
    private val step: suspend (name: String, block: suspend () -> Pair<Boolean, String>) -> Unit,
) {
    private val writer = UUID.randomUUID().toString()
    private var sequence = 0L
    private val peer = Target.Peer(peerId)

    /**
     * Waits with [KoraNode.awaitProvider] targeted at the peer (this app also provides `media.volume` itself, so an
     * untargeted wait would answer at once from the local provider), then makes the first `get_volume` on the peer.
     * `peerWaitMs` is the cold wait a consumer such as Kardio's watch spends in the same call.
     */
    suspend fun cold() = step("volume_cold") {
        val started = TimeSource.Monotonic.markNow()
        val peerOffers = node.awaitProvider(MediaVolume, COLD_WAIT, peer)
        val peerWaitMs = started.elapsedNow().inWholeMilliseconds
        val getStarted = TimeSource.Monotonic.markNow()
        val first = attempt { get() }
        val getMs = getStarted.elapsedNow().inWholeMilliseconds
        val error = first.exceptionOrNull()?.let(::describe)
        VolumeChecks.coldPassed(peerOffers, error) to "peer=$peerId peerOffers=$peerOffers peerWaitMs=$peerWaitMs " +
            "firstGet=${error ?: "ok"} getMs=$getMs" +
            (first.getOrNull()?.let { " state=${it.level}/${it.maxLevel}" } ?: "")
    }

    /** `volume_latency`, `volume_burst` and `volume_event`, in that order. */
    suspend fun measure() {
        latency()
        burst()
        event()
    }

    private suspend fun latency() = step("volume_latency") {
        val levels = VolumeChecks.latencyLevels(VolumeChecks.LATENCY_WRITES, get().maxLevel)
        val samples = levels.map { level ->
            val started = TimeSource.Monotonic.markNow()
            set(level, nextOrder())
            started.elapsedNow().inWholeMilliseconds
        }
        val final = get()
        val latency = VolumeChecks.latency(samples)
        VolumeChecks.lastWriteWins(final, levels.last()) to "peer=$peerId writes=${samples.size} $latency " +
            "final=${final.level} expected=${levels.last()} writer=${writer.take(WRITER_PREFIX)}"
    }

    /** Sends every write at once, sequences assigned in order; the one with the greatest sequence must win. */
    private suspend fun burst() = step("volume_burst") {
        val levels = VolumeChecks.burstLevels(VolumeChecks.BURST_WRITES, get().maxLevel)
        val orders = levels.map { nextOrder() }
        val started = TimeSource.Monotonic.markNow()
        val answers = coroutineScope {
            levels.zip(orders).map { (level, order) -> async { set(level, order) } }.awaitAll()
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        val final = get()
        val expected = levels.last()
        VolumeChecks.lastWriteWins(final, expected) to "peer=$peerId writes=${answers.size} elapsedMs=$elapsedMs " +
            "final=${final.level} expected=$expected answeredExpected=${answers.count { it.level == expected }}"
    }

    /** Subscribes, waits for the current state, sets a different level and times its `volume_changed`. */
    private suspend fun event() = step("volume_event") {
        coroutineScope {
            val events = Channel<VolumeState>(Channel.UNLIMITED)
            val collector = launch { node.events(MediaVolume.VolumeChanged, peer).collect { events.send(it) } }
            try {
                val initial = withTimeoutOrNull(EVENT_WAIT) { events.receive() }
                if (initial == null) {
                    false to "peer=$peerId no initial state within $EVENT_WAIT"
                } else {
                    val target = VolumeChecks.eventTarget(initial)
                    val started = TimeSource.Monotonic.markNow()
                    set(target, nextOrder())
                    val setMs = started.elapsedNow().inWholeMilliseconds
                    val seen = withTimeoutOrNull(EVENT_WAIT) {
                        var state = events.receive()
                        while (state.level != target) state = events.receive()
                        state
                    }
                    val eventMs = started.elapsedNow().inWholeMilliseconds
                    (seen != null) to "peer=$peerId from=${initial.level} to=$target setMs=$setMs " +
                        (if (seen != null) "eventMs=$eventMs" else "no event within $EVENT_WAIT")
                }
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    private suspend fun get(): VolumeState = node.call(MediaVolume.GetVolume, NoInput, peer)

    private suspend fun set(level: Int, order: WriteOrder): VolumeState =
        node.call(MediaVolume.SetVolume, SetVolumeRequest(level = level, order = order), peer)

    private fun nextOrder(): WriteOrder = WriteOrder(writer, ++sequence)

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun describe(e: Throwable): String =
        (e as? KoraException)?.let { "${it.error.code.wireName}: ${it.error.message}" } ?: "${e.javaClass.simpleName}: ${e.message}"

    companion object {
        /** Step names, in order, for the runner to skip when there is no wear peer. */
        val STEPS: List<String> = listOf("volume_cold", "volume_latency", "volume_burst", "volume_event")

        private val COLD_WAIT = 20.seconds
        private val EVENT_WAIT = 10.seconds
        private const val WRITER_PREFIX = 8
    }
}
