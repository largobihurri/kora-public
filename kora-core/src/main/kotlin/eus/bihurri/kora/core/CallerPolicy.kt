package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Scope
import kotlinx.coroutines.CancellationException

/**
 * Decides whether a [Caller] may use something protected by a [Scope]. The node asks once per call and once
 * per subscription, before any provider code runs. Implementations should answer from in-memory state.
 */
public fun interface CallerPolicy {
    public suspend fun authorize(caller: Caller, scope: Scope): Boolean
}

/**
 * The user-controlled gate for callers outside the Bihurri family. Backed by app settings in a real app, which
 * must expose it to the user: the master switch (off by default) and, per caller, the scopes the user approves.
 *
 * Key grants on the whole [Caller] value, or at least on `id` together with `signingCertificateSha256` for
 * [CallerKind.EXTERNAL_APP]: a package name can be reused by a different app after the original is uninstalled,
 * and such an app must not inherit the grants of its predecessor. When the user turns the switch off or removes
 * a grant, also call the transport's revocation hook (on Android `KoraAndroid.revokeUntrustedSubscriptions`) so
 * live subscriptions end.
 */
public interface ExternalAccess {
    /** The master switch. While false, no external caller is allowed anything. */
    public suspend fun isEnabled(): Boolean

    /** Scopes the user granted to [caller], regardless of the master switch. */
    public suspend fun grantedScopes(caller: Caller): Set<Scope>

    /**
     * Called when [caller] was refused [scope], whether because the master switch is off or because the scope was
     * not granted. Lets an app show "X asked for media.library.read" so the user can grant it; the default does
     * nothing.
     *
     * It runs on the calling coroutine before the refusal is answered: keep it fast (record and return) and
     * de-duplicate per caller and scope, because a caller can repeat a refused request as often as it likes.
     * Never called for [CallerKind.SELF] or [CallerKind.PEER]. An exception thrown here is swallowed (the caller
     * is refused regardless); cancellation propagates.
     */
    public suspend fun onDenied(caller: Caller, scope: Scope) {}

    /** Master switch off: used as the safe default when an app does not expose external access. */
    public object Disabled : ExternalAccess {
        override suspend fun isEnabled(): Boolean = false

        override suspend fun grantedScopes(caller: Caller): Set<Scope> = emptySet()
    }
}

/**
 * Default rules:
 * - [CallerKind.SELF] and [CallerKind.PEER] may use every scope. Peers are authenticated by the shared
 *   signature, so they are trusted like the app itself.
 * - [CallerKind.EXTERNAL_APP], [CallerKind.SHELL] and [CallerKind.REMOTE_AGENT] may use a scope only when the
 *   master switch is on **and** the scope was granted to that exact [Caller] (see [ExternalAccess]). A refusal is
 *   reported through [ExternalAccess.onDenied].
 *
 * Scopes are explicit and never imply one another: holding `media.playback.control` does not allow reading
 * the playback state (`media.playback.read`). A grant UI must list every scope the user approves, which keeps
 * what each external caller can see or do visible to the user. There are no wildcards.
 */
public class DefaultCallerPolicy(private val externalAccess: ExternalAccess = ExternalAccess.Disabled) : CallerPolicy {
    override suspend fun authorize(caller: Caller, scope: Scope): Boolean = when (caller.kind) {
        CallerKind.SELF, CallerKind.PEER -> true
        CallerKind.EXTERNAL_APP, CallerKind.SHELL, CallerKind.REMOTE_AGENT -> {
            val allowed = externalAccess.isEnabled() && scope in externalAccess.grantedScopes(caller)
            if (!allowed) reportDenied(caller, scope)
            allowed
        }
    }

    private suspend fun reportDenied(caller: Caller, scope: Scope) {
        try {
            externalAccess.onDenied(caller, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failing notification must not change the outcome: the caller is refused either way.
        }
    }
}
