package eus.bihurri.kora.android

import java.security.MessageDigest

/**
 * Digests of signing certificates, in the form carried by `Caller.signingCertificateSha256`: 64 lowercase hex
 * characters. Pure, so it is unit tested without Android.
 */
internal object CertificateDigest {
    /** SHA-256 of a DER-encoded certificate; the same value `apksigner verify --print-certs` shows. */
    fun sha256Hex(certificate: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(certificate))

    /**
     * The digest of an app signed by [fingerprints] (one [sha256Hex] per signer): the fingerprint itself for a
     * single signer; for several, the SHA-256 of the sorted, comma-separated fingerprints, which does not depend on
     * the order the system lists the signers in. Null when there are none.
     */
    fun combine(fingerprints: List<String>): String? = when (fingerprints.size) {
        0 -> null
        1 -> fingerprints.single()
        else -> sha256Hex(fingerprints.sorted().joinToString(",").encodeToByteArray())
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }

    private const val HEX = "0123456789abcdef"
}
