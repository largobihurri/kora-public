package eus.bihurri.kora.android.ipc;

/** Receives the answer to one call. */
oneway interface IKoraCallback {
    /** CBOR KoraResponse, success or failure. */
    void onResponse(in byte[] response);
}
