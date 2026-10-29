package eus.bihurri.kora.android

/**
 * Binder transactions share a buffer of about 1 MB per process, so one oversized envelope can fail with
 * `TransactionTooLargeException` and starve every other transaction in flight. The transport therefore refuses
 * envelopes above a conservative limit with `payload_too_large` instead.
 */
internal object PayloadLimits {
    /** Largest encoded envelope (request, response or event) sent over Binder: 256 KiB. */
    const val MAX_ENVELOPE_BYTES: Int = 256 * 1024
}
