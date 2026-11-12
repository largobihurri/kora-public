package eus.bihurri.kora.android.ipc;

import android.os.ParcelFileDescriptor;
import eus.bihurri.kora.android.ipc.IKoraCallback;
import eus.bihurri.kora.android.ipc.IKoraEventSink;
import eus.bihurri.kora.android.ipc.IKoraStreamCallback;
import eus.bihurri.kora.android.ipc.IKoraSubscription;

/**
 * Binder interface of a Kora node, served by KoraNodeService. Internal to the library: apps use KoraNode.
 *
 * Every payload is an opaque CBOR envelope, so the interface never changes when a capability evolves.
 * The server identifies the caller from the Binder transaction (uid and signature), never from the payload.
 *
 * Compatibility: [protocolVersion] must stay the first method (it keeps transaction code 1 in every version)
 * so that a client can always read it before using anything else. New methods are only appended, never inserted
 * or reordered, so every method keeps its transaction code.
 *
 * Methods per protocol version (KoraAndroid.PROTOCOL_VERSION):
 * - 1: protocolVersion, describe, call, subscribe (transaction codes 1 to 4).
 * - 2: adds callSending and callReceiving (codes 5 and 6), the byte streams beside a call. A client calls them only
 *   on a peer that reported 2 or more; a peer on version 1 does not know them.
 */
interface IKoraNode {
    /** Version of this interface; see KoraAndroid.PROTOCOL_VERSION. */
    int protocolVersion();

    /** CBOR NodeDescriptor of the node. Cheap; fails with SecurityException for callers that are not trusted. */
    byte[] describe();

    /** Serves one CBOR KoraRequest and answers a CBOR KoraResponse through [callback], exactly once. */
    oneway void call(in byte[] request, IKoraCallback callback);

    /**
     * Opens the stream described by a CBOR SubscribeRequest. Events, a failure (CBOR KoraError) or the end of the
     * stream arrive on [sink]; the returned handle cancels the subscription.
     */
    IKoraSubscription subscribe(in byte[] request, IKoraEventSink sink);

    /**
     * Since version 2. Serves one CBOR KoraRequest of an upload operation, whose bytes arrive on [bytes]: the read
     * end of a pipe, or a file the caller opened read-only, carrying exactly [sizeBytes] bytes. Answers a CBOR
     * KoraResponse through [callback], exactly once. The server owns its copy of [bytes] and closes it on every path;
     * the caller closes its own copy once this transaction is sent.
     */
    oneway void callSending(in byte[] request, in ParcelFileDescriptor bytes, long sizeBytes, IKoraCallback callback);

    /**
     * Since version 2. Serves one CBOR KoraRequest of a download operation and answers through [callback], exactly
     * once: a success with the read end of a pipe that carries the bytes, or a failure with none.
     */
    oneway void callReceiving(in byte[] request, IKoraStreamCallback callback);
}
