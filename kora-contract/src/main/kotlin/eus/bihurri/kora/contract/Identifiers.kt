package eus.bihurri.kora.contract

import kotlinx.serialization.Serializable

private val DOTTED_LOWERCASE = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*")
private const val MAX_IDENTIFIER_LENGTH = 64

private fun requireDotted(kind: String, value: String) {
    require(value.length <= MAX_IDENTIFIER_LENGTH && DOTTED_LOWERCASE.matches(value)) {
        "Invalid $kind '$value': expected dotted lowercase segments such as 'media.playback'"
    }
}

/** Stable identifier of a capability: dotted lowercase segments, e.g. `media.playback`. */
@JvmInline
public value class CapabilityId(public val value: String) {
    init {
        requireDotted("capability id", value)
    }

    override fun toString(): String = value
}

/**
 * Permission a caller must hold to use an operation or receive an event, e.g. `media.playback.control`.
 * Same lexical rules as [CapabilityId].
 */
@JvmInline
public value class Scope(public val value: String) {
    init {
        requireDotted("scope", value)
    }

    override fun toString(): String = value
}

/**
 * Version of a capability. Within one [major], versions only add optional fields and operations, so a
 * provider is compatible with a caller when it is at least as new as the caller expects.
 */
@Serializable
public data class CapabilityVersion(public val major: Int, public val minor: Int) {
    init {
        require(major >= 0 && minor >= 0) { "Version components must not be negative: $major.$minor" }
    }

    /**
     * True when this (provided) version can serve a caller that was built against [requested]:
     * majors are equal and the provided minor is greater than or equal to the requested minor.
     */
    public fun isCompatibleWith(requested: CapabilityVersion): Boolean =
        major == requested.major && minor >= requested.minor

    override fun toString(): String = "$major.$minor"
}
