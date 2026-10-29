package eus.bihurri.kora.android.ipc;

/** Receives the events of one subscription, in order. After onError or onComplete nothing more arrives. */
oneway interface IKoraEventSink {
    /** CBOR KoraEvent. */
    void onEvent(in byte[] event);

    /** CBOR KoraError; the subscription is over. */
    void onError(in byte[] error);

    /** The provider ended the stream normally. */
    void onComplete();
}
