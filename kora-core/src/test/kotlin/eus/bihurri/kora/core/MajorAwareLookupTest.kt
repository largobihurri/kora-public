package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityRegistry
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `test.major` 1.0: the name is a plain string, read with its own scope. */
private object MajorV1 : Capability(CapabilityId("test.major"), CapabilityVersion(1, 0)) {
    val Read = Scope("test.major.read")

    val GetName: OperationSpec<NoInput, String> =
        operation("get_name", NoInput.serializer(), String.serializer(), Read, "Name as a string.")

    val Ticked: EventSpec<Int> = event("ticked", Int.serializer(), Read, "Tick counter.")
}

@Serializable
private data class MajorName(val first: String, val last: String)

/** `test.major` 2.0 breaks the output shape and asks for another scope. */
private object MajorV2 : Capability(CapabilityId("test.major"), CapabilityVersion(2, 0)) {
    val Read = Scope("test.major.read2")

    val GetName: OperationSpec<NoInput, MajorName> =
        operation("get_name", NoInput.serializer(), MajorName.serializer(), Read, "Name as first and last.")

    val Ticked: EventSpec<String> = event("ticked", String.serializer(), Read, "Tick label.")
}

/** A fake 2.0 of a built-in id that the built-in registry does not know. */
private object LibraryV2 : Capability(MediaLibrary.id, CapabilityVersion(2, 0)) {
    val ListPlaylists: OperationSpec<NoInput, String> =
        operation("list_playlists", NoInput.serializer(), String.serializer(), Scope("media.library.read2"), "Fake 2.0.")
}

/** Scope, event and JSON lookups use the major the request carries, not the first definition of the id. */
class MajorAwareLookupTest {
    private val registry = CapabilityRegistry(listOf(MajorV1, MajorV2))
    private val access = FakeExternalAccess(enabled = true)

    private val network = InMemoryPeerNetwork()
    private val providerEndpoint = network.endpoint("provider")
    private val bridgeEndpoint = network.endpoint("bridge")
    private val peerCalls = mutableListOf<String>()

    private val provider = KoraNode("provider", peerDirectory = providerEndpoint, transport = providerEndpoint).apply {
        provide(MajorV1) {
            on(MajorV1.GetName) { peerCalls += "1"; "ada lovelace" }
            emits(MajorV1.Ticked) { flowOf(1) }
        }
        provide(MajorV2) {
            on(MajorV2.GetName) { peerCalls += "2"; MajorName("ada", "lovelace") }
            emits(MajorV2.Ticked) { flowOf("one") }
        }
    }

    private fun bridge(registry: CapabilityRegistry = this.registry) = KoraNode(
        "bridge",
        policy = DefaultCallerPolicy(access),
        peerDirectory = bridgeEndpoint,
        transport = bridgeEndpoint,
        registry = registry,
    ).also {
        providerEndpoint.connect(provider)
        bridgeEndpoint.connect(it)
    }

    @Test
    fun `the registry finds each major and the newest by default`() {
        assertSame(MajorV1, registry.find(MajorV1.id, 1))
        assertSame(MajorV2, registry.find("test.major", 2))
        assertSame(MajorV2, registry.find(MajorV1.id))
        assertSame(MajorV2, CapabilityRegistry(listOf(MajorV2, MajorV1)).find("test.major"))
        assertEquals(null, registry.find(MajorV1.id, 3))
        assertEquals(null, registry.find("test.other", 1))
        assertEquals(null, registry.find("Not A Valid Id", 1))
    }

    @Test
    fun `dispatch checks the scope of the major the request carries`() = runTest {
        val bridge = bridge()
        access.grants[AGENT] = setOf(MajorV1.Read)

        assertEquals("ada lovelace", bridge.dispatch(AGENT, request(MajorV1.GetName, NoInput)).output(MajorV1.GetName))
        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(AGENT, request(MajorV2.GetName, NoInput)))
        assertEquals(listOf("1"), peerCalls)

        access.grants[AGENT] = setOf(MajorV2.Read)

        assertEquals(MajorName("ada", "lovelace"), bridge.dispatch(AGENT, request(MajorV2.GetName, NoInput)).output(MajorV2.GetName))
        assertFailure(ErrorCode.UNAUTHORIZED, bridge.dispatch(AGENT, request(MajorV1.GetName, NoInput)))
        assertEquals(listOf("1", "2"), peerCalls)
        assertEquals(listOf(AGENT to MajorV2.Read, AGENT to MajorV1.Read), access.denied)
    }

    @Test
    fun `dispatchSubscribe checks the scope of the major the request carries`() = runTest {
        val bridge = bridge()
        access.grants[AGENT] = setOf(MajorV2.Read)

        val failure = runCatching { bridge.dispatchSubscribe(AGENT, subscription(MajorV1.Ticked)).collect {} }.exceptionOrNull()
        assertEquals(ErrorCode.UNAUTHORIZED, (failure as KoraException).error.code)

        bridge.dispatchSubscribe(AGENT, subscription(MajorV2.Ticked)).collect {} // granted: completes without error
        assertEquals(listOf(AGENT to MajorV1.Read), access.denied)
    }

    @Test
    fun `a major the registry lacks is never authorized with the scope of another major`() = runTest {
        // The peer offers media.library 2.0, which the built-in registry (major 1 only) cannot describe.
        providerEndpoint.connect(
            KoraNode("provider", peerDirectory = providerEndpoint, transport = providerEndpoint).apply {
                provide(LibraryV2) { on(LibraryV2.ListPlaylists) { peerCalls += "lib2"; "x" } }
            },
        )
        val bridge = KoraNode(
            "bridge",
            policy = DefaultCallerPolicy(access),
            peerDirectory = bridgeEndpoint,
            transport = bridgeEndpoint,
        ).also { bridgeEndpoint.connect(it) }
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        val response = bridge.dispatch(AGENT, request(LibraryV2.ListPlaylists, NoInput))

        assertFailure(ErrorCode.UNKNOWN_OPERATION, response)
        assertTrue(peerCalls.isEmpty())
        assertTrue(access.denied.isEmpty())
    }

    // ---- JsonGate ------------------------------------------------------------------------------------------------

    private fun local() = KoraNode("local", policy = DefaultCallerPolicy(access), registry = registry).apply {
        provide(MajorV1) {
            on(MajorV1.GetName) { "ada lovelace" }
            emits(MajorV1.Ticked) { flowOf(1) }
        }
        provide(MajorV2) {
            on(MajorV2.GetName) { MajorName("ada", "lovelace") }
            emits(MajorV2.Ticked) { flowOf("one") }
        }
    }

    private suspend fun JsonGate.invoke(argument: String): JsonObject =
        Json.parseToJsonElement(call(SELF, argument)).jsonObject

    @Test
    fun `the json gate uses the definition of the version it is given`() = runTest {
        val gate = JsonGate(local(), registry = registry)

        val v1 = gate.invoke("""{"capability":"test.major","version":"1.0","operation":"get_name"}""")
        val v2 = gate.invoke("""{"capability":"test.major","version":"2.0","operation":"get_name"}""")

        assertEquals("ada lovelace", v1["output"]!!.jsonPrimitive.content)
        assertEquals("ada", v2["output"]!!.jsonObject["first"]!!.jsonPrimitive.content)
        assertEquals("lovelace", v2["output"]!!.jsonObject["last"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the json gate defaults to the newest version and refuses a major nobody has`() = runTest {
        val gate = JsonGate(local(), registry = registry)

        val default = gate.invoke("""{"capability":"test.major","operation":"get_name"}""")
        val missing = gate.invoke("""{"capability":"test.major","version":"3.0","operation":"get_name"}""")

        assertEquals("ada", default["output"]!!.jsonObject["first"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), missing["ok"])
        assertEquals("incompatible_version", missing["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the json gate forwards a request to the peer under the scope of its major`() = runTest {
        val bridge = bridge()
        access.grants[AGENT] = setOf(MajorV1.Read)
        val gate = JsonGate(bridge, forwardToPeers = true, registry = registry)

        val v1 = Json.parseToJsonElement(gate.call(AGENT, """{"capability":"test.major","version":"1.0","operation":"get_name"}""")).jsonObject
        val v2 = Json.parseToJsonElement(gate.call(AGENT, """{"capability":"test.major","version":"2.0","operation":"get_name"}""")).jsonObject

        assertEquals("ada lovelace", v1["output"]!!.jsonPrimitive.content)
        assertEquals("unauthorized", v2["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
}
