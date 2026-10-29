package eus.bihurri.kora.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

private val SPEC_NAME = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")

private fun requireSpecName(kind: String, name: String) {
    require(SPEC_NAME.matches(name)) { "Invalid $kind name '$name': expected lower snake_case" }
}

/** Input of operations that take no parameters. Encodes as an empty object. */
@Serializable
public data object NoInput

/**
 * Typed description of one request/response operation of a [Capability].
 *
 * The same spec drives local calls, peer IPC (CBOR) and MCP (JSON and JSON Schema derived from the serializers).
 */
public class OperationSpec<I, O> internal constructor(
    /** Capability that declares this operation. */
    public val capabilityId: CapabilityId,
    /** Version of the declaring capability, i.e. the version a typed caller is built against. */
    public val capabilityVersion: CapabilityVersion,
    public val name: String,
    public val inputSerializer: KSerializer<I>,
    public val outputSerializer: KSerializer<O>,
    public val scope: Scope,
    public val description: String,
) {
    init {
        requireSpecName("operation", name)
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
    public val capabilityId: CapabilityId,
    /** Version of the declaring capability, i.e. the version a typed subscriber is built against. */
    public val capabilityVersion: CapabilityVersion,
    public val name: String,
    public val serializer: KSerializer<E>,
    public val scope: Scope,
    public val description: String,
) {
    init {
        requireSpecName("event", name)
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

    /** Declares an operation. Call only while initialising the capability object. */
    protected fun <I, O> operation(
        name: String,
        input: KSerializer<I>,
        output: KSerializer<O>,
        scope: Scope,
        description: String,
    ): OperationSpec<I, O> {
        require(findOperation(name) == null) { "Duplicate operation '$name' in capability '$id'" }
        return OperationSpec(id, version, name, input, output, scope, description).also { registeredOperations += it }
    }

    /** Declares an event. Call only while initialising the capability object. */
    protected fun <E> event(
        name: String,
        serializer: KSerializer<E>,
        scope: Scope,
        description: String,
    ): EventSpec<E> {
        require(findEvent(name) == null) { "Duplicate event '$name' in capability '$id'" }
        return EventSpec(id, version, name, serializer, scope, description).also { registeredEvents += it }
    }

    /** Advertisement of this capability for discovery. */
    public fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(id.value, version)

    override fun toString(): String = "Capability($id@$version)"
}
