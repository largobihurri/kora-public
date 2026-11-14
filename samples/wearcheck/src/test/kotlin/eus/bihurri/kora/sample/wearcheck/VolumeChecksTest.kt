package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.media.SetVolumeRequest
import eus.bihurri.kora.contract.media.VolumeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The pure parts of the volume steps: percentiles, the levels written and the pass rules, and the stub's rule. */
class VolumeChecksTest {
    @Test
    fun `percentiles use the nearest rank of the sorted samples`() {
        val samples = (100L downTo 1L).toList()

        assertEquals(50L, VolumeChecks.percentile(samples, 50))
        assertEquals(95L, VolumeChecks.percentile(samples, 95))
        assertEquals(100L, VolumeChecks.percentile(samples, 100))
        assertEquals(7L, VolumeChecks.percentile(listOf(7L), 95))
        assertEquals(20L, VolumeChecks.percentile(listOf(30L, 10L, 20L), 50))
    }

    @Test
    fun `percentiles need samples and a percent in 1 to 100`() {
        assertThrows<IllegalArgumentException> { VolumeChecks.percentile(emptyList<Long>(), 50) }
        assertThrows<IllegalArgumentException> { VolumeChecks.percentile(listOf(1L), 0) }
    }

    @Test
    fun `the latency summary reports p50, p95 and max`() {
        val latency = VolumeChecks.latency(listOf(12L, 40L, 15L, 11L, 300L, 14L, 13L, 16L, 18L, 17L))

        assertEquals(VolumeChecks.Latency(p50 = 15, p95 = 300, max = 300), latency)
        assertEquals("p50=15ms p95=300ms max=300ms", latency.toString())
    }

    @Test
    fun `latency levels stay below maxLevel and the burst ends on maxLevel, used once`() {
        val latency = VolumeChecks.latencyLevels(30, maxLevel = 15)
        val burst = VolumeChecks.burstLevels(20, maxLevel = 15)

        assertEquals(30, latency.size)
        assertTrue(latency.all { it in 0 until 15 })
        assertEquals(20, burst.size)
        assertEquals(15, burst.last())
        assertEquals(1, burst.count { it == 15 })
        assertTrue(burst.all { it in 0..15 })
    }

    @Test
    fun `last write wins when the final level is the one of the greatest sequence`() {
        assertTrue(VolumeChecks.lastWriteWins(VolumeState(level = 15, maxLevel = 15), expectedLevel = 15))
        assertFalse(VolumeChecks.lastWriteWins(VolumeState(level = 3, maxLevel = 15), expectedLevel = 15))
    }

    @Test
    fun `the event target always differs from the current level`() {
        assertEquals(8, VolumeChecks.eventTarget(VolumeState(level = 7, maxLevel = 15)))
        assertEquals(14, VolumeChecks.eventTarget(VolumeState(level = 15, maxLevel = 15)))
        assertEquals(0, VolumeChecks.eventTarget(VolumeState(level = 1, maxLevel = 1)))
    }

    @Test
    fun `a cold call passes when the peer offers volume and the first get answers`() {
        assertTrue(VolumeChecks.coldPassed(peerOffers = true, firstGetError = null))
        assertFalse(VolumeChecks.coldPassed(peerOffers = true, firstGetError = "provider_unavailable: no provider"))
        assertFalse(VolumeChecks.coldPassed(peerOffers = false, firstGetError = null))
    }

    @Test
    fun `the stub volume clamps and keeps mute when muted is null`() {
        val start = WearVolume.initial()

        assertEquals(VolumeState(level = 7, maxLevel = 15), start)
        assertEquals(start.copy(level = 15), WearVolume.applied(start, SetVolumeRequest(level = 20)))
        assertEquals(start.copy(level = 2, muted = true), WearVolume.applied(start.copy(muted = true), SetVolumeRequest(level = 2)))
        assertEquals(start.copy(muted = true), WearVolume.applied(start, SetVolumeRequest(level = 7, muted = true)))
    }
}
