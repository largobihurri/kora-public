package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.media.VolumeState

/** Pure parts of the volume steps (see [VolumeCheckSteps]): what is written, percentiles and pass rules. */
internal object VolumeChecks {
    /** Sequential `set_volume` round trips of `volume_latency`. */
    const val LATENCY_WRITES = 30

    /** Concurrent `set_volume` writes of `volume_burst`. */
    const val BURST_WRITES = 20

    /** Round-trip summary in milliseconds. */
    data class Latency(val p50: Long, val p95: Long, val max: Long) {
        override fun toString(): String = "p50=${p50}ms p95=${p95}ms max=${max}ms"
    }

    /** Nearest-rank [percent] (1..100) of [samples]: the smallest sample with at least [percent]% of them at or below. */
    fun percentile(samples: List<Long>, percent: Int): Long {
        require(samples.isNotEmpty()) { "no samples" }
        require(percent in 1..100) { "percent must be within 1..100: $percent" }
        val rank = (percent * samples.size + 99) / 100
        return samples.sorted()[rank - 1]
    }

    fun latency(samples: List<Long>): Latency =
        Latency(p50 = percentile(samples, 50), p95 = percentile(samples, 95), max = samples.max())

    /** Levels written by `volume_latency`: they cycle below [maxLevel], so the burst's final level differs from them. */
    fun latencyLevels(count: Int, maxLevel: Int): List<Int> = List(count) { it % maxLevel }

    /**
     * Levels written by `volume_burst`, in sequence order: the last (greatest sequence) is [maxLevel] and no other write
     * uses it, so a final level of [maxLevel] can only come from that write.
     */
    fun burstLevels(count: Int, maxLevel: Int): List<Int> = List(count) { if (it == count - 1) maxLevel else it % maxLevel }

    /** Whether the final state is the one of the write with the greatest sequence. */
    fun lastWriteWins(final: VolumeState, expectedLevel: Int): Boolean = final.level == expectedLevel

    /** A level different from [current]'s, so `set_volume` changes the state and `volume_changed` fires. */
    fun eventTarget(current: VolumeState): Int = if (current.level < current.maxLevel) current.level + 1 else current.level - 1

    /** `volume_cold` passes when the peer offered `media.volume` in time and the first `get_volume` answered. */
    fun coldPassed(peerOffers: Boolean, firstGetError: String?): Boolean = peerOffers && firstGetError == null
}
