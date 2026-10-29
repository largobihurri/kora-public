package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tools that follow the peers, and the list_changed notifications for both lifecycles. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpNotificationsTest {
    private val access = TestAccess()
    private val audit = TestAudit()
    private val peerCallers = mutableListOf<String>()

    private val network = InMemoryPeerNetwork()
    private val sonoraEndpoint = network.endpoint("sonora")
    private val bridgeEndpoint = network.endpoint("bridge")

    private val sonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) {
                peerCallers += caller.toString()
                Page(playlists, playlists.size)
            }
        }
    }

    private val bridge = KoraNode(
        nodeId = "bridge",
        policy = DefaultCallerPolicy(access),
        audit = audit,
        peerDirectory = bridgeEndpoint,
        transport = bridgeEndpoint,
    )

    private val session = McpSession(bridge, AGENT, SERVER_INFO)

    private val changed = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""

    private suspend fun handshake() {
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        session.handle(rpc("notifications/initialized", id = null))
    }

    private suspend fun names(): List<String> =
        parse(session.handle(rpc("tools/list"))).resultObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun connectBridge() = bridgeEndpoint.connect(bridge)

    @Test
    fun `a peer joining and leaving changes the tools and emits list_changed`() = runTest(UnconfinedTestDispatcher()) {
        connectBridge()
        handshake()
        val received = mutableListOf<String>()
        val job = launch { session.notifications.collect { received += it } }
        assertEquals(emptyList<String>(), names())

        sonoraEndpoint.connect(sonora)

        assertEquals(listOf(changed), received)
        assertEquals(
            listOf("media_library__list_playlists", "media_library__get_playlist_tracks", "media_library__search_tracks"),
            names(),
        )

        sonoraEndpoint.disconnect()

        assertEquals(listOf(changed, changed), received)
        assertEquals(emptyList<String>(), names())
        job.cancel()
    }

    @Test
    fun `a local provider registered later is announced too`() = runTest(UnconfinedTestDispatcher()) {
        connectBridge()
        handshake()
        val received = mutableListOf<String>()
        val job = launch { session.notifications.collect { received += it } }

        bridge.provideStubbed(MediaPlayback) { }

        assertEquals(listOf(changed), received)
        assertTrue(names().first().startsWith("media_playback__"))
        job.cancel()
    }

    @Test
    fun `nothing is announced when reachability does not change`() = runTest(UnconfinedTestDispatcher()) {
        connectBridge()
        handshake()
        val received = mutableListOf<String>()
        val job = launch { session.notifications.collect { received += it } }

        sonoraEndpoint.connect(sonora)
        network.refresh()
        network.refresh()

        assertEquals(1, received.size)
        job.cancel()
    }

    @Test
    fun `legacy notifications wait for notifications initialized`() = runTest(UnconfinedTestDispatcher()) {
        connectBridge()
        val received = mutableListOf<String>()
        val job = launch { session.notifications.collect { received += it } }

        sonoraEndpoint.connect(sonora)
        assertTrue(received.isEmpty())

        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        sonoraEndpoint.disconnect()
        assertTrue(received.isEmpty())

        session.handle(rpc("notifications/initialized", id = null))
        sonoraEndpoint.connect(sonora)
        assertEquals(1, received.size)
        job.cancel()
    }

    @Test
    fun `an agent reaches a peer capability under the bridge policy`() = runTest {
        connectBridge()
        sonoraEndpoint.connect(sonora)
        handshake()
        val call = rpc("tools/call", parse("""{"name":"media_library__list_playlists"}"""))

        val refused = parse(session.handle(call))
        assertTrue(refused.resultObject["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unauthorized: Scope 'media.library.read' is not granted", refused.resultObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(peerCallers.isEmpty())

        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)
        val allowed = parse(session.handle(call))

        assertFalse(allowed.resultObject["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, allowed.resultObject["structuredContent"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("PEER:bridge"), peerCallers)
        assertEquals(listOf(AGENT, AGENT), audit.entries.map { it.caller })
    }

    @Test
    fun `a peer that left makes its tool provider_unavailable`() = runTest {
        connectBridge()
        sonoraEndpoint.connect(sonora)
        handshake()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)
        sonoraEndpoint.disconnect()

        val answer = parse(session.handle(rpc("tools/call", parse("""{"name":"media_library__list_playlists"}"""))))

        assertTrue(answer.resultObject["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            answer.resultObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("provider_unavailable:"),
        )
    }

    // ---- subscriptions/listen (modern) -----------------------------------------------------------------------

    private fun listen(id: Int, notifications: String = """{"toolsListChanged":true}""") =
        rpc("subscriptions/listen", withMeta("""{"notifications":$notifications}"""), id = JsonPrimitive(id))

    @Test
    fun `listen acknowledges first and then streams list_changed tagged with the subscription id`() = runTest(UnconfinedTestDispatcher()) {
        connectBridge()
        val received = mutableListOf<JsonObject>()
        val job = launch { session.serve(listen(42)).collect { received += parse(it) } }

        val ack = received.single()
        assertEquals("notifications/subscriptions/acknowledged", ack["method"]!!.jsonPrimitive.content)
        val params = ack["params"]!!.jsonObject
        assertEquals(parse("""{"toolsListChanged":true}"""), params["notifications"])
        assertEquals(JsonPrimitive(42), params["_meta"]!!.jsonObject["io.modelcontextprotocol/subscriptionId"])

        sonoraEndpoint.connect(sonora)

        assertEquals(2, received.size)
        val change = received[1]
        assertEquals("notifications/tools/list_changed", change["method"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(42), change["params"]!!.jsonObject["_meta"]!!.jsonObject["io.modelcontextprotocol/subscriptionId"])
        job.cancel()
    }

    @Test
    fun `listen without tool changes is acknowledged with an empty filter and stays silent`() = runTest(UnconfinedTestDispatcher()) {
        connectBridge()
        val received = mutableListOf<JsonObject>()
        val job = launch { session.serve(listen(3, "{}")).collect { received += parse(it) } }

        sonoraEndpoint.connect(sonora)

        assertEquals(1, received.size)
        assertEquals(parse("{}"), received.single()["params"]!!.jsonObject["notifications"])
        job.cancel()
    }

    @Test
    fun `listen is invalid without metadata or a filter and unavailable through handle`() = runTest {
        assertEquals(-32602, parse(session.serve(rpc("subscriptions/listen", parse("""{"notifications":{}}"""))).toList().single()).errorCode)
        assertEquals(-32602, parse(session.serve(rpc("subscriptions/listen", withMeta("{}"))).toList().single()).errorCode)
        assertEquals(-32601, parse(session.handle(listen(5))).errorCode)
    }

    @Test
    fun `serve answers ordinary requests with at most one message`() = runTest {
        assertEquals(1, session.serve(rpc("ping")).toList().size)
        assertEquals(0, session.serve(rpc("notifications/initialized", id = null)).toList().size)
        assertEquals(-32700, parse(session.serve("{").toList().single()).errorCode)
    }
}
