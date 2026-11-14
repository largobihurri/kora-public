package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.contract.media.SetVolumeRequest
import eus.bihurri.kora.contract.media.VolumeState
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.WriteOrderGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** The sample's in-memory volume: [MAX_LEVEL] steps, starting at [START_LEVEL], unmuted. No device volume changes. */
internal object SampleVolume {
    const val MAX_LEVEL = 15
    const val START_LEVEL = 7

    fun initial(): VolumeState = VolumeState(level = START_LEVEL, maxLevel = MAX_LEVEL)

    /** [request] applied to [current]: the level clamped to `0..maxLevel`, mute unchanged when `muted` is null. */
    fun applied(current: VolumeState, request: SetVolumeRequest): VolumeState =
        current.copy(level = request.level.coerceIn(0, current.maxLevel), muted = request.muted ?: current.muted)
}

/**
 * Provides [MediaVolume] from [state]: `get_volume` answers it, `set_volume` applies [SampleVolume.applied] in
 * last-write-wins order per caller ([WriteOrderGuard]), and `volume_changed` emits it (current state first).
 */
internal fun KoraNode.provideSampleVolume(state: MutableStateFlow<VolumeState> = MutableStateFlow(SampleVolume.initial())) {
    val order = WriteOrderGuard<Caller>()
    provide(MediaVolume) {
        on(MediaVolume.GetVolume) { state.value }
        on(MediaVolume.SetVolume) { request ->
            order.applyInOrder(
                caller,
                request.order,
                apply = { state.updateAndGet { SampleVolume.applied(it, request) } },
                current = { state.value },
            )
        }
        emits(MediaVolume.VolumeChanged) { state }
    }
}
