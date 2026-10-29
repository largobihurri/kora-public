package eus.bihurri.kora.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [WireLink] over Data Layer messages.
 *
 * **Counterpart.** The one device that advertises the Kora capability and is reachable; when several do, the
 * nearby one (Bluetooth) wins, then the lowest id. It is looked up **lazily** (on the first send or the first
 * incoming message), cached, and looked up again only after the platform reports a capability change or a send
 * fails. Messages from any other device are ignored, so a second paired device cannot inject frames. The link
 * talks to one counterpart: a phone with several watches serves the nearest only.
 *
 * **Frames.** A frame is sent as one message when it fits, otherwise as several chunks ([FrameChunks]); incoming
 * chunks are reassembled in order of arrival, and incomplete frames are forgotten after a few newer ones.
 *
 * **Reachability.** [reachable] follows the lookup: `true` while a counterpart is known, `false` when a capability
 * change leaves none. Nothing polls: lookups happen on use and on the platform's capability callback.
 *
 * Delivery from the service is [deliver], which only queues; one consumer processes the queue in order.
 */
internal class MessageLink(
    private val dataLayer: DataLayer,
    private val scope: CoroutineScope,
    private val chunkPayload: Int = FrameChunks.DEFAULT_CHUNK_PAYLOAD,
    private val onProblem: (String, Throwable?) -> Unit = { _, _ -> },
) : WireLink {
    private class Message(val sourceNodeId: String, val path: String, val data: ByteArray)

    private val inbox = Channel<Message>(INBOX_CAPACITY)
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    private val assembler = FrameAssembler()
    private val lookup = Mutex()
    private val state = MutableStateFlow(false)

    @Volatile
    private var counterpart: DataNode? = null
    private var listener: AutoCloseable? = null
    private var closed = false

    override val incoming: Flow<ByteArray> get() = frames
    override val reachable: StateFlow<Boolean> get() = state

    init {
        scope.launch {
            for (message in inbox) {
                try {
                    process(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onProblem("Could not process a message", e)
                }
            }
        }
    }

    /** Called by the listener service for every message on the relay's path. Never blocks. */
    fun deliver(sourceNodeId: String, path: String, data: ByteArray) {
        if (inbox.trySend(Message(sourceNodeId, path, data)).isFailure) onProblem("Inbox full: message dropped", null)
    }

    override suspend fun send(frame: ByteArray) {
        val node = resolve(refresh = false) ?: throw linkUnavailable("No paired device runs this app")
        val chunks = FrameChunks.split(frame, chunkPayload)
        for (chunk in chunks) {
            try {
                dataLayer.send(node.id, WearProtocol.PATH_FRAME, chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                counterpart = null // look again next time: the device may have changed or gone
                throw linkUnavailable("Could not reach the paired device", e)
            }
        }
    }

    /** Stops listening to capability changes and ends the consumer. */
    fun close() {
        synchronized(this) {
            closed = true
            listener?.close()
            listener = null
        }
        inbox.close()
    }

    private suspend fun process(message: Message) {
        if (message.path != WearProtocol.PATH_FRAME) return
        var node = resolve(refresh = false)
        if (node?.id != message.sourceNodeId) node = resolve(refresh = true) // maybe the pair changed
        if (node?.id != message.sourceNodeId) {
            onProblem("Ignored a message from a device that is not the counterpart", null)
            return
        }
        assembler.accept(message.data)?.let { frames.emit(it) }
    }

    private suspend fun resolve(refresh: Boolean): DataNode? {
        if (!refresh) counterpart?.let { return it }
        return lookup.withLock {
            if (!refresh) counterpart?.let { return it }
            ensureListener()
            val found = try {
                dataLayer.reachableNodes().sortedWith(compareByDescending<DataNode> { it.isNearby }.thenBy { it.id }).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProblem("Could not list the paired devices", e)
                null
            }
            counterpart = found
            state.value = found != null
            found
        }
    }

    @Synchronized
    private fun ensureListener() {
        if (listener != null || closed) return
        listener = dataLayer.addCapabilityListener {
            scope.launch { resolve(refresh = true) }
        }
    }

    private companion object {
        const val INBOX_CAPACITY = 256
    }
}

