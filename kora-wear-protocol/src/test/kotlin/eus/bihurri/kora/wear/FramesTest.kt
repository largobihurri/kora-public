package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.CapabilityDescriptor
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FramesTest {
    private val version = CapabilityVersion(1, 0)
    private val descriptor = NodeDescriptor("sonora", "Sonora", listOf(CapabilityDescriptor("media.library", version)))
    private val request = KoraRequest("r1", "media.library", version, "list_playlists", byteArrayOf(1, 2, 3))
    private val subscribe = SubscribeRequest("s1", "media.playback", version, "now_playing_changed")

    private fun roundTrip(body: FrameBody): Frame {
        val bytes = FrameCodec.encode(epoch = 42, body)
        val reading = assertInstanceOf(FrameReading.Valid::class.java, FrameCodec.read(bytes))
        assertEquals(WearProtocol.VERSION, reading.frame.version)
        assertEquals(42L, reading.frame.epoch)
        return reading.frame
    }

    @Test
    fun `every frame body survives a round trip`() {
        val describe = roundTrip(FrameBody.DescribePeers("d1")).body as FrameBody.DescribePeers
        assertEquals("d1", describe.id)

        val peers = roundTrip(FrameBody.Peers("d1", listOf(descriptor))).body as FrameBody.Peers
        assertEquals(listOf(descriptor), peers.nodes)

        val changed = roundTrip(FrameBody.PeersChanged(listOf(descriptor))).body as FrameBody.PeersChanged
        assertEquals(listOf(descriptor), changed.nodes)

        val call = roundTrip(FrameBody.Call("sonora", request)).body as FrameBody.Call
        assertEquals("sonora", call.target)
        assertEquals(request, call.request)

        val success = roundTrip(FrameBody.CallResult(KoraResponse.Success("r1", byteArrayOf(9)))).body as FrameBody.CallResult
        assertEquals(KoraResponse.Success("r1", byteArrayOf(9)), success.response)
        val failure = roundTrip(FrameBody.CallResult(KoraResponse.Failure("r1", KoraError(ErrorCode.TIMEOUT, "slow")))).body as FrameBody.CallResult
        assertEquals(KoraResponse.Failure("r1", KoraError(ErrorCode.TIMEOUT, "slow")), failure.response)

        val sub = roundTrip(FrameBody.Subscribe("sonora", subscribe)).body as FrameBody.Subscribe
        assertEquals(subscribe, sub.request)

        assertEquals("s1", (roundTrip(FrameBody.Cancel("s1")).body as FrameBody.Cancel).subscriptionId)

        val event = KoraEvent("s1", "media.playback", "now_playing_changed", byteArrayOf(7))
        assertEquals(event, (roundTrip(FrameBody.Event(event)).body as FrameBody.Event).event)

        val failed = roundTrip(FrameBody.Failed("s1", KoraError(ErrorCode.UNAUTHORIZED))).body as FrameBody.Failed
        assertEquals(KoraError(ErrorCode.UNAUTHORIZED), failed.error)

        assertEquals("s1", (roundTrip(FrameBody.Completed("s1")).body as FrameBody.Completed).subscriptionId)
    }

    @Test
    fun `a frame of another version is reported before its body is read`() {
        val future = CborCodec.encode(Frame.serializer(), Frame(WearProtocol.VERSION + 1, 1, FrameBody.Cancel("s")))

        val reading = assertInstanceOf(FrameReading.UnsupportedVersion::class.java, FrameCodec.read(future))

        assertEquals(WearProtocol.VERSION + 1, reading.version)
    }

    @Test
    fun `garbage and truncated frames are malformed, not exceptions`() {
        assertInstanceOf(FrameReading.Malformed::class.java, FrameCodec.read(byteArrayOf(1, 2, 3, 4)))
        assertInstanceOf(FrameReading.Malformed::class.java, FrameCodec.read(ByteArray(0)))
        val valid = FrameCodec.encode(1, FrameBody.Call("sonora", request))
        assertInstanceOf(FrameReading.Malformed::class.java, FrameCodec.read(valid.copyOf(valid.size - 3)))
    }

    @Test
    fun `a frame over the size limit is refused on both ends`() {
        val huge = KoraRequest("r", "media.library", version, "op", ByteArray(WearProtocol.MAX_FRAME_BYTES))

        val failure = assertThrows(KoraException::class.java) { FrameCodec.encode(1, FrameBody.Call("sonora", huge)) }

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.error.code)
        assertInstanceOf(FrameReading.Malformed::class.java, FrameCodec.read(ByteArray(WearProtocol.MAX_FRAME_BYTES + 1)))
    }

    @Test
    fun `unknown fields are ignored so minor versions can add them`() {
        // A newer peer adds a field to the header and to a body; this reader skips both.
        @kotlinx.serialization.Serializable
        class FutureHeader(val version: Int, val epoch: Long, val extra: String, val body: FrameBody)
        val bytes = CborCodec.encode(FutureHeader.serializer(), FutureHeader(WearProtocol.VERSION, 5, "later", FrameBody.Cancel("s9")))

        val reading = assertInstanceOf(FrameReading.Valid::class.java, FrameCodec.read(bytes))

        assertEquals("s9", (reading.frame.body as FrameBody.Cancel).subscriptionId)
        assertArrayEquals(bytes, bytes)
    }
}
