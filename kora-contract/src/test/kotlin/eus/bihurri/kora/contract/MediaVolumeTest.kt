package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.contract.media.SetVolumeRequest
import eus.bihurri.kora.contract.media.VolumeState
import eus.bihurri.kora.contract.media.WriteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaVolumeTest {
    // ---- State ------------------------------------------------------------------------------------------------

    @Test
    fun `a state holds a level within zero and its maximum`() {
        assertEquals(0, VolumeState(0, 1).level)
        assertEquals(15, VolumeState(15, 15, muted = true).level)
        assertFalse(VolumeState(3, 15).muted)
    }

    @Test
    fun `a state rejects a negative level or one above the maximum`() {
        assertThrows(IllegalArgumentException::class.java) { VolumeState(-1, 15) }
        assertThrows(IllegalArgumentException::class.java) { VolumeState(16, 15) }
    }

    @Test
    fun `a state has at least one step`() {
        assertThrows(IllegalArgumentException::class.java) { VolumeState(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { VolumeState(0, -1) }
    }

    @Test
    fun `a state decoded from the wire is validated, and a missing mute reads as not muted`() {
        val spec = MediaVolume.GetVolume
        val error = assertThrows(IllegalArgumentException::class.java) {
            spec.decodeOutput(JsonCodec, """{"level":5,"maxLevel":4}""".encodeToByteArray())
        }
        assertMessage("level must be within 0..4: 5", error)
        assertEquals(VolumeState(4, 15), spec.decodeOutput(JsonCodec, """{"level":4,"maxLevel":15}""".encodeToByteArray()))
    }

    // ---- Request ----------------------------------------------------------------------------------------------

    @Test
    fun `a request leaves mute unchanged and carries no order by default`() {
        val request = SetVolumeRequest(level = 7)
        assertNull(request.muted)
        assertNull(request.order)
        assertEquals(7, request.level)
    }

    @Test
    fun `a request rejects a negative level but accepts any level above zero for the provider to clamp`() {
        assertMessage("level must not be negative: -1", assertThrows(IllegalArgumentException::class.java) { SetVolumeRequest(level = -1) })
        assertEquals(0, SetVolumeRequest(level = 0).level)
        assertEquals(Int.MAX_VALUE, SetVolumeRequest(level = Int.MAX_VALUE).level)
    }

    @Test
    fun `an order names a non-blank writer and a sequence from zero`() {
        assertMessage("writer must not be blank", assertThrows(IllegalArgumentException::class.java) { WriteOrder("", 1) })
        assertMessage("writer must not be blank", assertThrows(IllegalArgumentException::class.java) { WriteOrder("  ", 1) })
        assertMessage(
            "sequence must not be negative: -1",
            assertThrows(IllegalArgumentException::class.java) { WriteOrder("watch-1", -1) },
        )
        assertEquals(WriteOrder("watch-1", 0), SetVolumeRequest(level = 1, order = WriteOrder("watch-1", 0)).order)
    }

    @Test
    fun `a request decoded from the wire is validated too`() {
        val spec = MediaVolume.SetVolume
        fun decode(json: String) = spec.decodeInput(JsonCodec, json.encodeToByteArray())

        assertMessage("level must not be negative: -1", assertThrows(IllegalArgumentException::class.java) { decode("""{"level":-1}""") })
        assertMessage(
            "sequence must not be negative: -5",
            assertThrows(IllegalArgumentException::class.java) { decode("""{"level":1,"order":{"writer":"w","sequence":-5}}""") },
        )
        assertMessage(
            "writer must not be blank",
            assertThrows(IllegalArgumentException::class.java) { decode("""{"level":1,"order":{"writer":"","sequence":1}}""") },
        )
        assertEquals(SetVolumeRequest(level = 3), decode("""{"level":3}"""))
        assertEquals(
            SetVolumeRequest(level = 3, muted = true, order = WriteOrder("watch-1", 9)),
            decode("""{"level":3,"muted":true,"order":{"writer":"watch-1","sequence":9}}"""),
        )
    }

    /** The message proves which `init` check refused the value (a SerializationException is an IllegalArgumentException too). */
    private fun assertMessage(expected: String, error: IllegalArgumentException) {
        assertTrue(error.message.orEmpty().contains(expected), "expected \"$expected\" in: ${error.message}")
    }

    // ---- Specs ------------------------------------------------------------------------------------------------

    @Test
    fun `reads and the event use the read scope, writes the control scope`() {
        assertEquals(Scope("media.volume.read"), MediaVolume.ReadScope)
        assertEquals(Scope("media.volume.control"), MediaVolume.ControlScope)
        assertEquals(MediaVolume.ReadScope, MediaVolume.GetVolume.scope)
        assertEquals(MediaVolume.ControlScope, MediaVolume.SetVolume.scope)
        assertEquals(MediaVolume.ReadScope, MediaVolume.VolumeChanged.scope)
    }
}
