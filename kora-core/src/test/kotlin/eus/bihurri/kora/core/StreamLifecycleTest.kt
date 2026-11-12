package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.StreamDirection
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Who closes the bytes of a stream call when it does not end with a typed answer: an enclosing timeout, an answer
 * that cannot be handed on, a transport or routing failure, a handler that throws, a source that fails to close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamLifecycleTest {
    private val internalError = KoraError(ErrorCode.INTERNAL, "Internal error")

    // ---- Enclosing timeouts --------------------------------------------------------------------------------------

    @Test
    fun `an enclosing timeout propagates untranslated, closes the upload and is not audited`() = runTest {
        val audit = RecordingAudit()
        val internalErrors = mutableListOf<Throwable>()
        val node = KoraNode("n", audit = audit, onInternalError = { internalErrors += it }).apply {
            provide(Blobs) {
                onUpload(Blobs.Put) { _, bytes ->
                    delay(30.seconds)
                    bytes.text().length.toLong()
                }
                onDownload(Blobs.Get) { name ->
                    delay(30.seconds)
                    WithBytes(name, ByteStream(1, TrackedSource("x")))
                }
                on(Blobs.Drop) { }
            }
        }
        val source = TrackedSource("abc")

        thrownBy<TimeoutCancellationException> {
            withTimeout(1.seconds) { node.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        }
        thrownBy<TimeoutCancellationException> { withTimeout(1.seconds) { node.callReceiving(Blobs.Get, "a") } }

        assertEquals(2_000L, currentTime, "neither call waited for its handler")
        assertEquals(1, source.closes)
        assertEquals(emptyList<AuditEntry>(), audit.entries)
        assertEquals(emptyList<Throwable>(), internalErrors)
    }

    @Test
    fun `an enclosing timeout of a call to a peer closes the upload and the peer audits nothing`() = runTest {
        val network = InMemoryPeerNetwork()
        val filesEndpoint = network.endpoint("files")
        val appEndpoint = network.endpoint("app")
        val filesAudit = RecordingAudit()
        filesEndpoint.connect(
            KoraNode("files", audit = filesAudit, peerDirectory = filesEndpoint, transport = filesEndpoint).apply {
                provide(Blobs) {
                    onUpload(Blobs.Put) { _, bytes ->
                        delay(30.seconds)
                        bytes.text().length.toLong()
                    }
                    onDownload(Blobs.Get) { name -> WithBytes(name, ByteStream(1, TrackedSource("x"))) }
                    on(Blobs.Drop) { }
                }
            },
        )
        val app = KoraNode("app", peerDirectory = appEndpoint, transport = appEndpoint).also(appEndpoint::connect)
        val source = TrackedSource("abc")

        thrownBy<TimeoutCancellationException> {
            withTimeout(1.seconds) { app.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        }

        assertEquals(1, source.closes)
        assertEquals(emptyList<AuditEntry>(), filesAudit.entries)
    }

    @Test
    fun `a download that answers after an enclosing timeout closes its bytes`() = runTest {
        val localSource = TrackedSource("x")
        val local = KoraNode("n").apply {
            provide(Blobs) {
                onUpload(Blobs.Put) { _, _ -> 0L }
                onDownload(Blobs.Get) { name ->
                    withContext(NonCancellable) { delay(30.seconds) } // a handler that ignores cancellation
                    WithBytes(name, ByteStream(1, localSource))
                }
                on(Blobs.Drop) { }
            }
        }
        val remoteSource = TrackedSource("x")
        val transport = ScriptedStreaming(
            onReceive = { request ->
                withContext(NonCancellable) { delay(30.seconds) } // a transport that ignores cancellation
                StreamedResponse(KoraResponse.Success(request.id, Blobs.Get.encodeOutput(CborCodec, "a")), ByteStream(1, remoteSource))
            },
        )
        val near = KoraNode("near", peerDirectory = farPeer(Blobs), transport = transport)

        thrownBy<TimeoutCancellationException> { withTimeout(1.seconds) { local.callReceiving(Blobs.Get, "a") } }
        thrownBy<TimeoutCancellationException> { withTimeout(1.seconds) { near.callReceiving(Blobs.Get, "a") } }

        assertEquals(1, localSource.closes, "nobody receives the bytes of the local handler")
        assertEquals(1, remoteSource.closes, "nobody receives the bytes the transport answered")
        assertEquals(listOf("callReceiving"), transport.calls)
    }

    // ---- Download answers that cannot be handed on -----------------------------------------------------------------

    @Test
    fun `a peer's download answer that does not match the request closes its bytes`() = runTest {
        val source = TrackedSource("abc")
        val transport = ScriptedStreaming(
            onReceive = { StreamedResponse(KoraResponse.Success("other", Blobs.Get.encodeOutput(CborCodec, "a")), ByteStream(3, source)) },
        )
        val near = KoraNode("near", peerDirectory = farPeer(Blobs), transport = transport)

        val failure = thrownBy<KoraException> { near.callReceiving(Blobs.Get, "a") }

        assertEquals(KoraError(ErrorCode.INTERNAL, "Response does not match the request"), failure.error)
        assertEquals(1, source.closes)
        assertEquals(3, source.available(), "not a byte was read")
    }

    @Test
    fun `a peer's download answer whose output cannot be decoded closes its bytes`() = runTest {
        val source = TrackedSource("abc")
        val transport = ScriptedStreaming(
            onReceive = { request -> StreamedResponse(KoraResponse.Success(request.id, byteArrayOf(0x7f, 0x01)), ByteStream(3, source)) },
        )
        val near = KoraNode("near", peerDirectory = farPeer(Blobs), transport = transport)

        val failure = thrownBy<KoraException> { near.callReceiving(Blobs.Get, "a") }

        assertEquals(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed output of 'get'"), failure.error)
        assertEquals(1, source.closes)
        assertEquals(3, source.available(), "not a byte was read")
    }

    @Test
    fun `a download whose output cannot be encoded closes its bytes and answers internal`() = runTest {
        val audit = RecordingAudit()
        val internalErrors = mutableListOf<Throwable>()
        val sources = List(2) { TrackedSource("abc") }
        var served = 0
        val node = KoraNode("n", audit = audit, clock = FakeClock(), onInternalError = { internalErrors += it }).apply {
            provide(Garbled) { onDownload(Garbled.Get) { name -> WithBytes(name, ByteStream(3, sources[served++])) } }
        }

        val inbound = node.handleReceiving(PEER, request(Garbled.Get, "a", id = "d1"))
        val local = thrownBy<KoraException> { node.callReceiving(Garbled.Get, "a") }

        assertEquals(KoraResponse.Failure("d1", internalError), inbound.response)
        assertNull(inbound.bytes)
        assertEquals(internalError, local.error)
        assertEquals(listOf(1, 1), sources.map { it.closes })
        assertEquals(2, internalErrors.size)
        internalErrors.forEach { assertInstanceOf(SerializationException::class.java, it) }
        assertEquals(
            listOf(PEER, Caller(CallerKind.SELF, "n")).map {
                AuditEntry(1_000, it, "test.garbled", "get", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.INTERNAL), 0)
            },
            audit.entries,
        )
    }

    // ---- Transport failures ----------------------------------------------------------------------------------------

    @Test
    fun `an upload to a peer that fails in the transport closes the bytes once`() = runTest {
        val sources = List(3) { TrackedSource("abc") }
        val internalErrors = mutableListOf<Throwable>()
        val pipe = IOException("pipe")
        val refusal = KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Peer 'far' cannot take a byte stream")
        val broken = KoraNode(
            "near",
            peerDirectory = farPeer(Blobs),
            transport = ScriptedStreaming(onSend = { _, _ -> throw pipe }),
            onInternalError = { internalErrors += it },
        )
        val refusing = KoraNode("near", peerDirectory = farPeer(Blobs), transport = ScriptedStreaming(onSend = { _, _ -> throw KoraException(refusal) }))
        val crashing = KoraNode("near", peerDirectory = farPeer(Blobs), transport = ScriptedStreaming(onSend = { _, _ -> throw StackOverflowError() }))

        val failed = thrownBy<KoraException> { broken.callSending(Blobs.Put, "a", ByteStream(3, sources[0])) }
        val refused = thrownBy<KoraException> { refusing.callSending(Blobs.Put, "a", ByteStream(3, sources[1])) }
        thrownBy<StackOverflowError> { crashing.callSending(Blobs.Put, "a", ByteStream(3, sources[2])) }

        assertEquals(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Peer failure"), failed.error)
        assertSame(pipe, failed.cause)
        assertEquals(refusal, refused.error)
        assertEquals(listOf(1, 1, 1), sources.map { it.closes })
        assertEquals(listOf<Throwable>(pipe), internalErrors)
    }

    @Test
    fun `a download from a peer that fails in the transport is provider_unavailable`() = runTest {
        val internalErrors = mutableListOf<Throwable>()
        val pipe = IOException("pipe")
        val near = KoraNode(
            "near",
            peerDirectory = farPeer(Blobs),
            transport = ScriptedStreaming(onReceive = { throw pipe }),
            onInternalError = { internalErrors += it },
        )

        val failure = thrownBy<KoraException> { near.callReceiving(Blobs.Get, "a") }

        assertEquals(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Peer failure"), failure.error)
        assertEquals(listOf<Throwable>(pipe), internalErrors)
    }

    @Test
    fun `an upload the peer's policy refuses is closed unread and its handler never runs`() = runTest {
        val network = InMemoryPeerNetwork()
        val filesEndpoint = network.endpoint("files")
        val appEndpoint = network.endpoint("app")
        val store = BlobStore()
        val filesAudit = RecordingAudit()
        filesEndpoint.connect(
            KoraNode(
                "files",
                policy = { _, _ -> false },
                audit = filesAudit,
                clock = FakeClock(),
                peerDirectory = filesEndpoint,
                transport = filesEndpoint,
            ).also(store::provideOn),
        )
        val app = KoraNode("app", peerDirectory = appEndpoint, transport = appEndpoint).also(appEndpoint::connect)
        val source = TrackedSource("abc")

        val upload = thrownBy<KoraException> { app.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        val download = thrownBy<KoraException> { app.callReceiving(Blobs.Get, "a") }

        assertEquals(ErrorCode.UNAUTHORIZED, upload.error.code)
        assertEquals(ErrorCode.UNAUTHORIZED, download.error.code)
        assertEquals(3, source.available(), "not a byte was read")
        assertEquals(1, source.closes)
        assertEquals(emptyList<Caller>(), store.callers)
        assertEquals(
            listOf("put", "get").map {
                AuditEntry(1_000, Caller(CallerKind.PEER, "app"), "test.blobs", it, AuditKind.CALL, AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), 0)
            },
            filesAudit.entries,
        )
    }

    // ---- Routing ---------------------------------------------------------------------------------------------------

    @Test
    fun `routing to a peer that no route lists closes the bytes unread`() = runTest {
        val near = KoraNode("near", peerDirectory = farPeer(Blobs), transport = RoutingPeerTransport(emptyList()))
        val source = TrackedSource("abc")

        val upload = thrownBy<KoraException> { near.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        val download = thrownBy<KoraException> { near.callReceiving(Blobs.Get, "a") }

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, upload.error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, download.error.code)
        assertEquals(3, source.available(), "not a byte was read")
        assertEquals(1, source.closes)
    }

    @Test
    fun `a target nobody serves closes the bytes unread and sends nothing`() = runTest {
        val transport = ScriptedStreaming()
        val near = KoraNode("near", peerDirectory = farPeer(Blobs), transport = transport)
        val sources = List(2) { TrackedSource("abc") }

        val unknownPeer = thrownBy<KoraException> { near.callSending(Blobs.Put, "a", ByteStream(3, sources[0]), Target.Peer("nobody")) }
        val notLocal = thrownBy<KoraException> { near.callSending(Blobs.Put, "a", ByteStream(3, sources[1]), Target.Local) }

        assertEquals(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "No provider offers 'test.blobs'"), unknownPeer.error)
        assertEquals(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "No provider offers 'test.blobs'"), notLocal.error)
        assertEquals(listOf(1, 1), sources.map { it.closes })
        assertEquals(listOf(3, 3), sources.map { it.available() }, "not a byte was read")
        assertEquals(emptyList<String>(), transport.calls)
    }

    // ---- Handlers and sources that fail ------------------------------------------------------------------------------

    @Test
    fun `an Error thrown by a stream handler propagates and the upload is closed`() = runTest {
        val audit = RecordingAudit()
        val node = KoraNode("n", audit = audit).apply {
            provide(Blobs) {
                onUpload(Blobs.Put) { _, _ -> throw NotImplementedError("upload") }
                onDownload(Blobs.Get) { throw NotImplementedError("download") }
                on(Blobs.Drop) { }
            }
        }
        val source = TrackedSource("abc")

        val upload = thrownBy<NotImplementedError> { node.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        val download = thrownBy<NotImplementedError> { node.callReceiving(Blobs.Get, "a") }

        assertEquals("upload", upload.message)
        assertEquals("download", download.message)
        assertEquals(1, source.closes)
        assertEquals(emptyList<AuditEntry>(), audit.entries, "an Error is not a failed call")
    }

    @Test
    fun `an upload of the wrong size that escapes the handler answers invalid_payload, not internal`() = runTest {
        val audit = RecordingAudit()
        val internalErrors = mutableListOf<Throwable>()
        val store = BlobStore() // its upload handler reads to the end and catches nothing
        val node = KoraNode("n", audit = audit, clock = FakeClock(), onInternalError = { internalErrors += it }).also(store::provideOn)

        val short = node.handleSending(PEER, request(Blobs.Put, "a", id = "u1"), ByteStream(5, TrackedSource("abc")))
        val over = node.handleSending(PEER, request(Blobs.Put, "b", id = "u2"), ByteStream(3, TrackedSource("abcd")))
        val local = thrownBy<KoraException> { node.callSending(Blobs.Put, "c", ByteStream(5, TrackedSource("abc"))) }

        assertEquals(KoraResponse.Failure("u1", KoraError(ErrorCode.INVALID_PAYLOAD, "Byte stream ended after 3 of 5 bytes")), short)
        assertEquals(KoraResponse.Failure("u2", KoraError(ErrorCode.INVALID_PAYLOAD, "Byte stream runs over its 3 bytes")), over)
        assertEquals(KoraError(ErrorCode.INVALID_PAYLOAD, "Byte stream ended after 3 of 5 bytes"), local.error)
        assertEquals(emptyList<Throwable>(), internalErrors, "the provider has no bug to report")
        assertEquals(List(3) { AuditOutcome.Failed(ErrorCode.INVALID_PAYLOAD) }, audit.entries.map { it.outcome })
        assertEquals(emptyMap<String, String>(), store.content)
    }

    @Test
    fun `an ordinary IOException, or a size violation of another stream, from an upload handler still answers internal`() = runTest {
        val internalErrors = mutableListOf<Throwable>()
        val pipe = IOException("pipe")
        val node = KoraNode("n", onInternalError = { internalErrors += it }).apply {
            provide(Blobs) {
                onUpload(Blobs.Put) { name, bytes ->
                    bytes.text() // the upload itself is whole
                    if (name == "io") throw pipe
                    ByteStream(5, TrackedSource("abc")).text() // a stream of the provider's own that ends short
                    0L
                }
                onDownload(Blobs.Get) { throw NotImplementedError() }
                on(Blobs.Drop) { }
            }
        }

        val io = node.handleSending(PEER, request(Blobs.Put, "io", id = "u1"), ByteStream(3, TrackedSource("abc")))
        val other = node.handleSending(PEER, request(Blobs.Put, "other", id = "u2"), ByteStream(3, TrackedSource("abc")))

        assertEquals(KoraResponse.Failure("u1", internalError), io)
        assertEquals(KoraResponse.Failure("u2", internalError), other)
        assertEquals(2, internalErrors.size)
        assertSame(pipe, internalErrors[0])
        assertInstanceOf(ByteStreamSizeException::class.java, internalErrors[1])
    }

    @Test
    fun `a source whose close throws IOException does not change an upload's answer`() = runTest {
        val store = BlobStore()
        val node = KoraNode("n").also(store::provideOn)

        val inbound = node.handleSending(PEER, request(Blobs.Put, "a", id = "u1"), ByteStream(3, FailingClose("abc", IOException("close"))))
        val local = node.callSending(Blobs.Put, "b", ByteStream(3, FailingClose("abc", IOException("close"))))

        assertEquals("u1", inbound.id)
        assertEquals(3L, inbound.output(Blobs.Put))
        assertEquals(3L, local)
        assertEquals(mapOf("a" to "abc", "b" to "abc"), store.content)
    }

    @Test
    fun `a source whose close throws any exception does not change an upload's answer, but an Error propagates`() = runTest {
        val store = BlobStore()
        val audit = RecordingAudit()
        val node = KoraNode("n", audit = audit).also(store::provideOn)

        val inbound = node.handleSending(
            PEER,
            request(Blobs.Put, "a", id = "u1"),
            ByteStream(3, FailingClose("abc", IllegalStateException("close"))),
        )
        val local = node.callSending(Blobs.Put, "b", ByteStream(3, FailingClose("abc", IllegalStateException("close"))))
        val error = thrownBy<NotImplementedError> {
            node.handleSending(PEER, request(Blobs.Put, "c"), ByteStream(3, FailingClose("abc", NotImplementedError("close"))))
        }

        assertEquals("u1", inbound.id)
        assertEquals(3L, inbound.output(Blobs.Put))
        assertEquals(3L, local)
        assertEquals("close", error.message)
        assertEquals(mapOf("a" to "abc", "b" to "abc", "c" to "abc"), store.content, "every handler read its bytes")
        assertEquals(List(3) { AuditOutcome.Ok }, audit.entries.map { it.outcome })
    }
}

// ---- Fixtures --------------------------------------------------------------------------------------------------

/** Output serializer that cannot encode anything. */
private object Unencodable : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("test.Unencodable", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String): Unit = throw SerializationException("cannot encode")

    override fun deserialize(decoder: Decoder): String = decoder.decodeString()
}

/** A capability with one download whose output cannot be encoded. */
private object Garbled : Capability(CapabilityId("test.garbled"), CapabilityVersion(1, 0)) {
    val Get: OperationSpec<String, String> = operation(
        "get", String.serializer(), Unencodable, Scope("test.garbled.read"), "Download whose output cannot be encoded.",
        StreamDirection.DOWNLOAD,
    )
}

/** A source of bytes whose close throws [failure]. */
private class FailingClose(text: String, private val failure: Throwable) : ByteArrayInputStream(text.encodeToByteArray()) {
    override fun close(): Unit = throw failure
}

/** A [StreamingPeerTransport] whose stream answers are scripted and which records the stream calls it was given. */
private class ScriptedStreaming(
    private val onSend: suspend (KoraRequest, ByteStream) -> KoraResponse = { request, _ -> unexpected(request) },
    private val onReceive: suspend (KoraRequest) -> StreamedResponse = { request -> StreamedResponse(unexpected(request), null) },
) : StreamingPeerTransport {
    val calls = mutableListOf<String>()

    override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
        calls += "call"
        return unexpected(request)
    }

    override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> {
        calls += "subscribe"
        return emptyFlow()
    }

    override suspend fun callSending(peer: PeerInfo, request: KoraRequest, bytes: ByteStream): KoraResponse {
        calls += "callSending"
        return onSend(request, bytes)
    }

    override suspend fun callReceiving(peer: PeerInfo, request: KoraRequest): StreamedResponse {
        calls += "callReceiving"
        return onReceive(request)
    }
}

private fun unexpected(request: KoraRequest) = KoraResponse.Failure(request.id, KoraError(ErrorCode.INTERNAL, "unexpected"))

/** A directory with one peer, `far`, that offers [capabilities]. */
private fun farPeer(vararg capabilities: Capability): PeerDirectory = object : PeerDirectory {
    override val peers: StateFlow<List<PeerInfo>> =
        MutableStateFlow(listOf(PeerInfo(NodeDescriptor("far", "far", capabilities.map { it.descriptor() }))))
}
