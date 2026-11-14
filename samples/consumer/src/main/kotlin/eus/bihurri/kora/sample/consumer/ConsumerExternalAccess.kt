package eus.bihurri.kora.sample.consumer

import android.util.Log
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.ExternalAccess

/**
 * In-memory [ExternalAccess] for the Binder relay check: the master switch is ON and the adb shell may use the read
 * tools the consumer's MCP gate relays to its peer (`media.library.read`, `media.artwork.read`,
 * `media.playback.read`), so `kora-bridge --package eus.bihurri.kora.sample.consumer` -> consumer -> Binder ->
 * provider answers `get_artwork`. Everything else (`media.playback.control`, other callers) is refused and logged.
 *
 * The consumer authorizes the shell; the provider then serves the consumer as a signed `PEER`, so the provider's own
 * shell grants do not apply to this path. Sample-only: a real app keeps the switch off by default and lets the user
 * grant scopes per caller (see `SampleExternalAccess` in the provider sample).
 */
class ConsumerExternalAccess(
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : ExternalAccess {
    override suspend fun isEnabled(): Boolean = true

    override suspend fun grantedScopes(caller: Caller): Set<Scope> = if (caller == SHELL_CALLER) SHELL_SCOPES else emptySet()

    override suspend fun onDenied(caller: Caller, scope: Scope) {
        log("accessDenied caller=${caller.id} scope=$scope kind=${caller.kind}")
    }

    companion object {
        /** The adb shell as `KoraAndroid` identifies it. */
        val SHELL_CALLER: Caller = Caller(CallerKind.SHELL, "shell")

        /** The read scopes of the provider tools the consumer relays; no control. */
        val SHELL_SCOPES: Set<Scope> = setOf(MediaLibrary.ReadScope, MediaArtwork.ReadScope, MediaPlayback.ReadScope)
    }
}
