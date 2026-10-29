package eus.bihurri.kora.contract.location

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

@Serializable
public data class LocationFix(
    public val latitude: Double,
    public val longitude: Double,
    public val altitudeMeters: Double? = null,
    public val horizontalAccuracyMeters: Double? = null,
    public val timeEpochMs: Long,
)

/**
 * Payload of [LocationCapability.FixesBatched]: fixes in the order the receiver should record them.
 *
 * An empty batch is never sent, so one is rejected on construction and on decode.
 */
@Serializable
public data class LocationFixBatch(public val fixes: List<LocationFix>) {
    init {
        require(fixes.isNotEmpty()) { "A batch holds at least one fix" }
    }
}

/**
 * What a subscriber asks for through [LocationCapability.ConfigureStream]. Both values are requests: the provider
 * clamps them to what it can do and answers with the values it will use ([StreamConfig]).
 */
@Serializable
public data class StreamRequest(
    /** Wanted spacing between fixes, in milliseconds. The provider never sends fixes closer together than it grants. */
    public val desiredIntervalMs: Long = LocationCapability.DefaultIntervalMs,
    /**
     * Longest a fix may wait at the provider before it is sent, in milliseconds. This bounds how stale the newest
     * fix of a batch can be, and so how long a receiver must wait before it can call the stream silent.
     */
    public val maxLatencyMs: Long = LocationCapability.DefaultMaxLatencyMs,
) {
    init {
        require(desiredIntervalMs > 0) { "desiredIntervalMs must be positive: $desiredIntervalMs" }
        require(maxLatencyMs > 0) { "maxLatencyMs must be positive: $maxLatencyMs" }
    }
}

/** The stream parameters a provider granted, which a subscriber reads to size its own timeouts. */
@Serializable
public data class StreamConfig(public val intervalMs: Long, public val maxLatencyMs: Long)

/**
 * Position fixes as seen by the provider app, so peers need not request location permission themselves.
 *
 * Version history:
 * - 1.0: [GetLastFix] and [FixChanged] (one message per fix).
 * - 1.1: [ConfigureStream] and [FixesBatched] (additive). A provider of 1.1 provides every member, as the atomic
 *   capability rule requires, so a peer that sees `location@1.1` can rely on all four. A 1.0 consumer keeps using the
 *   1.0 members of a 1.1 provider unchanged; a 1.1 consumer needs a 1.1 provider.
 *
 * ## Batching
 * [FixChanged] sends one message per fix, which is too chatty over a radio link (a phone feeding a watch). A consumer
 * that wants a continuous track calls [ConfigureStream] and subscribes to [FixesBatched] instead:
 * - Batches are ordered by `timeEpochMs` and never repeat a fix, within a batch or across batches of one subscription.
 * - The provider chooses the batch period; it is never longer than the granted `maxLatencyMs`, so a fix reaches the
 *   subscriber no later than that after the provider obtained it. Fixes are spaced by at least the granted `intervalMs`.
 * - An empty batch is never sent; a silent stream means no fixes (GPS has no fix, or the link is down).
 * - The provider turns its GPS on only while a subscriber exists (of either event) and off when the last one leaves.
 *
 * [ConfigureStream] is scoped per caller: the values apply to the `fixes_batched` subscriptions that caller opens
 * afterwards (live ones keep theirs), and the last call wins. A caller that never calls it gets the defaults.
 */
public object LocationCapability : Capability(CapabilityId("location"), CapabilityVersion(1, 1)) {
    public val ReadScope: Scope = Scope("location.read")

    /** Spacing between fixes a [StreamRequest] gets by default. */
    public const val DefaultIntervalMs: Long = 2_000

    /** Latency bound a [StreamRequest] gets by default. */
    public const val DefaultMaxLatencyMs: Long = 5_000

    public val GetLastFix: OperationSpec<NoInput, LocationFix?> = operation(
        name = "get_last_fix",
        input = NoInput.serializer(),
        output = LocationFix.serializer().nullable,
        scope = ReadScope,
        description = "Get the most recent position fix, or null when none is available.",
    )

    public val FixChanged: EventSpec<LocationFix> = event(
        name = "fix_changed",
        serializer = LocationFix.serializer(),
        scope = ReadScope,
        description = "A new position fix is available.",
    )

    public val ConfigureStream: OperationSpec<StreamRequest, StreamConfig> = operation(
        name = "configure_stream",
        input = StreamRequest.serializer(),
        output = StreamConfig.serializer(),
        scope = ReadScope,
        description = "Set how often fixes are produced and how long they may wait before delivery for the " +
            "fixes_batched subscriptions this caller opens next. Answers the values the provider granted.",
    )

    public val FixesBatched: EventSpec<LocationFixBatch> = event(
        name = "fixes_batched",
        serializer = LocationFixBatch.serializer(),
        scope = ReadScope,
        description = "Several position fixes at once, ordered and without repeats; the provider sends a batch at " +
            "least every granted max latency while fixes arrive.",
    )
}
