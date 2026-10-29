package eus.bihurri.kora.android.ipc;

import eus.bihurri.kora.android.ipc.IKoraCallback;
import eus.bihurri.kora.android.ipc.IKoraEventSink;
import eus.bihurri.kora.android.ipc.IKoraSubscription;

/**
 * Binder interface of a Kora node, served by KoraNodeService. Internal to the library: apps use KoraNode.
 *
 * Every payload is an opaque CBOR envelope, so the interface never changes when a capability evolves.
 * The server identifies the caller from the Binder transaction (uid and signature), never from the payload.
 *
 * Compatibility: [protocolVersion] must stay the first method (it keeps transaction code 1 in every version)
 * so that a client can always read it before using anything else. New methods are only appended.
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
}
