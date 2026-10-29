package eus.bihurri.kora.android.ipc;

/** Handle of a subscription opened with IKoraNode.subscribe. */
interface IKoraSubscription {
    /** Stops the subscription. Idempotent; asynchronous so that cleanup never blocks the caller. */
    oneway void cancel();
}
