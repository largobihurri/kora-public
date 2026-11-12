package eus.bihurri.kora.android.ipc;

import android.os.ParcelFileDescriptor;

/** Receives the answer to one IKoraNode.callReceiving (since protocol version 2). */
oneway interface IKoraStreamCallback {
    /**
     * CBOR KoraResponse, success or failure. A success comes with [bytes], the read end of a pipe that carries
     * exactly [sizeBytes] bytes, which the receiver owns and closes; a failure comes with a null [bytes] and a
     * [sizeBytes] of 0.
     */
    void onResponse(in byte[] response, in ParcelFileDescriptor bytes, long sizeBytes);
}
