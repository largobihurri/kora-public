package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One message of the relay protocol. [version] is [WearProtocol.VERSION]; [epoch] is a random number the sender
 * picks once per process, so the receiver notices that the counterpart app restarted (and its subscriptions are
 * gone) without any keep-alive traffic.
 */
@Serializable
public class Frame(
    public val version: Int,
    public val epoch: Long,
    public val body: FrameBody,
) {
    override fun toString(): String = "Frame(v$version, $body)"
}

/** Only the header of a [Frame], decoded first so an unsupported version is dropped before its body is read. */
@Serializable
internal class FrameHeader(val version: Int = 0)

/**
 * What a frame says. A request names its [target], a node id **as the receiving device knows it** (no
 * [WearProtocol.PEER_PREFIX]); correlation uses the ids the envelopes already carry (`KoraRequest.id`,
 * `SubscribeRequest.subscriptionId`).
 */
@Serializable
public sealed interface FrameBody {
    /** Asks for the peers of the receiving device ("describe-remote-peers"). Answered with [Peers]. */
    @Serializable
    @SerialName("describe")
    public class DescribePeers(public val id: String) : FrameBody

    /** Answer to [DescribePeers]: the receiving node itself first, then its own peers (never relayed ones). */
    @Serializable
    @SerialName("peers")
    public class Peers(public val id: String, public val nodes: List<NodeDescriptor>) : FrameBody

    /** Push after a [DescribePeers]: the list of [Peers] changed. Sent only to a counterpart that asked before. */
    @Serializable
    @SerialName("peers_changed")
    public class PeersChanged(public val nodes: List<NodeDescriptor>) : FrameBody

    /** Calls [request] on [target], a node of the receiving device (itself or one of its peers). */
    @Serializable
    @SerialName("call")
    public class Call(public val target: String, public val request: KoraRequest) : FrameBody

    /** Answer to a [Call]; `response.id` is the request id. */
    @Serializable
    @SerialName("result")
    public class CallResult(public val response: KoraResponse) : FrameBody

    /** Opens the stream described by [request] on [target]. Events come back as [Event]. */
    @Serializable
    @SerialName("subscribe")
    public class Subscribe(public val target: String, public val request: SubscribeRequest) : FrameBody

    /** Ends the subscription [subscriptionId]; no answer. */
    @Serializable
    @SerialName("cancel")
    public class Cancel(public val subscriptionId: String) : FrameBody

    /** One event of a subscription; one frame per event. */
    @Serializable
    @SerialName("event")
    public class Event(public val event: KoraEvent) : FrameBody

    /** The subscription [subscriptionId] ended with [error] (refused, or its source failed). */
    @Serializable
    @SerialName("failed")
    public class Failed(public val subscriptionId: String, public val error: KoraError) : FrameBody

    /** The source of the subscription [subscriptionId] completed. */
    @Serializable
    @SerialName("completed")
    public class Completed(public val subscriptionId: String) : FrameBody
}

/** Result of reading bytes as a [Frame]. */
public sealed interface FrameReading {
    public class Valid(public val frame: Frame) : FrameReading

    /** A well-formed header of a version this library does not speak. */
    public class UnsupportedVersion(public val version: Int) : FrameReading

    /** Not a frame at all (garbage, truncated, over the size limit, or an unknown body type). */
    public class Malformed(public val reason: String) : FrameReading
}

/** CBOR encoding of [Frame]s with the size guard of [WearProtocol.MAX_FRAME_BYTES]. */
public object FrameCodec {
    /** Encodes [body] into a frame of the current version. Throws `payload_too_large` above the limit. */
    public fun encode(epoch: Long, body: FrameBody): ByteArray = encode(Frame(WearProtocol.VERSION, epoch, body))

    public fun encode(frame: Frame): ByteArray {
        val bytes = CborCodec.encode(Frame.serializer(), frame)
        if (bytes.size > WearProtocol.MAX_FRAME_BYTES) {
            throw KoraException(
                KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "Frame of ${bytes.size} bytes exceeds ${WearProtocol.MAX_FRAME_BYTES}"),
            )
        }
        return bytes
    }

    /** Reads [bytes]; never throws. */
    public fun read(bytes: ByteArray): FrameReading {
        if (bytes.size > WearProtocol.MAX_FRAME_BYTES) return FrameReading.Malformed("frame of ${bytes.size} bytes is too large")
        val header = try {
            CborCodec.decode(FrameHeader.serializer(), bytes)
        } catch (_: IllegalArgumentException) {
            return FrameReading.Malformed("unreadable header")
        }
        if (header.version != WearProtocol.VERSION) return FrameReading.UnsupportedVersion(header.version)
        return try {
            FrameReading.Valid(CborCodec.decode(Frame.serializer(), bytes))
        } catch (_: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
            FrameReading.Malformed("unreadable frame")
        }
    }
}
