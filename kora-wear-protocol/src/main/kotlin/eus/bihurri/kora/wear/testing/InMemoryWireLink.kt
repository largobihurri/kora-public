package eus.bihurri.kora.wear.testing

import eus.bihurri.kora.wear.WireLink
import eus.bihurri.kora.wear.linkUnavailable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Two [WireLink]s wired to each other, for tests of the relay (and of apps built on it) without Android: what one
 * sends the other receives. Frames cross as bytes.
 *
 * ```
 * val (watch, phone) = InMemoryWireLink.pair()
 * phone.setConnected(false)   // both sides: reachable = false, sends fail until reconnected
 * ```
 */
public class InMemoryWireLink private constructor(private val state: MutableStateFlow<Boolean>) : WireLink {
    private lateinit var other: InMemoryWireLink
    private val inbox = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)

    /** Every frame this side tried to send successfully, oldest first. */
    public val sent: MutableList<ByteArray> = CopyOnWriteArrayList()

    /** While true, frames this side sends are accepted but never delivered (a lost message). */
    @Volatile
    public var dropOutgoing: Boolean = false

    override val incoming: Flow<ByteArray> get() = inbox
    override val reachable: StateFlow<Boolean> get() = state

    override suspend fun send(frame: ByteArray) {
        if (!state.value) throw linkUnavailable("The counterpart is not connected")
        sent += frame
        if (!dropOutgoing) other.inbox.emit(frame.copyOf())
    }

    /** Connects or disconnects both sides at once. */
    public fun setConnected(connected: Boolean) {
        state.value = connected // one flag for both ends, so neither sees a half-connected link
    }

    public companion object {
        /** Two ends of one link, both reachable. */
        public fun pair(): Pair<InMemoryWireLink, InMemoryWireLink> {
            val state = MutableStateFlow(true)
            val a = InMemoryWireLink(state)
            val b = InMemoryWireLink(state)
            a.other = b
            b.other = a
            return a to b
        }
    }
}
