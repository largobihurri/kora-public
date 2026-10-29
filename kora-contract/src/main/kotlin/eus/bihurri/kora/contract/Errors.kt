package eus.bihurri.kora.contract

import kotlinx.serialization.Serializable

/**
 * Stable, machine-readable failure reasons. The [wireName]s are part of the wire contract.
 *
 * A reader that does not know a code added by a newer version decodes it as [UNKNOWN]; treat it as a generic
 * failure and show [KoraError.message].
 */
@Serializable(with = ErrorCode.Serializer::class)
public enum class ErrorCode(override val wireName: String) : WireEnum {
    UNKNOWN_CAPABILITY("unknown_capability"),
    UNKNOWN_OPERATION("unknown_operation"),
    INCOMPATIBLE_VERSION("incompatible_version"),
    UNAUTHORIZED("unauthorized"),
    INVALID_PAYLOAD("invalid_payload"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    TIMEOUT("timeout"),

    /** A request, response or event is too large for the transport (e.g. a Binder transaction). */
    PAYLOAD_TOO_LARGE("payload_too_large"),
    INTERNAL("internal"),

    /** Any code this version does not know. Never produced by this version; only seen when reading. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<ErrorCode>(
        "eus.bihurri.kora.contract.ErrorCode",
        entries,
        UNKNOWN,
    )
}

/** A failure reported to the caller. [message] is for humans and may change; branch on [code]. */
@Serializable
public data class KoraError(public val code: ErrorCode, public val message: String = "")

/** Throwable form of [KoraError], used on the local call path. */
public class KoraException(
    public val error: KoraError,
    cause: Throwable? = null,
) : RuntimeException("${error.code}: ${error.message}", cause)
