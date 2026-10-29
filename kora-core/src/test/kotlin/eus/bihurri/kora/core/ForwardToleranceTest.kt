package eus.bihurri.kora.core

import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.media.MediaPlayback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A peer built with a **newer Kora** reaches this node: its descriptor carries fields, capabilities and versions this
 * build has never heard of, and its payloads carry extra fields. Routing and typed calls must work as if nothing
 * were unusual. (Field-level tolerance of every contract type is covered by `:kora-contract` ForwardToleranceTest.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardToleranceTest {
    @Serializable
    private data class FutureVersion(val major: Int, val minor: Int, val patch: Int = 3)

    @Serializable
    private data class FutureCapability(val id: String, val version: FutureVersion, val operations: List<String>, val experimental: Boolean = false)

    @Serializable
    private data class FutureDescriptor(
        val nodeId: String,
        val displayName: String,
        val capabilities: List<FutureCapability>,
        val koraVersion: String,
        val transports: List<String>,
        val batteryPercent: Int,
    )

    @Serializable
    private data class FutureFix(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
        val horizontalAccuracyMeters: Double?,
        val timeEpochMs: Long,
        val speedMetersPerSecond: Double,
        val provider: String,
    )

    private val futureFix = FutureFix(43.26, -2.93, null, 4.5, 99, 1.4, "fused")

    private val futureDescriptor = CborCodec.decode(
        NodeDescriptor.serializer(),
        CborCodec.encode(
            FutureDescriptor.serializer(),
            FutureDescriptor(
                nodeId = "watch",
                displayName = "Watch",
                capabilities = listOf(
                    FutureCapability("location", FutureVersion(1, 9), listOf("get_last_fix", "fix_changed", "get_altitude_profile")),
                    FutureCapability("haptics.pattern", FutureVersion(4, 2), listOf("vibrate"), experimental = true),
                    FutureCapability("media.playback", FutureVersion(2, 0), listOf("play")),
                ),
                koraVersion = "7.4.0-beta",
                transports = listOf("binder", "ble"),
                batteryPercent = 80,
            ),
        ),
    )

    /** Answers like a future provider: payloads have more fields than this version knows. */
    private class FuturePeer(private val fix: ByteArray, descriptor: NodeDescriptor) : PeerDirectory, PeerTransport {
        override val peers: StateFlow<List<PeerInfo>> = MutableStateFlow(listOf(PeerInfo(descriptor)))
        val requests = mutableListOf<KoraRequest>()

        override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
            requests += request
            return KoraResponse.Success(request.id, fix)
        }

        override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> =
            flowOf(KoraEvent(request.subscriptionId, request.capability, request.event, fix))
    }

    private fun node(peer: FuturePeer) = KoraNode("phone", peerDirectory = peer, transport = peer)

    @Test
    fun `a descriptor with unknown fields and unknown capabilities decodes`() {
        assertEquals("watch", futureDescriptor.nodeId)
        assertEquals("7.4.0-beta", futureDescriptor.koraVersion)
        assertEquals(listOf("location", "haptics.pattern", "media.playback"), futureDescriptor.capabilities.map { it.id })
        assertEquals(CapabilityVersion(1, 9), futureDescriptor.capabilities.first().version)
    }

    @Test
    fun `a newer minor of a known capability serves an older caller, with unknown payload fields ignored`() = runTest {
        val peer = FuturePeer(CborCodec.encode(FutureFix.serializer(), futureFix), futureDescriptor)
        val phone = node(peer)

        val fix = phone.call(LocationCapability.GetLastFix, NoInput)

        assertEquals(LocationFix(43.26, -2.93, null, 4.5, 99), fix)
        assertEquals(CapabilityVersion(1, 1), peer.requests.single().version) // the caller still asks for what it knows
        assertEquals(listOf("watch"), phone.providers(LocationCapability))
    }

    @Test
    fun `events of a newer minor are decoded by an older subscriber`() = runTest {
        val peer = FuturePeer(CborCodec.encode(FutureFix.serializer(), futureFix), futureDescriptor)

        val fix = node(peer).events(LocationCapability.FixChanged).first()

        assertEquals(LocationFix(43.26, -2.93, null, 4.5, 99), fix)
    }

    @Test
    fun `capabilities this version does not know are ignored, and a newer major of a known one is not a match`() {
        val phone = node(FuturePeer(ByteArray(0), futureDescriptor))

        assertTrue(phone.isAvailable(LocationCapability))
        assertFalse(phone.isAvailable(MediaPlayback)) // the peer only has media.playback 2.0
        assertFalse(phone.isAvailable(FitnessSession)) // not advertised at all
    }
}
