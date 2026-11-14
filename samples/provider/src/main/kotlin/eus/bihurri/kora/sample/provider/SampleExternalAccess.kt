package eus.bihurri.kora.sample.provider

import android.util.Log
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.ExternalAccess

/**
 * In-memory [ExternalAccess] for demonstration: the master switch is ON and the adb shell may read the media
 * library and its artwork (`media.library.read`, `media.artwork.read`) but nothing else, so `media.playback.read` is
 * refused and recorded. Artwork is a separate grant: `media.library.read` alone does not cover it.
 *
 * The shell's artwork grant follows the screen's "Grant artwork to adb shell" switch ([shellArtworkGranted], read on
 * every call, see [ShellArtworkGrant]), so the device test can see the provider refuse `get_artwork` while the
 * library tools still answer.
 *
 * **Tailnet agents (sample-only behaviour).** Every paired agent (`CallerKind.REMOTE_AGENT`, see `kora-tailnet`)
 * is granted all the scopes this sample provides ([TAILNET_AGENT_SCOPES]) for as long as its pairing exists, so the
 * device test can run `tools/call` right after "Pair agent" without a grants screen. [isPairedAgent] answers whether
 * the caller is one of the existing pairings; revoking the pairing therefore ends the grant too.
 *
 * **Volume.** The adb shell may also read and set the sample's volume ([VOLUME_SCOPES]), so `kora-bridge` can run
 * the volume check (README, "Volume check"); tailnet agents get the same scopes.
 *
 * A real app must not do this. It must keep the master switch **off by default**, expose it in its settings, let
 * the user grant scopes one by one per caller (a fresh pairing can do nothing until then), persist those grants,
 * key them on the package name together with the signing certificate digest (`Caller.signingCertificateSha256`, so a
 * different app reinstalled under the same package name does not inherit them) and show the requests recorded by
 * [onDenied] ("X asked for media.library.read") so the user can approve them. Whenever the user turns the switch off
 * or removes a grant, the app must also call `KoraAndroid.revokeUntrustedSubscriptions()`.
 */
class SampleExternalAccess(
    private val isPairedAgent: (Caller) -> Boolean = { false },
    private val shellArtworkGranted: () -> Boolean = { true },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : ExternalAccess {
    override suspend fun isEnabled(): Boolean = true

    override suspend fun grantedScopes(caller: Caller): Set<Scope> = when {
        caller.kind == CallerKind.REMOTE_AGENT -> if (isPairedAgent(caller)) TAILNET_AGENT_SCOPES else emptySet()
        caller == SHELL_CALLER -> shellScopes(grantArtwork = shellArtworkGranted())
        else -> emptySet()
    }

    /** A real app records this (de-duplicated per caller and scope) for its settings screen; the sample logs it. */
    override suspend fun onDenied(caller: Caller, scope: Scope) {
        val certificate = caller.signingCertificateSha256?.take(CERTIFICATE_PREFIX) ?: "-"
        log("accessDenied caller=${caller.id} scope=$scope kind=${caller.kind} cert=$certificate")
    }

    companion object {
        /** The adb shell as `KoraAndroid` identifies it (`content call`, `kora-bridge`). */
        val SHELL_CALLER: Caller = Caller(CallerKind.SHELL, "shell")

        /** What the adb shell may do: read the library, read and set the volume and, while [grantArtwork], read artwork. */
        fun shellScopes(grantArtwork: Boolean): Set<Scope> =
            (if (grantArtwork) setOf(MediaLibrary.ReadScope, MediaArtwork.ReadScope) else setOf(MediaLibrary.ReadScope)) +
                VOLUME_SCOPES

        /** What a tailnet pairing may do in this sample: everything the sample provides. */
        val TAILNET_AGENT_SCOPES: Set<Scope> = setOf(
            MediaLibrary.ReadScope,
            MediaArtwork.ReadScope,
            MediaPlayback.ReadScope,
            MediaPlayback.ControlScope,
            MediaVolume.ReadScope,
            MediaVolume.ControlScope,
        )

        /** Read and set the volume (`get_volume`, `volume_changed`, `set_volume`): granted to the adb shell. */
        val VOLUME_SCOPES: Set<Scope> = setOf(MediaVolume.ReadScope, MediaVolume.ControlScope)

        private const val CERTIFICATE_PREFIX = 12
    }
}
