package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.LocationFixBatch
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.location.StreamRequest
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.nullable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** `location` 1.0 consumers against a 1.1 provider, and the reverse, through the wire (CBOR). */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationCompatibilityTest {
    /** The capability exactly as 1.0 shipped it: what an app built before 1.1 compiled against. */
    private object LocationV10 : Capability(CapabilityId("location"), CapabilityVersion(1, 0)) {
        val GetLastFix: OperationSpec<NoInput, LocationFix?> = operation(
            "get_last_fix", NoInput.serializer(), LocationFix.serializer().nullable, LocationCapability.ReadScope, "last fix",
        )
        val FixChanged: EventSpec<LocationFix> = event("fix_changed", LocationFix.serializer(), LocationCapability.ReadScope, "fix")
    }

    private val network = InMemoryPeerNetwork()
    private val phoneEndpoint = network.endpoint("phone")
    private val watchEndpoint = network.endpoint("watch")
    private val fix = LocationFix(43.26, -2.93, horizontalAccuracyMeters = 5.0, timeEpochMs = 99)
    private val single = MutableSharedFlow<LocationFix>()
    private val batches = MutableSharedFlow<LocationFixBatch>()
    private val configured = mutableListOf<Pair<StreamRequest, Caller>>()

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

    private fun watch() = KoraNode("watch", peerDirectory = watchEndpoint, transport = watchEndpoint)

    @Test
    fun `a 1_0 consumer calls get_last_fix on a 1_1 provider`() = runTest {
        val watch = watch()
        phoneEndpoint.connect(phone)
        watchEndpoint.connect(watch)

        assertEquals(fix, watch.call(LocationV10.GetLastFix, NoInput))
    }

    @Test
    fun `a 1_0 consumer still receives fix_changed from a 1_1 provider`() = runTest(UnconfinedTestDispatcher()) {
        val watch = watch()
        phoneEndpoint.connect(phone)
        watchEndpoint.connect(watch)
        val received = mutableListOf<LocationFix>()
        val job = launch { watch.events(LocationV10.FixChanged).collect { received += it } }

        single.emit(fix)
        job.cancel()

        assertEquals(listOf(fix), received)
    }

    @Test
    fun `a 1_1 consumer configures the stream and receives batches`() = runTest(UnconfinedTestDispatcher()) {
        val watch = watch()
        phoneEndpoint.connect(phone)
        watchEndpoint.connect(watch)

        val granted = watch.call(LocationCapability.ConfigureStream, StreamRequest(1_000, 3_000))
        val received = mutableListOf<LocationFixBatch>()
        val job = launch { watch.events(LocationCapability.FixesBatched).collect { received += it } }
        val batch = LocationFixBatch(listOf(fix, fix.copy(timeEpochMs = 100)))
        batches.emit(batch)
        job.cancel()

        assertEquals(StreamConfig(1_000, 3_000), granted)
        assertEquals(listOf(StreamRequest(1_000, 3_000) to Caller(CallerKind.PEER, "watch")), configured)
        assertEquals(listOf(batch), received)
    }

    @Test
    fun `a 1_1 consumer is refused by a provider that only has 1_0`() = runTest {
        val oldPhone = KoraNode("phone", peerDirectory = phoneEndpoint, transport = phoneEndpoint).apply {
            provideStubbed(LocationV10) { on(LocationV10.GetLastFix) { fix } }
        }
        val watch = watch()
        phoneEndpoint.connect(oldPhone)
        watchEndpoint.connect(watch)

        val e = assertThrows(KoraException::class.java) {
            kotlinx.coroutines.runBlocking { watch.call(LocationCapability.ConfigureStream, StreamRequest()) }
        }
        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, e.error.code)
        assertEquals(fix, watch.call(LocationV10.GetLastFix, NoInput))
    }
}
