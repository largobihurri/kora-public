package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.KoraException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What happens on a [FramedLink], in the order it happened. */
public sealed interface LinkEvent {
    /** The counterpart sent [body]. */
    public class Received(public val body: FrameBody) : LinkEvent

    /** The counterpart became unreachable: in-flight work toward it can no longer finish. */
    public data object Lost : LinkEvent

    /** The counterpart is reachable again after a [Lost]; it may have restarted, so ask it again what you need. */
    public data object Reconnected : LinkEvent

    /** The counterpart app restarted (its [Frame.epoch] changed): whatever it held for us, such as subscriptions, is gone. */
    public data object CounterpartRestarted : LinkEvent
}

/**
 * A [WireLink] speaking [Frame]s: encodes what is sent, decodes and validates what arrives, and turns the link's
 * reachability and the counterpart's [Frame.epoch] into [LinkEvent]s. Directory, transport and server of one link
 * share a single instance, so every frame is decoded once.
 *
 * Collectors of [events] must be registered **before** [start]: frames arriving without a collector are dropped.
 * [WearRelay] takes care of the order.
 *
 * @param epoch identifies this process to the counterpart; random unless a test fixes it.
 * @param onDropped told why an incoming frame was ignored (malformed, unsupported version); for logging only.
 */
public class FramedLink(
    private val link: WireLink,
    private val scope: CoroutineScope,
    public val epoch: Long = Random.nextLong(),
    private val onDropped: (String) -> Unit = {},
) {
    private val shared = MutableSharedFlow<LinkEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
    private var peerEpoch: Long? = null
    private var started = false

    /** Every [LinkEvent], to every collector. */
    public val events: SharedFlow<LinkEvent> = shared

    /** Encodes and sends [body]. Throws [KoraException]: `payload_too_large` or `provider_unavailable`. */
    public suspend fun send(body: FrameBody) {
        val bytes = FrameCodec.encode(epoch, body)
        try {
            link.send(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KoraException) {
            throw e
        } catch (e: Exception) {
            throw unavailable("The counterpart is not reachable", e)
        }
    }

    /** Starts reading the link. Idempotent. */
    @Synchronized
    public fun start() {
        if (started) return
        started = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.incoming.collect { bytes ->
                when (val reading = FrameCodec.read(bytes)) {
                    is FrameReading.Malformed -> onDropped(reading.reason)
                    is FrameReading.UnsupportedVersion -> onDropped("unsupported protocol version ${reading.version}")
                    is FrameReading.Valid -> {
                        val frame = reading.frame
                        val known = peerEpoch
                        peerEpoch = frame.epoch
                        if (known != null && known != frame.epoch) shared.emit(LinkEvent.CounterpartRestarted)
                        shared.emit(LinkEvent.Received(frame.body))
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var lost = false
            var previous = link.reachable.value
            link.reachable.collect { now ->
                if (previous && !now) {
                    lost = true
                    shared.emit(LinkEvent.Lost)
                } else if (!previous && now && lost) {
                    lost = false
                    shared.emit(LinkEvent.Reconnected)
                }
                previous = now
            }
        }
    }

    private companion object {
        const val BUFFER = 256
    }
}
