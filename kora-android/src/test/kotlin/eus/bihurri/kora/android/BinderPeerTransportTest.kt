package eus.bihurri.kora.android

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.PeerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BinderPeerTransportTest {
    private val connector = FakeConnector()
    private val remote = FakeRemoteNode()
    private val peer = PeerInfo(NodeDescriptor("eus.bihurri.peer", "Peer"))
    private val descriptors = FakeDescriptors()

    private fun TestScope.transport(
        max: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
        buffer: Int = 8,
        idle: Duration = 10.seconds,
    ): BinderPeerTransport {
        val pool = ConnectionPool(connector, backgroundScope, 5.seconds, 30.seconds)
        return BinderPeerTransport(
            pool,
            maxEnvelopeBytes = max,
            eventBuffer = buffer,
            binderContext = EmptyCoroutineContext,
            streamScope = backgroundScope,
            streamIdleTimeout = idle,
            descriptors = descriptors,
            pumpContext = EmptyCoroutineContext,
            timeSource = testScheduler.timeSource,
        )
    }

    private fun bytes(size: Int = 10) = ByteStream(size.toLong(), ByteArrayInputStream(ByteArray(size) { it.toByte() }))

    private fun code(result: Result<*>): ErrorCode = (result.exceptionOrNull() as KoraException).error.code

    private fun success(id: String = "d") = CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success(id, byteArrayOf(9)))

    private fun failed(id: String = "d", code: ErrorCode = ErrorCode.INVALID_PAYLOAD) =
        CborCodec.encode(KoraResponse.serializer(), KoraResponse.Failure(id, failure(code)))

    private fun request(id: String = "r1") =
        KoraRequest(id, "media.playback", MediaPlayback.version, "play", ByteArray(0))

    private fun subscribeRequest(id: String = "s1") =
        SubscribeRequest(id, "media.playback", MediaPlayback.version, "now_playing_changed")

    private fun connect(index: Int = 0, node: FakeRemoteNode = remote) = connector.attempts[index].events.onConnected(node, node.version)

    private fun failure(code: ErrorCode, message: String = "") = KoraError(code, message)

    private fun event(subscriptionId: String = "s1", payload: Byte = 1) = CborCodec.encode(
        KoraEvent.serializer(),
        KoraEvent(subscriptionId, "media.playback", "now_playing_changed", byteArrayOf(payload)),
    )

    // ---- calls -----------------------------------------------------------------------------------------------

    @Test
    fun `a call travels as CBOR and returns the decoded response`() = runTest {
        val transport = transport()
        remote.onCall = { bytes, callback ->
            val received = CborCodec.decode(KoraRequest.serializer(), bytes)
            callback.onResponse(CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success(received.id, byteArrayOf(9))))
        }

        val answer = async { transport.call(peer, request("r5")) }
        runCurrent()
        connect()

        assertEquals(KoraResponse.Success("r5", byteArrayOf(9)), answer.await())
    }

    @Test
    fun `the answer may arrive later than the call`() = runTest {
        val transport = transport()
        val answer = async { transport.call(peer, request("r5")) }
        runCurrent()
        connect()
        runCurrent()
        assertFalse(answer.isCompleted)

        remote.calls.single().second.onResponse(CborCodec.encode(KoraResponse.serializer(), KoraResponse.Failure("r5", failure(ErrorCode.UNAUTHORIZED))))

        assertEquals(ErrorCode.UNAUTHORIZED, assertInstanceOf(KoraResponse.Failure::class.java, answer.await()).error.code)
    }

    @Test
    fun `a call fails as provider unavailable when the peer never connects`() = runTest {
        val transport = transport()
        val answer = async { runCatching { transport.call(peer, request()) } }
        runCurrent()

        advanceTimeBy(6.seconds)

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (answer.await().exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `peer death fails the calls in flight and the next call reconnects`() = runTest {
        val transport = transport()
        val inFlight = async { runCatching { transport.call(peer, request("a")) } }
        runCurrent()
        connect()
        runCurrent()

        connector.attempts[0].events.onLost("Peer process died")

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (inFlight.await().exceptionOrNull() as KoraException).error.code)

        val second = FakeRemoteNode().apply {
            onCall = { _, cb -> cb.onResponse(CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success("b", ByteArray(0)))) }
        }
        val retry = async { transport.call(peer, request("b")) }
        runCurrent()
        assertEquals(2, connector.attempts.size)
        connect(1, second)

        assertEquals("b", retry.await().id)
    }

    @Test
    fun `a remote exception while sending is reported as unavailable`() = runTest {
        val transport = transport()
        remote.onCall = { _, _ -> throw android.os.DeadObjectException() }
        val answer = async { runCatching { transport.call(peer, request()) } }
        runCurrent()
        connect()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (answer.await().exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `cancelling a call ignores the late answer and lets the connection go idle`() = runTest {
        val transport = transport()
        val answer = async { transport.call(peer, request()) }
        runCurrent()
        connect()
        runCurrent()

        answer.cancel()
        runCurrent()
        remote.calls.single().second.onResponse(CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success("r1", ByteArray(0))))

        advanceTimeBy(31.seconds)
        runCurrent()
        assertTrue(connector.attempts[0].unbound)
    }

    @Test
    fun `an oversized request is refused before touching Binder`() = runTest {
        val transport = transport(max = 20)
        val e = runCatching { transport.call(peer, request()) }.exceptionOrNull() as KoraException
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, e.error.code)
        assertTrue(connector.attempts.isEmpty())
    }

    @Test
    fun `a garbage response is invalid payload`() = runTest {
        val transport = transport()
        remote.onCall = { _, cb -> cb.onResponse(byteArrayOf(1, 2, 3)) }
        val answer = async { runCatching { transport.call(peer, request()) } }
        runCurrent()
        connect()

        assertEquals(ErrorCode.INVALID_PAYLOAD, (answer.await().exceptionOrNull() as KoraException).error.code)
    }

    // ---- subscriptions ---------------------------------------------------------------------------------------

    @Test
    fun `events flow until the provider completes`() = runTest {
        val transport = transport()
        val received = async { transport.subscribe(peer, subscribeRequest()).toList() }
        runCurrent()
        connect()
        runCurrent()
        val sink = remote.subscriptions.single().second

        sink.onEvent(event(payload = 1))
        sink.onEvent(event(payload = 2))
        sink.onComplete()

        assertEquals(listOf<Byte>(1, 2), received.await().map { it.payload.single() })
    }

    @Test
    fun `an error from the provider fails the flow with its code`() = runTest {
        val transport = transport()
        val received = async { runCatching { transport.subscribe(peer, subscribeRequest()).toList() } }
        runCurrent()
        connect()
        runCurrent()

        remote.subscriptions.single().second.onError(CborCodec.encode(KoraError.serializer(), failure(ErrorCode.UNAUTHORIZED, "no")))

        val e = received.await().exceptionOrNull() as KoraException
        assertEquals(KoraError(ErrorCode.UNAUTHORIZED, "no"), e.error)
    }

    @Test
    fun `cancelling the collector cancels the remote subscription and releases the connection`() = runTest {
        val transport = transport()
        val collector = launch { transport.subscribe(peer, subscribeRequest()).collect { } }
        runCurrent()
        connect()
        runCurrent()
        assertEquals(0, remote.cancelled)

        collector.cancel()
        runCurrent()

        assertEquals(1, remote.cancelled)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertTrue(connector.attempts[0].unbound)
    }

    @Test
    fun `an open subscription keeps the connection from going idle`() = runTest {
        val transport = transport()
        val collector = launch { transport.subscribe(peer, subscribeRequest()).collect { } }
        runCurrent()
        connect()

        advanceTimeBy(120.seconds)
        runCurrent()

        assertFalse(connector.attempts[0].unbound)
        collector.cancel()
    }

    @Test
    fun `peer death closes the open subscriptions as unavailable`() = runTest {
        val transport = transport()
        val received = async { runCatching { transport.subscribe(peer, subscribeRequest()).toList() } }
        runCurrent()
        connect()
        runCurrent()
        remote.subscriptions.single().second.onEvent(event())

        connector.attempts[0].events.onLost("Peer process died")

        val e = received.await().exceptionOrNull() as KoraException
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, e.error.code)
    }

    @Test
    fun `a subscription that cannot be opened fails as unavailable`() = runTest {
        val transport = transport()
        val broken = FakeRemoteNode().apply { subscribeFails = android.os.DeadObjectException() }
        val received = async { runCatching { transport.subscribe(peer, subscribeRequest()).toList() } }
        runCurrent()
        connect(node = broken)

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (received.await().exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `a consumer that is too slow gets an error instead of silently losing events`() = runTest {
        val transport = transport(buffer = 2)
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<KoraEvent>()
        var failed: Throwable? = null
        launch {
            transport.subscribe(peer, subscribeRequest())
                .catch { failed = it }
                .collect { seen += it; gate.await() }
        }
        runCurrent()
        connect()
        runCurrent()
        val sink = remote.subscriptions.single().second

        repeat(10) { sink.onEvent(event(payload = it.toByte())) }
        runCurrent()
        assertEquals(1, remote.cancelled) // the peer is told to stop as soon as the overflow is detected

        gate.complete(Unit)
        runCurrent()

        assertEquals(ErrorCode.INTERNAL, (failed as KoraException).error.code)
        assertTrue(seen.size < 10)
    }

    // ---- streams: versions and refusals before anything is sent ----------------------------------------------

    @Test
    fun `a stream call to a peer on protocol 1 is refused before any transaction`() = runTest {
        val transport = transport()
        val old = FakeRemoteNode(version = 1)
        val upload = async { runCatching { transport.callSending(peer, request("u"), bytes()) } }
        runCurrent()
        connect(node = old)

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(upload.await()))
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(runCatching { transport.callReceiving(peer, request("d")) }))
        assertEquals(0, old.transactions)
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `a peer on protocol 1 still serves calls and subscriptions`() = runTest {
        val transport = transport()
        val old = FakeRemoteNode(version = 1).apply {
            onCall = { bytes, cb ->
                val id = CborCodec.decode(KoraRequest.serializer(), bytes).id
                cb.onResponse(CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success(id, ByteArray(0))))
            }
        }
        val answer = async { transport.call(peer, request("r1")) }
        runCurrent()
        connect(node = old)
        assertEquals("r1", answer.await().id)

        val events = async { transport.subscribe(peer, subscribeRequest()).toList() }
        runCurrent()
        old.subscriptions.single().second.onEvent(event())
        old.subscriptions.single().second.onComplete()

        assertEquals(1, events.await().size)
    }

    @Test
    fun `an oversized stream envelope is refused before touching Binder`() = runTest {
        val transport = transport(max = 20)

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(runCatching { transport.callSending(peer, request(), bytes()) }))
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(runCatching { transport.callReceiving(peer, request()) }))
        assertTrue(connector.attempts.isEmpty())
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `a remote exception while sending a stream call is unavailable and closes both ends`() = runTest {
        val transport = transport()
        remote.streamCallFails = android.os.DeadObjectException()
        val upload = async { runCatching { transport.callSending(peer, request("u"), bytes()) } }
        runCurrent()
        connect()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(upload.await()))
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(runCatching { transport.callReceiving(peer, request("d")) }))
        assertEquals(2, descriptors.opened.size)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    // ---- streams: the answer -----------------------------------------------------------------------------------

    @Test
    fun `an upload sends the bytes through the pipe and returns the answer`() = runTest {
        val transport = transport()
        val upload = async { transport.callSending(peer, request("u"), bytes()) }
        runCurrent()
        connect()
        runCurrent()

        val call = remote.sendingCalls.single()
        assertEquals(10L, call.sizeBytes)
        assertEquals((0 until 10).map { it.toByte() }, descriptors.input(call.bytes!!).use { it.readAllBytes() }.toList())
        call.callback.onResponse(success("u"))

        assertEquals("u", upload.await().id)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `waiting too long for an upload answer is a timeout and closes both ends`() = runTest {
        val transport = transport(idle = 3.seconds)
        val upload = async { runCatching { transport.callSending(peer, request("u"), bytes()) } }
        runCurrent()
        connect()
        runCurrent()

        advanceTimeBy(2.seconds)
        runCurrent()
        assertFalse(upload.isCompleted)
        advanceTimeBy(1.5.seconds)

        assertEquals(ErrorCode.TIMEOUT, code(upload.await()))
        remote.sendingCalls.single().bytes!!.close() // the peer's own copy
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `waiting too long for a download answer is a timeout`() = runTest {
        val transport = transport(idle = 3.seconds)
        val download = async { runCatching { transport.callReceiving(peer, request("d")) } }
        runCurrent()
        connect()

        advanceTimeBy(3.5.seconds)

        assertEquals(ErrorCode.TIMEOUT, code(download.await()))
    }

    @Test
    fun `peer death during an upload is unavailable`() = runTest {
        val transport = transport()
        val upload = async { runCatching { transport.callSending(peer, request("u"), bytes()) } }
        runCurrent()
        connect()
        runCurrent()

        connector.attempts[0].events.onLost("Peer process died")

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(upload.await()))
        remote.sendingCalls.single().bytes!!.close()
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `peer death before a download answer is unavailable and a late answer's descriptor is closed`() = runTest {
        val transport = transport()
        val download = async { runCatching { transport.callReceiving(peer, request("d")) } }
        runCurrent()
        connect()
        runCurrent()

        connector.attempts[0].events.onLost("Peer process died")
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(download.await()))
        val late = descriptors.createPipe()
        late.writeEnd.close()
        remote.receivingCalls.single().second.onResponse(success(), late.readEnd, 0)

        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `cancelling a download that waits for its answer closes a late descriptor`() = runTest {
        val transport = transport()
        val download = async { transport.callReceiving(peer, request("d")) }
        runCurrent()
        connect()
        runCurrent()

        download.cancel()
        runCurrent()
        assertTrue(runCatching { download.await() }.exceptionOrNull() is CancellationException)
        val late = descriptors.createPipe()
        late.writeEnd.close()
        remote.receivingCalls.single().second.onResponse(success(), late.readEnd, 0)

        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a failed download answer carries no bytes and closes any descriptor that came with it`() = runTest {
        val transport = transport()
        remote.onCallReceiving = { _, cb ->
            val pipe = descriptors.createPipe()
            pipe.writeEnd.close()
            cb.onResponse(failed("d", ErrorCode.UNAUTHORIZED), pipe.readEnd, 5)
        }
        val download = async { transport.callReceiving(peer, request("d")) }
        runCurrent()
        connect()

        val answer = download.await()
        assertEquals(ErrorCode.UNAUTHORIZED, assertInstanceOf(KoraResponse.Failure::class.java, answer.response).error.code)
        assertNull(answer.bytes)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a download success without bytes, with a negative size or garbage is invalid payload`() = runTest {
        val transport = transport()
        var answer: (cb: eus.bihurri.kora.android.ipc.IKoraStreamCallback) -> Unit = { it.onResponse(success(), null, 0) }
        remote.onCallReceiving = { _, cb -> answer(cb) }
        val first = async { runCatching { transport.callReceiving(peer, request("d")) } }
        runCurrent()
        connect()
        assertEquals(ErrorCode.INVALID_PAYLOAD, code(first.await()))

        answer = { cb -> descriptors.createPipe().also { it.writeEnd.close() }.let { cb.onResponse(success(), it.readEnd, -1) } }
        assertEquals(ErrorCode.INVALID_PAYLOAD, code(runCatching { transport.callReceiving(peer, request("d")) }))

        answer = { cb -> descriptors.createPipe().also { it.writeEnd.close() }.let { cb.onResponse(byteArrayOf(1, 2, 3), it.readEnd, 0) } }
        assertEquals(ErrorCode.INVALID_PAYLOAD, code(runCatching { transport.callReceiving(peer, request("d")) }))

        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `downloaded bytes hold the connection until they are closed`() = runTest {
        val transport = transport()
        remote.onCallReceiving = { _, cb ->
            val (readEnd, _) = descriptors.filled(byteArrayOf(1, 2, 3))
            cb.onResponse(success(), readEnd, 3)
        }
        val download = async { transport.callReceiving(peer, request("d")) }
        runCurrent()
        connect()
        val answer = download.await()

        advanceTimeBy(31.seconds)
        runCurrent()
        assertFalse(connector.attempts[0].unbound)
        assertEquals(listOf<Byte>(1, 2, 3), answer.bytes!!.input.readAllBytes().toList())
        answer.close()
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(connector.attempts[0].unbound)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `peer death while a download is read fails the read with unavailable and closes the descriptor`() = runTest {
        val transport = transport()
        var writer: java.io.OutputStream? = null
        remote.onCallReceiving = { _, cb ->
            val (readEnd, out) = descriptors.filled(byteArrayOf(1, 2), keepWriting = true)
            writer = out
            cb.onResponse(success(), readEnd, 10)
        }
        val download = async { transport.callReceiving(peer, request("d")) }
        runCurrent()
        connect()
        val answer = download.await()
        assertEquals(2, answer.bytes!!.input.read(ByteArray(2)))

        connector.attempts[0].events.onLost("Peer process died")

        val failure = assertThrows(KoraException::class.java) { answer.bytes!!.input.read() }
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, failure.error.code)
        writer!!.close()
        answer.close()
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a download read that waits too long for bytes is a timeout`() = runTest {
        val transport = transport(idle = 500.milliseconds)
        var writer: java.io.OutputStream? = null
        remote.onCallReceiving = { _, cb ->
            val (readEnd, out) = descriptors.filled(byteArrayOf(1), keepWriting = true)
            writer = out
            cb.onResponse(success(), readEnd, 10)
        }
        val download = async { transport.callReceiving(peer, request("d")) }
        runCurrent()
        connect()
        val answer = download.await()
        assertEquals(1, answer.bytes!!.input.read(ByteArray(5)))

        // The next read would wait; the watchdog runs on virtual time, so let it fire before reading.
        val reading = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { answer.bytes!!.input.read() } }
        Thread.sleep(100)
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(ErrorCode.TIMEOUT, code(reading.await()))
        writer!!.close()
        answer.close()
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    // ---- describe --------------------------------------------------------------------------------------------

    @Test
    fun `describe returns the advertised descriptor and frees the connection`() = runTest {
        val transport = transport()
        remote.descriptor = CborCodec.encode(NodeDescriptor.serializer(), NodeDescriptor("eus.bihurri.peer", "Peer"))
        val descriptor = async { transport.describe("eus.bihurri.peer") }
        runCurrent()
        connect()

        assertEquals(NodeDescriptor("eus.bihurri.peer", "Peer"), descriptor.await())
        advanceTimeBy(31.seconds)
        runCurrent()
        assertTrue(connector.attempts[0].unbound)
    }

    @Test
    fun `describe maps a refusal by the peer to unavailable`() = runTest {
        val transport = transport()
        val refusing = FakeRemoteNode().apply { describeFails = SecurityException("not trusted") }
        val answer = async { runCatching { transport.describe("eus.bihurri.peer") } }
        runCurrent()
        connect(node = refusing)

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (answer.await().exceptionOrNull() as KoraException).error.code)
    }
}
