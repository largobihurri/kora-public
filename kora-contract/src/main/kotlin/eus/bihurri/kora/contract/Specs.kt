package eus.bihurri.kora.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

private val SPEC_NAME = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")

private fun requireSpecName(kind: String, name: String) {
    require(SPEC_NAME.matches(name)) { "Invalid $kind name '$name': expected lower snake_case" }
}

private fun requireSince(kind: String, name: String, since: CapabilityVersion, capabilityVersion: CapabilityVersion) {
    require(since.major == capabilityVersion.major && since.minor <= capabilityVersion.minor) {
        "Invalid 'since' $since of $kind '$name': expected major ${capabilityVersion.major} and at most $capabilityVersion"
    }
}

/** Input of operations that take no parameters. Encodes as an empty object. */
@Serializable
public data object NoInput

/**
 * One member of a [Capability]: what an [OperationSpec] and an [EventSpec] have in common.
 *
 * A member has two versions. [capabilityVersion] is the version of the capability that declares it, the one the
 * caller's app was built against. [since] is the version the member itself was added in, which is the version a
 * request for it asks the provider for: a provider serves a member when its minor is at least the member's, so an
 * app built against `x@1.1` keeps using the members that exist since 1.0 on a provider that is still at `x@1.0`.
 */
public sealed interface MemberSpec {
    /** Capability that declares this member. */
    public val capabilityId: CapabilityId

    /** Version of the declaring capability, i.e. the version a typed caller is built against. */
    public val capabilityVersion: CapabilityVersion

    /**
     * Capability version this member exists since: the oldest provider version that has it, and the version a
     * request for it carries. Same major as [capabilityVersion] and never newer than it; `major.0` unless the member
     * was added in a later minor.
     */
    public val since: CapabilityVersion

    public val name: String

    public val scope: Scope

    public val description: String
}

/**
 * Whether a byte stream travels beside the envelope of an operation, and which way: see [OperationSpec.stream].
 *
 * Never serialized. Each side reads the direction from its own definition of the operation, so no descriptor,
 * envelope or wire fixture carries it, and the input and output of a stream operation stay ordinary serializable
 * types without the bytes.
 */
public enum class StreamDirection {
    /** No stream: the envelope is the whole call. Every operation that declares nothing else. */
    NONE,

    /** The caller sends bytes to the provider with the request. */
    UPLOAD,

    /** The provider sends bytes back to the caller with its answer. */
    DOWNLOAD,
}

/**
 * What calling an operation does to the provider's data: see [OperationSpec.effect]. A caller such as an assistant
 * reads it to decide whether to run a call at once, ask the user first, or warn that the call cannot be undone.
 *
 * Never serialized, like [StreamDirection]: each side reads it from its own definition of the operation, so no
 * descriptor, envelope or wire fixture carries it. MCP publishes it as the tool annotations `readOnlyHint` and
 * `destructiveHint`.
 */
public enum class OperationEffect {
    /** Changes nothing the user keeps. Every operation whose scope ends in `.read` is a read. */
    READ,

    /**
     * Changes something in a way that can be undone or edited back: creating, editing, moving, completing, pinning,
     * moving an item to a trash it can be restored from, or controlling a player.
     */
    WRITE,

    /** Loses something for good: deleting an item that no trash keeps, or deleting a container with its content. */
    DESTRUCTIVE,
}

/**
 * Typed description of one request/response operation of a [Capability].
 *
 * The same spec drives local calls, peer IPC (CBOR) and MCP (JSON and JSON Schema derived from the serializers).
 */
public class OperationSpec<I, O> internal constructor(
    /** Capability that declares this operation. */
    override val capabilityId: CapabilityId,
    /** Version of the declaring capability, i.e. the version a typed caller is built against. */
    override val capabilityVersion: CapabilityVersion,
    override val name: String,
    public val inputSerializer: KSerializer<I>,
    public val outputSerializer: KSerializer<O>,
    override val scope: Scope,
    override val description: String,
    /**
     * Byte stream that travels beside the envelope, [StreamDirection.NONE] for an ordinary operation. Only
     * transports that carry streams can serve an operation with another direction; on every other path it fails
     * with `provider_unavailable`.
     */
    public val stream: StreamDirection = StreamDirection.NONE,
    /**
     * What a call does to the provider's data. Every built-in operation declares it. An operation declared through
     * one of the [Capability.operation] overloads without an effect gets [OperationEffect.DESTRUCTIVE]: nothing is
     * known about it, so a caller treats it with the most care, as MCP does with a tool that has no annotations.
     */
    public val effect: OperationEffect = OperationEffect.DESTRUCTIVE,
    /** True when the capability declared [effect] itself, false when it is the default of an overload without one. */
    internal val effectDeclared: Boolean = false,
    /** Capability version this operation exists since, which a call asks for; see [MemberSpec.since]. */
    override val since: CapabilityVersion = CapabilityVersion(capabilityVersion.major, 0),
) : MemberSpec {
    init {
        requireSpecName("operation", name)
        requireSince("operation", name, since, capabilityVersion)
    }

    public fun encodeInput(codec: KoraCodec, input: I): ByteArray = codec.encode(inputSerializer, input)

    public fun decodeInput(codec: KoraCodec, bytes: ByteArray): I = codec.decode(inputSerializer, bytes)

    public fun encodeOutput(codec: KoraCodec, output: O): ByteArray = codec.encode(outputSerializer, output)

    public fun decodeOutput(codec: KoraCodec, bytes: ByteArray): O = codec.decode(outputSerializer, bytes)

    override fun toString(): String = "OperationSpec($capabilityId.$name)"
}

/** Typed description of one event a [Capability] can push to subscribers. */
public class EventSpec<E> internal constructor(
    /** Capability that declares this event. */
    override val capabilityId: CapabilityId,
    /** Version of the declaring capability, i.e. the version a typed subscriber is built against. */
    override val capabilityVersion: CapabilityVersion,
    override val name: String,
    public val serializer: KSerializer<E>,
    override val scope: Scope,
    override val description: String,
    /** Capability version this event exists since, which a subscription asks for; see [MemberSpec.since]. */
    override val since: CapabilityVersion = CapabilityVersion(capabilityVersion.major, 0),
) : MemberSpec {
    init {
        requireSpecName("event", name)
        requireSince("event", name, since, capabilityVersion)
    }

    public fun encode(codec: KoraCodec, event: E): ByteArray = codec.encode(serializer, event)

    public fun decode(codec: KoraCodec, bytes: ByteArray): E = codec.decode(serializer, bytes)

    override fun toString(): String = "EventSpec($capabilityId.$name)"
}

/**
 * Definition of a capability: identity, version, and the operations and events it offers.
 *
 * Each concrete capability is an `object` whose specs are properties registered through [operation] and
 * [event], so call sites stay type-safe (`MediaPlayback.SeekTo`) while routers can still enumerate them.
 *
 * A capability is an atomic contract: a node that provides it implements **every** operation and event it declares
 * (`KoraNode.provide` rejects partial registrations), so a peer that sees `id@version` in a descriptor can rely on
 * all of them. Optional features belong in separate capabilities, not in optional members.
 *
 * A member added in a minor version is declared with `since` set to that version (`since = CapabilityVersion(1, 1)`);
 * every other member exists since `major.0`. Callers ask a provider for the member's `since`, not for [version], so
 * raising the minor does not cut an updated app off from providers that still have the previous one
 * ([MemberSpec.since]).
 */
public abstract class Capability(
    public val id: CapabilityId,
    public val version: CapabilityVersion,
) {
    private val registeredOperations = mutableListOf<OperationSpec<*, *>>()
    private val registeredEvents = mutableListOf<EventSpec<*>>()

    public val operations: List<OperationSpec<*, *>> get() = registeredOperations

    public val events: List<EventSpec<*>> get() = registeredEvents

    public fun findOperation(name: String): OperationSpec<*, *>? = registeredOperations.firstOrNull { it.name == name }

    public fun findEvent(name: String): EventSpec<*>? = registeredEvents.firstOrNull { it.name == name }

    /**
     * Declares an operation with its [effect] and, when one travels beside its envelope, the direction of its byte
     * [stream]. Call only while initialising the capability object. Every built-in operation is declared this way.
     *
     * @param since the capability version the operation was added in, when that is not `major.0`. It must have the
     *   major of [version] and must not be newer than it, otherwise this throws `IllegalArgumentException`.
     */
    protected fun <I, O> operation(
        name: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        scope: Scope,
        description: String,
        effect: OperationEffect,
        stream: StreamDirection = StreamDirection.NONE,
        since: CapabilityVersion = CapabilityVersion(version.major, 0),
    ): OperationSpec<I, O> =
        register(name, input, output, scope, description, stream, effect, effectDeclared = true, since = since)

    /**
     * Declares an operation without a byte stream and without an effect, which is then
     * [OperationEffect.DESTRUCTIVE] (see [OperationSpec.effect]). Kept for capabilities written before effects
     * existed; prefer the overload that takes an effect. Call only while initialising the capability object.
     *
     * @param since as in the overload that takes an effect.
     */
    protected fun <I, O> operation(
        name: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        scope: Scope,
        description: String,
        since: CapabilityVersion = CapabilityVersion(version.major, 0),
    ): OperationSpec<I, O> = operation(name, input, output, scope, description, StreamDirection.NONE, since)

    /**
     * Declares an operation whose byte stream travels in direction [stream] beside its envelope, without an effect,
     * which is then [OperationEffect.DESTRUCTIVE] (see [OperationSpec.effect]). Kept for capabilities written before
     * effects existed; prefer the overload that takes an effect. Call only while initialising the capability object.
     *
     * @param since as in the overload that takes an effect.
     */
    protected fun <I, O> operation(
        name: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        scope: Scope,
        description: String,
        stream: StreamDirection,
        since: CapabilityVersion = CapabilityVersion(version.major, 0),
    ): OperationSpec<I, O> = register(
        name, input, output, scope, description, stream, OperationEffect.DESTRUCTIVE, effectDeclared = false, since = since,
    )

    private fun <I, O> register(
        name: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        scope: Scope,
        description: String,
        stream: StreamDirection,
        effect: OperationEffect,
        effectDeclared: Boolean,
        since: CapabilityVersion,
    ): OperationSpec<I, O> {
        require(findOperation(name) == null) { "Duplicate operation '$name' in capability '$id'" }
        return OperationSpec(id, version, name, input, output, scope, description, stream, effect, effectDeclared, since)
            .also { registeredOperations += it }
    }

    /**
     * Declares an event. Call only while initialising the capability object.
     *
     * @param since the capability version the event was added in, when that is not `major.0`. It must have the major
     *   of [version] and must not be newer than it, otherwise this throws `IllegalArgumentException`.
     */
    protected fun <E> event(
        name: String,
        serializer: KSerializer<E>,
        scope: Scope,
        description: String,
        since: CapabilityVersion = CapabilityVersion(version.major, 0),
    ): EventSpec<E> {
        require(findEvent(name) == null) { "Duplicate event '$name' in capability '$id'" }
        return EventSpec(id, version, name, serializer, scope, description, since).also { registeredEvents += it }
    }

    /** Advertisement of this capability for discovery. */
    public fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(id.value, version)

    override fun toString(): String = "Capability($id@$version)"
}
