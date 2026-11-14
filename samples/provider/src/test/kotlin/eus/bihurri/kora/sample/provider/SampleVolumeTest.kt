package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.contract.media.SetVolumeRequest
import eus.bihurri.kora.contract.media.VolumeState
import eus.bihurri.kora.contract.media.WriteOrder
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The sample's volume: the pure rule ([SampleVolume.applied]), the handlers through a real node, and the grants. */
class SampleVolumeTest {
    private val start = VolumeState(level = 7, maxLevel = 15)

    @Test
    fun `starts at level 7 of 15, unmuted`() {
        assertEquals(VolumeState(level = 7, maxLevel = 15, muted = false), SampleVolume.initial())
    }

    @Test
    fun `a level within range is applied as is`() {
        assertEquals(start.copy(level = 3), SampleVolume.applied(start, SetVolumeRequest(level = 3)))
    }

    @Test
    fun `a level above maxLevel is clamped to maxLevel`() {
        assertEquals(start.copy(level = 15), SampleVolume.applied(start, SetVolumeRequest(level = 99)))
    }

    @Test
    fun `muted null keeps mute, true or false sets it and keeps the level`() {
        val muted = start.copy(muted = true)
        assertEquals(muted.copy(level = 4), SampleVolume.applied(muted, SetVolumeRequest(level = 4)))
        assertEquals(start.copy(muted = true), SampleVolume.applied(start, SetVolumeRequest(level = 7, muted = true)))
        assertEquals(start, SampleVolume.applied(muted, SetVolumeRequest(level = 7, muted = false)))
    }

    @Test
    fun `get, set and observe the volume through a node`() = runBlocking {
        val node = KoraNode("provider").apply { provideSampleVolume() }

        assertEquals(start, node.call(MediaVolume.GetVolume, NoInput))
        assertEquals(start.copy(level = 15), node.call(MediaVolume.SetVolume, SetVolumeRequest(level = 40)))
        assertEquals(start.copy(level = 15), node.call(MediaVolume.GetVolume, NoInput))
        assertEquals(start.copy(level = 15), node.events(MediaVolume.VolumeChanged).first())
    }

    @Test
    fun `an ordered write older than the last applied one leaves the state unchanged`() = runBlocking {
        val node = KoraNode("provider").apply { provideSampleVolume() }

        node.call(MediaVolume.SetVolume, SetVolumeRequest(level = 9, order = WriteOrder("w", sequence = 2)))
        val stale = node.call(MediaVolume.SetVolume, SetVolumeRequest(level = 1, order = WriteOrder("w", sequence = 1)))
        val unordered = node.call(MediaVolume.SetVolume, SetVolumeRequest(level = 2))

        assertEquals(start.copy(level = 9), stale)
        assertEquals(start.copy(level = 2), unordered)
    }

    @Test
    fun `the adb shell and tailnet agents may read and control the volume`() = runBlocking {
        val volume = setOf(MediaVolume.ReadScope, MediaVolume.ControlScope)
        val shell = SampleExternalAccess().grantedScopes(Caller(CallerKind.SHELL, "shell"))

        assertTrue(shell.containsAll(volume), "shell scopes: $shell")
        assertTrue(SampleExternalAccess.TAILNET_AGENT_SCOPES.containsAll(volume))
    }
}
