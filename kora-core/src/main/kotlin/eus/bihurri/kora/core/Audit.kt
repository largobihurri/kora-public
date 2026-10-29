package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode

/** What kind of access an [AuditEntry] records. */
public enum class AuditKind { CALL, SUBSCRIBE }

/** Result of an audited access. */
public sealed interface AuditOutcome {
    public data object Ok : AuditOutcome

    public data class Failed(public val code: ErrorCode) : AuditOutcome
}

/**
 * One access to a node, as seen by the node that served it.
 *
 * Entries deliberately hold no request or response payloads: they can carry health or location data, and the
 * audit trail must stay safe to show and to export. [capability] and [member] are the raw strings the caller
 * sent, so they can name things that do not exist; an implementation that stores them should bound their length.
 *
 * For [AuditKind.SUBSCRIBE] the entry is written when the subscription is established (or refused), not when
 * it ends, and [durationMs] covers only the establishment.
 */
public data class AuditEntry(
    public val timeEpochMs: Long,
    public val caller: Caller,
    public val capability: String,
    /** Operation name for calls, event name for subscriptions. */
    public val member: String,
    public val kind: AuditKind,
    public val outcome: AuditOutcome,
    public val durationMs: Long,
)

/**
 * Sink for [AuditEntry]. Called on the calling coroutine, so implementations must be fast and must not throw;
 * hand slow work to another coroutine. A calling coroutine that is cancelled is not audited.
 */
public fun interface AuditLog {
    public fun record(entry: AuditEntry)

    /** Discards every entry. */
    public object None : AuditLog {
        override fun record(entry: AuditEntry): Unit = Unit
    }
}
