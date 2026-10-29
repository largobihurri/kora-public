package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [KoraNode.dispatch]: serving external callers with capabilities that only a peer offers. */
class NodeDispatchTest {
    private val playlists = Page(listOf(PlaylistSummary("p1", "Morning run", 12)), total = 1, offset = 0)
    private val sonoraCalls = mutableListOf<Caller>()
    private val bridgeAudit = RecordingAudit()
    private val access = FakeExternalAccess(enabled = true)

    private val network = InMemoryPeerNetwork()
    private val sonoraEndpoint = network.endpoint("sonora")
    private val bridgeEndpoint = network.endpoint("bridge")

    private val sonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) {
                sonoraCalls += caller
                playlists
            }
        }
        provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { sonoraCalls += caller } }
    }

    private val bridge = KoraNode(
        nodeId = "bridge",
        policy = DefaultCallerPolicy(access),
        audit = bridgeAudit,
        peerDirectory = bridgeEndpoint,
        transport = bridgeEndpoint,
        clock = FakeClock(),
    )

    private fun connect() {
        sonoraEndpoint.connect(sonora)
        bridgeEndpoint.connect(bridge)
    }

    private val listRequest get() = request(MediaLibrary.ListPlaylists, PageRequest())

    @Test
    fun `a granted agent reaches a peer capability and the peer sees the bridge`() = runTest {
        connect()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        val response = bridge.dispatch(AGENT, listRequest)

        assertEquals(playlists, response.output(MediaLibrary.ListPlaylists))
        assertEquals(listOf(Caller(CallerKind.PEER, "bridge")), sonoraCalls)
        val entry = bridgeAudit.entries.single()
        assertEquals(AGENT, entry.caller)
        assertEquals(AuditOutcome.Ok, entry.outcome)
    }

    @Test
    fun `an agent without the scope is refused before anything reaches the peer`() = runTest {
        connect()
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)

        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(AGENT, listRequest))

        assertTrue(sonoraCalls.isEmpty())
        assertEquals(listOf(AGENT to MediaLibrary.ReadScope), access.denied)
        assertEquals(AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), bridgeAudit.entries.single().outcome)
    }

    @Test
    fun `with the master switch off an agent is refused even with grants`() = runTest {
        connect()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)
        access.enabled = false

        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(AGENT, listRequest))
        assertTrue(sonoraCalls.isEmpty())
    }

    @Test
    fun `the scope is the one of the operation, so control is not implied by read`() = runTest {
        connect()
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)

        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(AGENT, request(MediaPlayback.Play, eus.bihurri.kora.contract.NoInput)))
        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(AGENT, request(MediaPlayback.SeekTo, SeekRequest(1))))
        assertTrue(sonoraCalls.isEmpty())
    }

    @Test
    fun `a peer failure is audited and returned`() = runTest {
        connect()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)
        val bad = listRequest.let { eus.bihurri.kora.contract.KoraRequest(it.id, it.capability, it.version, it.operation, ByteArray(1)) }

        assertFailure(ErrorCode.INVALID_PAYLOAD, bridge.dispatch(AGENT, bad))
        assertEquals(AuditOutcome.Failed(ErrorCode.INVALID_PAYLOAD), bridgeAudit.entries.single().outcome)
    }

    @Test
    fun `an unknown operation of a peer capability is refused`() = runTest {
        connect()
        val unknown = eus.bihurri.kora.contract.KoraRequest("r", "media.library", MediaLibrary.version, "nope", ByteArray(0))

        assertFailure(ErrorCode.UNKNOWN_OPERATION, bridge.dispatch(AGENT, unknown))
    }

    @Test
    fun `without any provider the call is unavailable and audited`() = runTest {
        bridgeEndpoint.connect(bridge)
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, bridge.dispatch(AGENT, listRequest))
        assertEquals(AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), bridgeAudit.entries.single().outcome)
    }

    @Test
    fun `a malformed capability id is unknown`() = runTest {
        val bad = eus.bihurri.kora.contract.KoraRequest("r", "Not A Capability", MediaLibrary.version, "x", ByteArray(0))
        assertFailure(ErrorCode.UNKNOWN_CAPABILITY, bridge.dispatch(AGENT, bad))
    }

    @Test
    fun `a local provider is served by handle with its own policy`() = runTest {
        bridge.provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { playlists } }
        connect()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        assertEquals(playlists, bridge.dispatch(AGENT, listRequest).output(MediaLibrary.ListPlaylists))
        assertTrue(sonoraCalls.isEmpty())
        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(SHELL, listRequest))
    }

    @Test
    fun `the json gate forwards to peers only when asked to`() = runTest {
        connect()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)
        val argument = """{"capability":"media.library","operation":"list_playlists"}"""

        val closed = Json.parseToJsonElement(JsonGate(bridge).call(AGENT, argument)).jsonObject
        assertEquals(false, closed["ok"]!!.jsonPrimitive.boolean)
        assertEquals("unknown_capability", closed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val open = Json.parseToJsonElement(JsonGate(bridge, forwardToPeers = true).call(AGENT, argument)).jsonObject
        assertEquals(true, open["ok"]!!.jsonPrimitive.boolean)
    }
}
