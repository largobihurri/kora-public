package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.CapabilityDescriptor
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.fixtures.FixtureCase
import eus.bihurri.kora.contract.fixtures.FixtureFormat
import eus.bihurri.kora.contract.fixtures.FixtureStore
import eus.bihurri.kora.contract.fixtures.FuturePayloads
import eus.bihurri.kora.contract.fixtures.WireFixtureVerifier
import eus.bihurri.kora.contract.fixtures.fromHex
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test

/**
 * Golden fixtures of the relay protocol: one frame per body type, stored under the revision [REVISION] of [WearProtocol.VERSION]. Old versions'
 * fixtures stay forever and keep being decoded; see `docs/compatibility.md` for how to record a new version.
 */
class WireFixturesTest {
    private val version = CapabilityVersion(1, 0)
    private val descriptor = NodeDescriptor("sonora", "Sonora", listOf(CapabilityDescriptor("media.library", version)), "1.0.0")
    private val request = KoraRequest("r1", "media.library", version, "list_playlists", byteArrayOf(1, 2, 3))
    private val epoch = 1_700_000_000_123L

    private companion object {
        /**
         * Revision of the stored frames: [WearProtocol.VERSION] and a minor that is raised whenever a frame gains a
         * field or a body type (an additive change keeps the protocol version, but changes the bytes of the samples).
         */
        const val REVISION = "1.0"
    }

    private fun frame(serialName: String, body: FrameBody) =
        FixtureCase("wear", REVISION, "frame.$serialName", Frame.serializer(), Frame(WearProtocol.VERSION, epoch, body))

    private val cases: List<FixtureCase<*>> = listOf(
        frame("describe", FrameBody.DescribePeers("d1")),
        frame("peers", FrameBody.Peers("d1", listOf(descriptor))),
        frame("peers_changed", FrameBody.PeersChanged(listOf(descriptor))),
        frame("call", FrameBody.Call("sonora", request)),
        frame("result", FrameBody.CallResult(KoraResponse.Success("r1", byteArrayOf(9, 8)))),
        frame("subscribe", FrameBody.Subscribe("sonora", SubscribeRequest("s1", "media.playback", version, "now_playing_changed"))),
        frame("cancel", FrameBody.Cancel("s1")),
        frame("event", FrameBody.Event(KoraEvent("s1", "media.playback", "now_playing_changed", byteArrayOf(7)))),
        frame("failed", FrameBody.Failed("s1", KoraError(ErrorCode.UNAUTHORIZED, "Scope is not granted"))),
        frame("completed", FrameBody.Completed("s1")),
    )

    private val verifier = WireFixtureVerifier(cases)

    @Test
    fun `every stored fixture of every protocol version still decodes`() {
        verifier.verifyStoredFixturesDecode()
    }

    @Test
    fun `the current encoding equals the stored fixtures`() {
        verifier.verifyCurrentEncodingMatches()
    }

    @Test
    fun `the revision belongs to the protocol version in force`() {
        assertEquals(WearProtocol.VERSION, REVISION.substringBefore('.').toInt())
    }

    @Test
    fun `there is a sample for every body type`() {
        val bodyTypes = FrameBody.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()

        assertEquals(bodyTypes, cases.map { it.name.removePrefix("frame.") }.toSet())
    }

    @Test
    fun `the frame reader accepts the stored fixtures of the current version`() {
        val store = FixtureStore.forTests()
        assumeFalse(store.recording, "fixtures recorded in this run are not on the test classpath yet")
        val current = store.stored().filter { it.version == REVISION && it.format == FixtureFormat.CBOR }

        assertEquals(cases.size, current.size)
        for (fixture in current) {
            assertInstanceOf(FrameReading.Valid::class.java, FrameCodec.read(fixture.text.fromHex()), fixture.toString())
        }
    }

    @Test
    fun `frames from a future minor with extra fields and unknown enum constants are still read`() {
        FuturePayloads.verifyUnknownFieldsAreIgnored(cases)
        val affected = FuturePayloads.verifyUnknownEnumConstantsReadAsUnknown(cases)

        assertTrue(affected > 0, "no frame has an enum-valued field named status, phase or code")
    }
}
