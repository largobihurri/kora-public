package eus.bihurri.kora.core

/** Who is calling, as established by the transport that delivered the request. */
public enum class CallerKind {
    /** The same app and process as the node. */
    SELF,

    /** Another app signed with the common Bihurri key. The transport verifies the signature. */
    PEER,

    /** A third-party app that holds the runtime permission. */
    EXTERNAL_APP,

    /** The adb shell. */
    SHELL,

    /** An MCP client or other remote bridge acting for a user or an agent. */
    REMOTE_AGENT,
}

/**
 * An identified caller. [id] is the package name for apps, `"shell"` (or `"root"`) for adb, the agent id for
 * remote agents and the node id for [CallerKind.SELF] and [CallerKind.PEER]. Transports must derive every field
 * from verified facts (uid, signature), never from request content.
 *
 * [signingCertificateSha256] binds an app identity to the certificate that signed it: lowercase hex, 64 characters,
 * the SHA-256 of the DER-encoded certificate (the fingerprint `apksigner verify --print-certs` prints). Transports
 * fill it for [CallerKind.EXTERNAL_APP]; it is null for the other kinds, which are authenticated by other means
 * (same uid, shared signing key, adb). When the app has several signers the value is a SHA-256 over the sorted
 * signer fingerprints, which matches no single certificate; when the app rotated its signing key it is the
 * current certificate, so a rotation changes the digest and the user is asked to grant access again.
 *
 * A package name alone is not an identity: another app signed with a different key can be installed under the
 * same name after the original is uninstalled. [ExternalAccess] implementations should therefore key grants on
 * the pair (`id`, `signingCertificateSha256`). The digest takes part in equality, so a [Caller] can be used as a
 * map key as it is.
 */
public data class Caller(
    public val kind: CallerKind,
    public val id: String,
    public val signingCertificateSha256: String? = null,
) {
    init {
        require(signingCertificateSha256 == null || CERTIFICATE_DIGEST.matches(signingCertificateSha256)) {
            "signingCertificateSha256 must be 64 lowercase hex characters"
        }
    }

    override fun toString(): String = "$kind:$id"

    private companion object {
        val CERTIFICATE_DIGEST = Regex("[0-9a-f]{64}")
    }
}
