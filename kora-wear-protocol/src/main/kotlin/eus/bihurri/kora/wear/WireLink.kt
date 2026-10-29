package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Port: a point-to-point channel to the **counterpart**, the same app on the other device of a phone/watch pair.
 * It moves whole frames (opaque bytes of at most [WearProtocol.MAX_FRAME_BYTES]); splitting a frame into the
 * messages a platform can carry is the link's business. On Android the implementation is `kora-wear`'s
 * `MessageLink`; tests use `InMemoryWireLink`.
 *
 * The link authenticates the counterpart (the Wear OS Data Layer only connects apps with the same package name and
 * signing certificate), so nothing in a frame has to prove who sent it.
 */
public interface WireLink {
    /**
     * Sends one frame. Throws [KoraException] `provider_unavailable` when no counterpart is reachable or the
     * platform refuses. Delivery is best effort: a frame can be lost, and there is no retry.
     */
    public suspend fun send(frame: ByteArray)

    /** Whole frames from the counterpart. Hot: a frame arriving while nobody collects is dropped. */
    public val incoming: Flow<ByteArray>

    /**
     * Whether the counterpart is known to be reachable. It starts `false` (not resolved yet: the first [send]
     * resolves it lazily) and only its transitions matter: `true` to `false` means the link was lost, which fails
     * in-flight calls and ends subscriptions.
     */
    public val reachable: StateFlow<Boolean>
}

/** Constants of the relay protocol shared by every implementation of [WireLink]. */
public object WearProtocol {
    /** Version written in every frame. A frame with another version is dropped; only additive changes keep it. */
    public const val VERSION: Int = 1

    /** Largest encoded frame, in either direction: the same limit as a Binder envelope (256 KiB). */
    public const val MAX_FRAME_BYTES: Int = 256 * 1024

    /** Name of the Wear OS capability both apps declare so each finds the other (`android_wear_capabilities`). */
    public const val CAPABILITY: String = "kora_node"

    /** Prefix of every Data Layer message path used by this protocol; the manifest filter matches it. */
    public const val PATH_PREFIX: String = "/kora/v1"

    /** Path of the messages that carry frames. */
    public const val PATH_FRAME: String = "$PATH_PREFIX/frame"

    /**
     * Prefix of the node ids of the counterpart's peers as seen by this node (`wear:sonora`). Peers of the other
     * device get this prefix so they cannot collide with the ids of local peers, and so that this node's own id
     * (which the counterpart app shares) is not mistaken for itself. A node never relays a request for an id that
     * has this prefix, so requests cannot bounce between the two devices.
     */
    public const val PEER_PREFIX: String = "wear:"
}

internal fun unavailable(message: String, cause: Throwable? = null): KoraException =
    KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, message), cause)

/** The `provider_unavailable` failure a [WireLink] implementation throws from `send`. */
public fun linkUnavailable(message: String, cause: Throwable? = null): KoraException = unavailable(message, cause)
