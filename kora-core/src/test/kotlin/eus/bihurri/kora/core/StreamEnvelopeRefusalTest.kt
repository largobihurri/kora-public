package eus.bihurri.kora.core

import eus.bihurri.kora.contract.CapabilityRegistry
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A request for a stream operation that arrives without its stream (the JSON gate, the binary gate, the relay, an
 * older Binder peer) is refused with `provider_unavailable` after the policy, and audited.
 */
class StreamEnvelopeRefusalTest {
    private val audit = RecordingAudit()
    private val access = FakeExternalAccess(enabled = true)
    private val store = BlobStore()
    private val registry = CapabilityRegistry(listOf(Blobs))
    private val node = KoraNode("files", policy = DefaultCallerPolicy(access), audit = audit, clock = FakeClock(), registry = registry)
        .also(store::provideOn)

    private suspend fun JsonGate.code(caller: Caller, operation: String): String =
        Json.parseToJsonElement(call(caller, """{"capability":"test.blobs","operation":"$operation","input":"a"}"""))
            .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `a stream operation without its stream is unavailable after the policy, and audited`() = runTest {
        access.grants[AGENT] = setOf(Blobs.Write)

        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, node.handle(PEER, request(Blobs.Put, "a")))
        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, node.handle(PEER, request(Blobs.Get, "a")))
        assertFailure(ErrorCode.UNAUTHORIZED, node.handle(AGENT, request(Blobs.Get, "a")))
        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, node.handle(AGENT, request(Blobs.Put, "a")))

        assertEquals(emptyList<Caller>(), store.callers)
        assertEquals(listOf(AGENT to Blobs.Read), access.denied)
        assertEquals(
            listOf(
                AuditEntry(1_000, PEER, "test.blobs", "put", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), 0),
                AuditEntry(1_000, PEER, "test.blobs", "get", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), 0),
                AuditEntry(1_000, AGENT, "test.blobs", "get", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), 0),
                AuditEntry(1_000, AGENT, "test.blobs", "put", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), 0),
            ),
            audit.entries,
        )
    }

    @Test
    fun `the json gate cannot carry a stream and a refused caller learns nothing about it`() = runTest {
        val gate = JsonGate(node, registry = registry)
        access.grants[AGENT] = setOf(Blobs.Read)

        assertEquals("unauthorized", gate.code(SHELL, "put"))
        assertEquals("provider_unavailable", gate.code(AGENT, "get"))
        assertEquals(emptyList<Caller>(), store.callers)
        assertEquals(listOf(SHELL to Blobs.Write), access.denied)
        assertEquals(
            listOf(AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE)),
            audit.entries.map { it.outcome },
        )
    }

    @Test
    fun `a bridge refuses to forward a stream operation and nothing reaches the peer`() = runTest {
        val network = InMemoryPeerNetwork()
        val filesEndpoint = network.endpoint("files")
        val bridgeEndpoint = network.endpoint("bridge")
        val peerStore = BlobStore()
        val files = KoraNode("files", peerDirectory = filesEndpoint, transport = filesEndpoint).also(peerStore::provideOn)
        val bridgeAudit = RecordingAudit()
        val bridge = KoraNode(
            nodeId = "bridge",
            policy = DefaultCallerPolicy(access),
            audit = bridgeAudit,
            peerDirectory = bridgeEndpoint,
            transport = bridgeEndpoint,
            clock = FakeClock(),
            registry = registry,
        )
        filesEndpoint.connect(files)
        bridgeEndpoint.connect(bridge)
        access.grants[AGENT] = setOf(Blobs.Read, Blobs.Write)

        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, bridge.dispatch(AGENT, request(Blobs.Put, "a")))
        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, bridge.dispatch(AGENT, request(Blobs.Get, "a")))
        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(SHELL, request(Blobs.Put, "a")))
        assertEquals(Unit, bridge.dispatch(AGENT, request(Blobs.Drop, "a")).output(Blobs.Drop))

        assertEquals(listOf(Caller(CallerKind.PEER, "bridge")), peerStore.callers, "only the plain operation is forwarded")
        assertEquals(listOf(SHELL to Blobs.Write), access.denied)
        assertEquals(
            listOf(
                AuditEntry(1_000, AGENT, "test.blobs", "put", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), 0),
                AuditEntry(1_000, AGENT, "test.blobs", "get", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), 0),
                AuditEntry(1_000, SHELL, "test.blobs", "put", AuditKind.CALL, AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), 0),
                AuditEntry(1_000, AGENT, "test.blobs", "drop", AuditKind.CALL, AuditOutcome.Ok, 0),
            ),
            bridgeAudit.entries,
        )
    }

    @Test
    fun `a bridge decides the refusal itself and sends nothing to the peer`() = runTest {
        val transport = SentRecordingTransport()
        val directory = object : PeerDirectory {
            override val peers: StateFlow<List<PeerInfo>> =
                MutableStateFlow(listOf(PeerInfo(NodeDescriptor("files", "files", listOf(Blobs.descriptor())))))
        }
        val bridgeAudit = RecordingAudit()
        val bridge = KoraNode(
            nodeId = "bridge",
            policy = DefaultCallerPolicy(access),
            audit = bridgeAudit,
            peerDirectory = directory,
            transport = transport,
            clock = FakeClock(),
            registry = registry,
        )
        access.grants[AGENT] = setOf(Blobs.Read, Blobs.Write)

        val upload = bridge.dispatch(AGENT, request(Blobs.Put, "a", id = "u1"))
        val download = bridge.dispatch(AGENT, request(Blobs.Get, "a", id = "d1"))
        val plain = bridge.dispatch(AGENT, request(Blobs.Drop, "a", id = "p1"))

        assertEquals(
            KoraResponse.Failure(
                "u1",
                KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Operation 'put' carries a byte stream, which this path cannot carry"),
            ),
            upload,
        )
        assertEquals(
            KoraResponse.Failure(
                "d1",
                KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Operation 'get' carries a byte stream, which this path cannot carry"),
            ),
            download,
        )
        assertEquals(Unit, plain.output(Blobs.Drop))
        assertEquals(listOf("call p1"), transport.sent, "only the plain operation reaches the transport")
        assertEquals(
            listOf(ErrorCode.PROVIDER_UNAVAILABLE, ErrorCode.PROVIDER_UNAVAILABLE).map(AuditOutcome::Failed) + AuditOutcome.Ok,
            bridgeAudit.entries.map { it.outcome },
        )
    }
}

/** A [StreamingPeerTransport] that records every request it is handed and answers plain calls with no output. */
private class SentRecordingTransport : StreamingPeerTransport {
    val sent = mutableListOf<String>()

    override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
        sent += "call ${request.id}"
        return KoraResponse.Success(request.id, Blobs.Drop.encodeOutput(CborCodec, Unit))
    }

    override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> {
        sent += "subscribe ${request.subscriptionId}"
        return emptyFlow()
    }

    override suspend fun callSending(peer: PeerInfo, request: KoraRequest, bytes: ByteStream): KoraResponse {
        sent += "callSending ${request.id}"
        return KoraResponse.Failure(request.id, KoraError(ErrorCode.INTERNAL, "unexpected"))
    }

    override suspend fun callReceiving(peer: PeerInfo, request: KoraRequest): StreamedResponse {
        sent += "callReceiving ${request.id}"
        return StreamedResponse(KoraResponse.Failure(request.id, KoraError(ErrorCode.INTERNAL, "unexpected")), null)
    }
}
