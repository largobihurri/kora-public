package eus.bihurri.kora.android

import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind

/** Port: the facts about installed packages that identifying a caller depends on. */
internal interface PackageInspector {
    /** Uid of this app. */
    val ownUid: Int

    /** Packages that run under [uid] (several when apps share a uid). */
    fun packagesForUid(uid: Int): List<String>

    /** True when [uid] is signed with the same certificate as this app. */
    fun sharesSignatureWithUs(uid: Int): Boolean

    /**
     * Digest of the certificate that signs [packageName] (see `Caller.signingCertificateSha256`), or null when it
     * cannot be read.
     */
    fun signingCertificateSha256(packageName: String): String?
}

/** Turns the uid of a Binder transaction into a [Caller], or null when the caller is not allowed in. */
internal fun interface CallerResolver {
    fun resolve(callingUid: Int): Caller?
}

/**
 * Identifies Binder callers from verified facts only: the uid the kernel attached to the transaction and the
 * signing certificate of that uid. Nothing inside a payload is trusted. In this order:
 *
 * 1. Same uid as this app: [CallerKind.SELF], with [nodeId] as id.
 * 2. Uid 2000 (adb shell) or 0 (root): [CallerKind.SHELL], with id `"shell"` or `"root"`.
 * 3. Same signing certificate: [CallerKind.PEER], with the package name as id.
 * 4. Any other app: [CallerKind.EXTERNAL_APP], with the package name as id and the digest of its signing
 *    certificate, so that a different app installed later under the same name is a different caller.
 *
 * When a uid is shared by several packages (a legacy `sharedUserId`) the first package in alphabetical order is
 * used, so the id is stable; the packages of one uid are signed by the same certificate.
 *
 * The identifier does not decide what any of them may do: that is the caller policy's job. A caller is rejected
 * (null, the service answers `unauthorized`) only when it cannot be identified: a uid with no package, or an
 * external app whose certificate cannot be read. Failing closed there keeps grants from matching a caller that
 * has no certificate to bind to.
 *
 * Call it inside each Binder method: the calling uid belongs to the transaction, not to the connection.
 */
internal class CallerIdentifier(
    private val inspector: PackageInspector,
    private val nodeId: String,
) : CallerResolver {
    override fun resolve(callingUid: Int): Caller? {
        if (callingUid == inspector.ownUid) return Caller(CallerKind.SELF, nodeId)
        when (callingUid) {
            SHELL_UID -> return Caller(CallerKind.SHELL, "shell")
            ROOT_UID -> return Caller(CallerKind.SHELL, "root")
        }
        val pkg = inspector.packagesForUid(callingUid).minOrNull() ?: return null
        if (inspector.sharesSignatureWithUs(callingUid)) return Caller(CallerKind.PEER, pkg)
        val digest = inspector.signingCertificateSha256(pkg) ?: return null
        return Caller(CallerKind.EXTERNAL_APP, pkg, digest)
    }

    private companion object {
        /** `Process.SHELL_UID`, spelled out so the identifier needs no Android classes. */
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}

/** [PackageInspector] backed by the real [android.content.pm.PackageManager]. */
internal class AndroidPackageInspector(private val context: android.content.Context) : PackageInspector {
    override val ownUid: Int get() = android.os.Process.myUid()

    override fun packagesForUid(uid: Int): List<String> =
        context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    override fun sharesSignatureWithUs(uid: Int): Boolean =
        context.packageManager.checkSignatures(uid, ownUid) == android.content.pm.PackageManager.SIGNATURE_MATCH

    /**
     * Single signer: the current certificate of the signing history (the last entry, which is the one the app is
     * signed with after a key rotation). Several signers: the combination of all of them. See [CertificateDigest].
     */
    override fun signingCertificateSha256(packageName: String): String? {
        val pm = context.packageManager
        val info = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return null
        }
        val signing = info.signingInfo ?: return null
        val certificates = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners.toList()
        } else {
            listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        }
        return CertificateDigest.combine(certificates.map { CertificateDigest.sha256Hex(it.toByteArray()) })
    }
}
