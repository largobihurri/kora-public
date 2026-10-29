package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityDescriptor
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Version 1 of a synthetic capability: names are plain strings. */
private object ThingV1 : Capability(CapabilityId("test.thing"), CapabilityVersion(1, 0)) {
    val Read = Scope("test.thing.read")

    val GetName: OperationSpec<NoInput, String> =
        operation("get_name", NoInput.serializer(), String.serializer(), Read, "Name as a string.")

    val Ticked: EventSpec<Int> = event("ticked", Int.serializer(), Read, "Tick counter.")
}

@Serializable
private data class ThingName(val first: String, val last: String)

/** Version 2 breaks the output shape (a new major), so a provider keeps [ThingV1] for the transition. */
private object ThingV2 : Capability(CapabilityId("test.thing"), CapabilityVersion(2, 0)) {
    val Read = Scope("test.thing.read")

    val GetName: OperationSpec<NoInput, ThingName> =
        operation("get_name", NoInput.serializer(), ThingName.serializer(), Read, "Name as first and last.")

    val Ticked: EventSpec<String> = event("ticked", String.serializer(), Read, "Tick label.")
}

/** A later minor of version 1 that only adds an operation. */
private object ThingV1Dot2 : Capability(CapabilityId("test.thing"), CapabilityVersion(1, 2)) {
    val GetName: OperationSpec<NoInput, String> =
        operation("get_name", NoInput.serializer(), String.serializer(), ThingV1.Read, "Name as a string.")

    val Ticked: EventSpec<Int> = event("ticked", Int.serializer(), ThingV1.Read, "Tick counter.")

    val GetAge: OperationSpec<NoInput, Int> =
        operation("get_age", NoInput.serializer(), Int.serializer(), ThingV1.Read, "Age.")
}

/** A major nobody provides. */
private object ThingV3 : Capability(CapabilityId("test.thing"), CapabilityVersion(3, 0)) {
    val GetName: OperationSpec<NoInput, String> =
        operation("get_name", NoInput.serializer(), String.serializer(), ThingV1.Read, "Name.")
}

@OptIn(ExperimentalCoroutinesApi::class)
class TwoMajorsTest {
    private fun KoraNode.provideV1() = provide(ThingV1) {
        on(ThingV1.GetName) { "ada lovelace" }
        emits(ThingV1.Ticked) { flowOf(1, 2) }
    }

    private fun KoraNode.provideV2() = provide(ThingV2) {
        on(ThingV2.GetName) { ThingName("ada", "lovelace") }
        emits(ThingV2.Ticked) { flowOf("one", "two") }
    }

    private fun bothMajors() = KoraNode("provider").apply {
        provideV1()
        provideV2()
    }

    private fun code(response: KoraResponse) = (response as KoraResponse.Failure).error.code

    @Test
    fun `each major is served by its own provider`() = runTest {
        val node = bothMajors()

        assertEquals("ada lovelace", node.call(ThingV1.GetName, NoInput))
        assertEquals(ThingName("ada", "lovelace"), node.call(ThingV2.GetName, NoInput))
        assertEquals(1, node.events(ThingV1.Ticked).first())
        assertEquals("one", node.events(ThingV2.Ticked).first())
    }

    @Test
    fun `the order of registration does not matter`() = runTest {
        val node = KoraNode("provider").apply {
            provideV2()
            provideV1()
        }

        assertEquals("ada lovelace", node.call(ThingV1.GetName, NoInput))
        assertEquals(ThingName("ada", "lovelace"), node.call(ThingV2.GetName, NoInput))
    }

    @Test
    fun `inbound requests are routed by the major they carry`() = runTest {
        val node = bothMajors()
        val v1 = node.handle(SELF, request(ThingV1.GetName, NoInput)).output(ThingV1.GetName)
        val v2 = node.handle(SELF, request(ThingV2.GetName, NoInput)).output(ThingV2.GetName)

        assertEquals("ada lovelace", v1)
        assertEquals(ThingName("ada", "lovelace"), v2)
    }

    @Test
    fun `describe advertises both majors ordered by id and version`() {
        val node = KoraNode("provider")

        node.provideV2()
        node.provideV1()

        assertEquals(
            listOf(
                CapabilityDescriptor("test.thing", CapabilityVersion(1, 0)),
                CapabilityDescriptor("test.thing", CapabilityVersion(2, 0)),
            ),
            node.describe().capabilities,
        )
    }

    @Test
    fun `a major nobody provides is incompatible_version while an unknown id stays unknown_capability`() = runTest {
        val onlyV1 = KoraNode("provider").apply { provideV1() }
        val future = ThingV2.GetName

        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, code(onlyV1.handle(SELF, request(future, NoInput))))
        val refused = failure { onlyV1.call(future, NoInput) }
        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, refused.error.code)
        assertTrue(refused.error.message.contains("1.0"), refused.error.message)

        val unknown = request(ThingV1.GetName, NoInput)
        val other = KoraRequest(unknown.id, "test.other", unknown.version, unknown.operation, unknown.payload)
        assertEquals(ErrorCode.UNKNOWN_CAPABILITY, code(onlyV1.handle(SELF, other)))
    }

    @Test
    fun `a minor above the one provided for that major is refused even when another major exists`() = runTest {
        val node = bothMajors()

        val tooNew = request(ThingV1.GetName, NoInput, version = CapabilityVersion(1, 3))

        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, code(node.handle(SELF, tooNew)))
        assertTrue(!node.isAvailable(ThingV1Dot2))
        assertTrue(node.isAvailable(ThingV1))
        assertTrue(node.isAvailable(ThingV2))
    }

    @Test
    fun `the same major cannot be provided twice but another major can`() {
        val node = KoraNode("provider")
        node.provideV1()

        assertThrows(IllegalStateException::class.java) {
            node.provide(ThingV1Dot2) {
                on(ThingV1Dot2.GetName) { "x" }
                on(ThingV1Dot2.GetAge) { 1 }
                emits(ThingV1Dot2.Ticked) { flowOf(0) }
            }
        }
        node.provideV2()

        assertEquals(2, node.describe().capabilities.size)
    }

    @Test
    fun `availability and providers are answered per major`() = runTest(UnconfinedTestDispatcher()) {
        val node = KoraNode("provider")
        node.provideV1()
        val seenV1 = mutableListOf<Boolean>()
        val seenV2 = mutableListOf<Boolean>()
        val jobV1 = launch { node.availability(ThingV1).collect { seenV1 += it } }
        val jobV2 = launch { node.availability(ThingV2).collect { seenV2 += it } }

        assertEquals(listOf("provider"), node.providers(ThingV1))
        assertEquals(emptyList<String>(), node.providers(ThingV2))
        assertFalse(node.isAvailable(ThingV2))

        node.provideV2()

        assertEquals(listOf(true), seenV1)
        assertEquals(listOf(false, true), seenV2)
        assertEquals(listOf("provider"), node.providers(ThingV2))
        jobV1.cancel()
        jobV2.cancel()
    }

    @Test
    fun `peers pick the major they were built against`() = runTest {
        val network = InMemoryPeerNetwork()
        val providerEndpoint = network.endpoint("provider")
        val oldApp = network.endpoint("old-app")
        val newApp = network.endpoint("new-app")
        providerEndpoint.connect(
            KoraNode("provider", peerDirectory = providerEndpoint, transport = providerEndpoint).apply {
                provideV1()
                provideV2()
            },
        )
        val oldNode = KoraNode("old-app", peerDirectory = oldApp, transport = oldApp)
        val newNode = KoraNode("new-app", peerDirectory = newApp, transport = newApp)
        oldApp.connect(oldNode)
        newApp.connect(newNode)

        assertEquals("ada lovelace", oldNode.call(ThingV1.GetName, NoInput))
        assertEquals(ThingName("ada", "lovelace"), newNode.call(ThingV2.GetName, NoInput))
        assertEquals("one", newNode.events(ThingV2.Ticked).first())
        assertEquals(1, oldNode.events(ThingV1.Ticked).first())
        assertEquals(listOf("provider"), oldNode.providers(ThingV1))
        assertEquals(listOf("provider"), newNode.providers(ThingV2))
    }

    @Test
    fun `a peer that dropped the old major is incompatible for an old caller`() = runTest {
        val network = InMemoryPeerNetwork()
        val providerEndpoint = network.endpoint("provider")
        val oldApp = network.endpoint("old-app")
        providerEndpoint.connect(
            KoraNode("provider", peerDirectory = providerEndpoint, transport = providerEndpoint).apply { provideV2() },
        )
        val oldNode = KoraNode("old-app", peerDirectory = oldApp, transport = oldApp)
        oldApp.connect(oldNode)

        val error = failure { oldNode.call(ThingV1.GetName, NoInput) }

        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, error.error.code)
        assertFalse(oldNode.isAvailable(ThingV1))
        assertTrue(oldNode.isAvailable(ThingV2))
    }

    @Test
    fun `local target reports every local version when none can serve the request`() = runTest {
        val node = bothMajors()

        val error = failure { node.call(ThingV3.GetName, NoInput, Target.Local) }

        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, error.error.code)
        assertTrue(error.error.message.contains("1.0 and 2.0"), error.error.message)
    }

    private suspend fun failure(block: suspend () -> Unit): KoraException =
        try {
            block()
            throw AssertionError("expected a KoraException")
        } catch (e: KoraException) {
            e
        }
}
