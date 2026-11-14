package eus.bihurri.kora.contract.media

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import kotlinx.serialization.Serializable

/**
 * The volume a provider controls: [level] of [maxLevel] integer steps, and whether it is [muted].
 *
 * [maxLevel] is announced by the provider (like the steps of an Android stream volume) and is at least 1; [level] is
 * within `0..maxLevel`. A muted volume keeps its [level], so unmuting restores it.
 */
@Serializable
public data class VolumeState(
    public val level: Int,
    public val maxLevel: Int,
    public val muted: Boolean = false,
) {
    init {
        require(maxLevel >= 1) { "maxLevel must be at least 1: $maxLevel" }
        require(level in 0..maxLevel) { "level must be within 0..$maxLevel: $level" }
    }
}

/**
 * Orders the writes of one writer session: [writer] names the session, [sequence] orders its writes.
 *
 * [writer] is not blank and is picked fresh by the caller for every session, for example a random UUID at start-up,
 * so a counter or clock that resets with the app or the device starts a new writer instead of looking stale.
 * [sequence] is not negative and increases with every write of that writer. See [MediaVolume] for the rule.
 */
@Serializable
public data class WriteOrder(
    public val writer: String,
    public val sequence: Long,
) {
    init {
        require(writer.isNotBlank()) { "writer must not be blank" }
        require(sequence >= 0) { "sequence must not be negative: $sequence" }
    }
}

/**
 * Request of [MediaVolume.SetVolume]: set the absolute [level], optionally change [muted], optionally ordered by
 * [order].
 *
 * [level] is not negative; a level above the provider's [VolumeState.maxLevel] is clamped, not refused. [muted] null
 * leaves mute unchanged. [order] null applies the write unconditionally; with an [order] the provider drops writes
 * that arrive after a newer one of the same writer (see [MediaVolume] for the last-write-wins rule).
 *
 * [level] carries no `IntegerRange`: the annotation needs a maximum, and the maximum is the provider's, so `init` is
 * the only check.
 */
@Serializable
public data class SetVolumeRequest(
    public val level: Int,
    public val muted: Boolean? = null,
    public val order: WriteOrder? = null,
) {
    init {
        require(level >= 0) { "level must not be negative: $level" }
    }
}

/**
 * Read, set and observe the playback volume of the provider app.
 *
 * Binding rules for every provider:
 * - **Which volume.** The provider chooses what it controls, its own player or the music stream of the device, and
 *   announces the number of steps as [VolumeState.maxLevel].
 * - **Clamping.** [SetVolume] clamps [SetVolumeRequest.level] to `0..maxLevel` and answers the state it applied, so a
 *   caller that coalesces input (a watch crown) can send a level past the end without an error.
 * - **Mute.** [SetVolumeRequest.muted] null leaves mute unchanged; true or false sets it.
 * - **Last write wins per writer session, by [SetVolumeRequest.order].** Kora does not keep the calls of one caller
 *   in order (Binder and the wear relay dispatch them in parallel), so a caller that sends a burst of writes (a watch
 *   crown) orders them with a [WriteOrder]:
 *   - Without an `order` the write is always applied (agents, one-off calls).
 *   - With an `order` the provider keeps the current writer per caller. A new writer replaces it, and its first
 *     write is applied; the replaced writer is retired, and a later write of a retired writer (a delayed write of a
 *     previous session) is not applied. A write of the current writer is applied only if its sequence is greater than
 *     the last applied one. A write that is not applied leaves the state unchanged and is answered with it.
 *   - Callers pick a fresh [WriteOrder.writer] per session (for example a random UUID at start-up) and increase the
 *     sequence with every write, so a counter or clock that resets never makes later writes look stale.
 *   Writers of different callers are independent. `eus.bihurri.kora.core.WriteOrderGuard` implements this rule.
 * - **Observation.** [VolumeChanged] emits the current state first to every new subscriber, then every change,
 *   whatever caused it (this capability, the hardware buttons, another app).
 *
 * Reads and the event need [ReadScope]; writes need [ControlScope]. [GetVolume] is [OperationEffect.READ] and
 * [SetVolume] is [OperationEffect.WRITE].
 *
 * Version history:
 * - 1.0: [GetVolume], [SetVolume], [VolumeChanged].
 */
public object MediaVolume : Capability(CapabilityId("media.volume"), CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("media.volume.read")
    public val ControlScope: Scope = Scope("media.volume.control")

    public val GetVolume: OperationSpec<NoInput, VolumeState> = operation(
        name = "get_volume",
        input = NoInput.serializer(),
        output = VolumeState.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get the current volume: level of maxLevel steps, and whether it is muted.",
    )

    public val SetVolume: OperationSpec<SetVolumeRequest, VolumeState> = operation(
        name = "set_volume",
        input = SetVolumeRequest.serializer(),
        output = VolumeState.serializer(),
        scope = ControlScope,
        effect = OperationEffect.WRITE,
        description = "Set the volume to an absolute level (clamped to 0..maxLevel) and optionally mute or unmute " +
            "(muted omitted leaves it unchanged). order is optional: without it the write is always applied; with " +
            "it, a write of the same writer whose sequence is not greater than the last applied one is ignored, a " +
            "new writer starts over, and a replaced writer's later writes are ignored. Returns the applied state.",
    )

    public val VolumeChanged: EventSpec<VolumeState> = event(
        name = "volume_changed",
        serializer = VolumeState.serializer(),
        scope = ReadScope,
        description = "The volume or mute changed; a new subscriber receives the current state first.",
    )
}
