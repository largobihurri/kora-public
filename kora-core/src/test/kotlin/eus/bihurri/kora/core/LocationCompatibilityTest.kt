package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.LocationFixBatch
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.location.StreamRequest
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `location` consumers and providers built at different minors, through the wire (CBOR): a 1.0 consumer against a 1.1
 * provider, and a 1.1 consumer against a 1.0 provider, which serves the members that exist since 1.0 and refuses
 * the ones added in 1.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationCompatibilityTest {
    /** The capability exactly as 1.0 shipped it: what an app built before 1.1 compiled against. */
    private object LocationV10 : Capability(CapabilityId("location"), CapabilityVersion(1, 0)) {
        val GetLastFix: OperationSpec<NoInput, LocationFix?> = operation(
            "get_last_fix", NoInput.serializer(), LocationFix.serializer().nullable, LocationCapability.ReadScope, "last fix",
        )
        val FixChanged: EventSpec<LocationFix> = event("fix_changed", LocationFix.serializer(), LocationCapability.ReadScope, "fix")
    }

    private val v10 = CapabilityVersion(1, 0)
    private val v11 = CapabilityVersion(1, 1)
    private val network = InMemoryPeerNetwork()
    private val phoneEndpoint = network.endpoint("phone")
    private val watchEndpoint = network.endpoint("watch")

    /** What the watch sent to the phone. */
    private val sent = RecordingTransport(watchEndpoint)
    private val fix = LocationFix(43.26, -2.93, horizontalAccuracyMeters = 5.0, timeEpochMs = 99)
    private val single = MutableSharedFlow<LocationFix>()
    private val batches = MutableSharedFlow<LocationFixBatch>()
    private val configured = mutableListOf<Pair<StreamRequest, Caller>>()
    private var lastFixCalls = 0

    private val phone = KoraNode("phone", peerDirectory = phoneEndpoint, transport = phoneEndpoint).apply {
        provide(LocationCapability) {
            on(LocationCapability.GetLastFix) { fix }
            on(LocationCapability.ConfigureStream) {
                configured += it to caller
                StreamConfig(it.desiredIntervalMs, it.maxLatencyMs)
            }
            emits(LocationCapability.FixChanged) { single }
            emits(LocationCapability.FixesBatched) { batches }
        }
    }

    /** A phone whose app was built when `location` was at 1.0. */
    private fun phoneAt10() = KoraNode("phone", peerDirectory = phoneEndpoint, transport = phoneEndpoint).apply {
        provide(LocationV10) {
            on(LocationV10.GetLastFix) {
                lastFixCalls++
                fix
            }
            emits(LocationV10.FixChanged) { single }
        }
    }

    private fun watch() = KoraNode("watch", peerDirectory = watchEndpoint, transport = sent)

    /** Connects [provider] as the phone and returns the watch that sees it. */
    private fun watchOf(provider: KoraNode): KoraNode {
        val watch = watch()
        phoneEndpoint.connect(provider)
        watchEndpoint.connect(watch)
        return watch
    }

    private suspend fun refusal(block: suspend () -> Unit): ErrorCode? = try {
        block()
        null
    } catch (e: KoraException) {
        e.error.code
    }

    private suspend fun gateCall(node: KoraNode, operation: String): JsonObject =
        Json.parseToJsonElement(JsonGate(node).call(SELF, """{"capability":"location","operation":"$operation"}""")).jsonObject

    @Test
    fun `a 1_0 consumer calls get_last_fix on a 1_1 provider`() = runTest {
        val watch = watchOf(phone)

        assertEquals(fix, watch.call(LocationV10.GetLastFix, NoInput))
    }

    @Test
    fun `a 1_0 consumer still receives fix_changed from a 1_1 provider`() = runTest(UnconfinedTestDispatcher()) {
        val watch = watchOf(phone)
        val received = mutableListOf<LocationFix>()
        val job = launch { watch.events(LocationV10.FixChanged).collect { received += it } }

        single.emit(fix)
        job.cancel()

        assertEquals(listOf(fix), received)
    }

    @Test
    fun `a 1_1 consumer calls a 1_0 member of a 1_0 provider`() = runTest {
        val watch = watchOf(phoneAt10())

        val answer = watch.call(LocationCapability.GetLastFix, NoInput)

        assertEquals(fix, answer)
        assertEquals(1, lastFixCalls)
        assertEquals(listOf("get_last_fix" to v10), sent.calls.map { it.operation to it.version })
    }

    @Test
    fun `a 1_1 consumer subscribes to a 1_0 event of a 1_0 provider`() = runTest(UnconfinedTestDispatcher()) {
        val watch = watchOf(phoneAt10())
        val received = mutableListOf<LocationFix>()
        val job = launch { watch.events(LocationCapability.FixChanged).collect { received += it } }

        single.emit(fix)
        job.cancel()

        assertEquals(listOf(fix), received)
        assertEquals(listOf("fix_changed" to v10), sent.subscriptions.map { it.event to it.version })
    }

    @Test
    fun `a 1_1 consumer finds a 1_0 provider for the 1_0 members`() = runTest(UnconfinedTestDispatcher()) {
        val watch = watch()
        watchEndpoint.connect(watch)
        val seen = mutableListOf<Boolean>()
        val job = launch { watch.availability(LocationCapability.FixChanged).collect { seen += it } }

        phoneEndpoint.connect(phoneAt10())
        job.cancel()

        assertEquals(listOf("phone"), watch.providers(LocationCapability.GetLastFix))
        assertEquals(listOf("phone"), watch.providers(LocationCapability.FixChanged))
        assertEquals(true, watch.isAvailable(LocationCapability.GetLastFix))
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `the whole capability and its 1_1 members still need a 1_1 provider to be found`() = runTest {
        val old = watchOf(phoneAt10())

        assertEquals(emptyList<String>(), old.providers(LocationCapability))
        assertEquals(false, old.isAvailable(LocationCapability))
        assertEquals(emptyList<String>(), old.providers(LocationCapability.ConfigureStream))
        assertEquals(false, old.isAvailable(LocationCapability.FixesBatched))

        val current = watchOf(phone)

        assertEquals(listOf("phone"), current.providers(LocationCapability))
        assertEquals(listOf("phone"), current.providers(LocationCapability.ConfigureStream))
        assertEquals(true, current.isAvailable(LocationCapability.FixesBatched))
    }

    @Test
    fun `a 1_1 consumer is refused the 1_1 members by a provider that only has 1_0`() = runTest {
        val watch = watchOf(phoneAt10())

        val call = refusal { watch.call(LocationCapability.ConfigureStream, StreamRequest()) }
        val subscription = refusal { watch.events(LocationCapability.FixesBatched).first() }

        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, call)
        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, subscription)
        assertEquals(emptyList<Any>(), sent.calls + sent.subscriptions)
    }

    @Test
    fun `a 1_1 consumer still calls a 1_1 member at 1_1`() = runTest {
        val watch = watchOf(phone)

        val granted = watch.call(LocationCapability.ConfigureStream, StreamRequest(1_000, 3_000))

        assertEquals(StreamConfig(1_000, 3_000), granted)
        assertEquals(listOf(StreamRequest(1_000, 3_000) to Caller(CallerKind.PEER, "watch")), configured)
        assertEquals(listOf("configure_stream" to v11), sent.calls.map { it.operation to it.version })
    }

    @Test
    fun `a 1_1 consumer still subscribes to a 1_1 event at 1_1`() = runTest(UnconfinedTestDispatcher()) {
        val watch = watchOf(phone)
        val received = mutableListOf<LocationFixBatch>()
        val job = launch { watch.events(LocationCapability.FixesBatched).collect { received += it } }
        val batch = LocationFixBatch(listOf(fix, fix.copy(timeEpochMs = 100)))

        batches.emit(batch)
        job.cancel()

        assertEquals(listOf(batch), received)
        assertEquals(listOf("fixes_batched" to v11), sent.subscriptions.map { it.event to it.version })
    }

    @Test
    fun `the JSON gate calls a 1_0 member of a 1_0 provider when no version is given`() = runTest {
        val answer = gateCall(phoneAt10(), "get_last_fix")

        assertEquals(JsonPrimitive(true), answer["ok"], answer.toString())
        assertEquals(fix, Json.decodeFromJsonElement(LocationFix.serializer(), answer["output"]!!))
        assertEquals(1, lastFixCalls)
    }

    @Test
    fun `the JSON gate still asks for a 1_1 member at 1_1 when no version is given`() = runTest {
        val answer = gateCall(phoneAt10(), "configure_stream")

        assertEquals(JsonPrimitive(false), answer["ok"], answer.toString())
        assertEquals(JsonPrimitive("incompatible_version"), answer["error"]!!.jsonObject["code"])
    }
}
