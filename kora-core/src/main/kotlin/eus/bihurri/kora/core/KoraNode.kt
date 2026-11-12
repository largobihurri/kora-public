package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capabilities
import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityRegistry
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraBuild
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.StreamDirection
import eus.bihurri.kora.contract.SubscribeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One Kora participant: an app (or any process) that provides capabilities, calls the ones it needs, and
 * optionally talks to peer nodes through a [PeerTransport].
 *
 * Inbound side, used by transports: [handle] and [subscribe], and [handleSending] and [handleReceiving] for
 * transports that carry byte streams. Outbound side, used by app code: [call] and [events] with the specs of the
 * contract, e.g. `node.call(MediaPlayback.SeekTo, SeekRequest(1_000))`, and [callSending] and [callReceiving] for
 * operations whose bytes travel beside the envelope (`OperationSpec.stream`).
 * A node with the default [peerDirectory] and [transport] is standalone: it serves and calls itself only.
 *
 * Every access, local or from a peer, goes through the same pipeline: capability lookup, version check,
 * [policy], handler, [audit]. Local calls made through [call] use `Caller(SELF, nodeId)`.
 *
 * @param nodeId stable id of this node, advertised to peers (conventionally the application id).
 * @param displayName human-readable name advertised to peers.
 * @param codec format of payloads inside the envelopes; peers must use the same.
 * @param policy decides which callers may use which scopes. The default allows only SELF and PEER.
 * @param audit receives one entry per call and subscription, never payloads.
 * @param peerDirectory which peers exist and what they provide.
 * @param transport how to reach them.
 * @param clock milliseconds source for audit timestamps and durations; inject a fake in tests.
 * @param callTimeout limit for one outbound [call], local or remote. Stream calls are not bound by it.
 * @param onInternalError notified with the original exception whenever a failure is reported to the caller
 *   as `internal`; the caller never sees its message or stack trace.
 */
public class KoraNode(
    public val nodeId: String,
    public val displayName: String = nodeId,
    private val codec: KoraCodec = CborCodec,
    private val policy: CallerPolicy = DefaultCallerPolicy(),
    private val audit: AuditLog = AuditLog.None,
    private val peerDirectory: PeerDirectory = PeerDirectory.None,
    private val transport: PeerTransport = PeerTransport.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val callTimeout: Duration = 5.seconds,
    private val onInternalError: (Throwable) -> Unit = {},
    private val registry: CapabilityRegistry = Capabilities.registry,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId must not be blank" }
        require(callTimeout.isPositive()) { "callTimeout must be positive: $callTimeout" }
    }

    /** Providers keyed by capability id **and major**: a node may serve `x@1.y` and `x@2.z` at the same time. */
    private val localProviders = MutableStateFlow<Map<ProviderKey, CapabilityProvider>>(emptyMap())
    private val sequence = AtomicLong()

    // ---- Provider registration ------------------------------------------------------------------------------

    /**
     * Makes this node provide [capability], serving the operations and events registered in [configure].
     *
     * A capability is an atomic contract, so [configure] must register a handler for every operation and a source
     * for every event the capability declares. Otherwise this throws `IllegalArgumentException` naming the
     * capability and the missing members, and nothing is registered: fail fast at app start rather than advertise
     * (to peers, the gate or MCP) members that would answer `unknown_operation`. Optional features belong in
     * separate capabilities. Requests naming members the capability does not declare still fail with
     * `unknown_operation`.
     *
     * A node provides **at most one version per major** of a capability, but may provide several majors of the same
     * id at once (`provide(ThingV1)` and `provide(ThingV2)`: equal ids, majors 1 and 2). That is how a provider keeps
     * serving the previous major for a transition period: every request goes to the provider whose major equals the
     * major the caller asked for, [describe] advertises both, and [providers] / [availability] answer per major.
     * Registering a second capability with the same id and the same major fails with `IllegalStateException`.
     */
    public fun <C : Capability> provide(capability: C, configure: ProviderBuilder<C>.() -> Unit) {
        val provider = ProviderBuilder(capability).apply(configure).build()
        localProviders.update { current ->
            val key = ProviderKey(capability.id.value, capability.version.major)
            check(key !in current) {
                "Capability '${capability.id}' major ${key.major} is already provided by '$nodeId'"
            }
            current + (key to provider)
        }
    }

    /**
     * What this node advertises to peers: its identity and the capabilities registered so far, one entry per
     * provided version (two majors of one id are two entries), ordered by id and then version.
     */
    public fun describe(): NodeDescriptor = NodeDescriptor(
        nodeId = nodeId,
        displayName = displayName,
        capabilities = localProviders.value.values.map { it.capability.descriptor() }
            .sortedWith(compareBy({ it.id }, { it.version.major }, { it.version.minor })),
        koraVersion = KoraBuild.VERSION,
    )

    // ---- Inbound entry points (used by transports) ---------------------------------------------------------

    /**
     * Serves one request from [caller]. Never throws for a failed request: every failure is a
     * [KoraResponse.Failure] carrying `unknown_capability`, `incompatible_version`, `unknown_operation`,
     * `unauthorized`, `invalid_payload`, a `KoraException` thrown by the handler as is, or `internal` for anything
     * else (without exception details). Cancellation of the calling coroutine propagates and is not audited.
     *
     * The request carries no byte stream, so an operation that needs one ([handleSending], [handleReceiving]) is
     * `provider_unavailable`, decided after the policy: a refused caller still gets `unauthorized`, and every
     * attempt is audited.
     */
    public suspend fun handle(caller: Caller, request: KoraRequest): KoraResponse =
        try {
            val payload = audited(caller, AuditKind.CALL, request.capability, request.operation) {
                val bound = bind(caller, request)
                val binding = bound.binding as? PlainBinding ?: throw streamOnly(request.operation)
                binding.invoke(codec, bound.context, request.payload)
            }
            KoraResponse.Success(request.id, payload)
        } catch (e: KoraException) {
            KoraResponse.Failure(request.id, e.error)
        }

    /**
     * Serves one request from [caller] that arrived with [bytes], for transports that carry streams. The operation
     * must be an upload (`StreamDirection.UPLOAD`); for any other the answer is `invalid_payload`. Lookup, version
     * check, policy, audit and answers are those of [handle], and they run on the envelope before the handler can
     * read a byte, with one difference: when [bytes] do not carry their declared size and the
     * [ByteStreamSizeException] a read of them threw escapes the handler, the answer is `invalid_payload` with its
     * message, since the caller sent a malformed stream, and `onInternalError` is not notified. [bytes] are closed
     * once this returns, on every path, whether or not the handler read them; an exception the source throws while
     * closing does not change the answer (an `Error` still propagates).
     * Not bound by any timeout; cancellation of the calling coroutine propagates and is not audited.
     */
    public suspend fun handleSending(caller: Caller, request: KoraRequest, bytes: ByteStream): KoraResponse =
        try {
            val payload = audited(caller, AuditKind.CALL, request.capability, request.operation) {
                val bound = bind(caller, request)
                val binding = bound.binding as? UploadBinding
                    ?: throw streamMismatch(request.operation, "takes no upload stream")
                try {
                    binding.invoke(codec, bound.context, request.payload, bytes)
                } catch (e: ByteStreamSizeException) {
                    if (!bytes.sizeViolated) throw e // another stream the handler read: its own failure
                    throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, e.message ?: "Malformed byte stream"), e)
                }
            }
            KoraResponse.Success(request.id, payload)
        } catch (e: KoraException) {
            KoraResponse.Failure(request.id, e.error)
        } finally {
            try {
                bytes.close()
            } catch (_: Exception) {
                // The answer stands whatever the source throws while closing; only an Error gets past.
            }
        }

    /**
     * Serves one request from [caller] whose answer carries bytes, for transports that carry streams. The operation
     * must be a download (`StreamDirection.DOWNLOAD`); for any other the answer is `invalid_payload`. Lookup,
     * version check, policy, audit and answers are those of [handle]. The audit entry is written when the handler
     * returns, so the bytes read afterwards are not audited. A success holds the handler's bytes, which the caller
     * of this method then owns and closes; a failure holds none. Not bound by any timeout; cancellation of the
     * calling coroutine propagates and is not audited.
     */
    public suspend fun handleReceiving(caller: Caller, request: KoraRequest): StreamedResponse {
        var offered: ByteStream? = null
        return try {
            val result = audited(caller, AuditKind.CALL, request.capability, request.operation) {
                val bound = bind(caller, request)
                val binding = bound.binding as? DownloadBinding
                    ?: throw streamMismatch(request.operation, "answers no byte stream")
                binding.invoke(codec, bound.context, request.payload).also { offered = it.bytes }
            }
            StreamedResponse(KoraResponse.Success(request.id, result.output), result.bytes)
        } catch (e: Throwable) {
            offered?.closeQuietly() // the bytes cannot be handed on
            if (e !is KoraException) throw e
            StreamedResponse(KoraResponse.Failure(request.id, e.error), null)
        }
    }

    /**
     * Like [handle], but also serves capabilities that only a peer offers, for bridges (MCP, a PC relay) that act
     * for an external [caller] on a node that does not provide everything itself.
     *
     * When this node provides a compatible version the request is served by [handle]. Otherwise, if a peer offers
     * one, the **node's own policy** authorizes [caller] first, against the scope the contract declares for the
     * operation, and only then is the request forwarded; the peer sees this node as `PEER`, which it trusts, so the
     * policy here is the only check the external caller meets. Only operations of the built-in capabilities can be
     * forwarded this way, because their scope is known from the contract; any other operation is
     * `unknown_operation`. The access is audited as a call by [caller]. When nobody offers the capability the
     * answer is the one [handle] gives. An operation that carries a byte stream is `provider_unavailable` after the
     * policy, as in [handle]: the request has no stream to forward, so nothing is sent.
     *
     * [target] narrows the choice like in [call]: a bridge that already knows which peer the external party means
     * (for example the relay of `:kora-wear-protocol`) passes [Target.Peer] or [Target.Local].
     */
    public suspend fun dispatch(caller: Caller, request: KoraRequest, target: Target = Target.Any): KoraResponse {
        val id = runCatching { CapabilityId(request.capability) }.getOrNull() ?: return handle(caller, request)
        val routed = try {
            Result.success(route(id, request.version, target))
        } catch (e: KoraException) {
            Result.failure(e)
        }
        if (routed.getOrNull() == Destination.Local) return handle(caller, request)
        return try {
            val payload = audited(caller, AuditKind.CALL, request.capability, request.operation) {
                val destination = routed.getOrThrow() // nobody can serve it: audited as a failed call
                val spec = registry.find(request.capability, request.version.major)?.findOperation(request.operation)
                    ?: throw unknownMember("operation", request.operation)
                authorize(caller, spec.scope)
                if (spec.stream != StreamDirection.NONE) throw streamOnly(request.operation)
                when (val response = send(destination, request)) {
                    is KoraResponse.Failure -> throw KoraException(response.error)
                    is KoraResponse.Success -> response.payload
                }
            }
            KoraResponse.Success(request.id, payload)
        } catch (e: KoraException) {
            KoraResponse.Failure(request.id, e.error)
        }
    }

    /**
     * Like [subscribe], but also opens streams of capabilities that only a peer offers: the event counterpart of
     * [dispatch], with the same rules. When this node provides a compatible version the stream is [subscribe]'s;
     * otherwise this node's own policy authorizes [caller] against the scope the contract declares for the event
     * and the subscription is opened on the peer chosen by [target] (this node appears to it as `PEER`). Only
     * events of the built-in capabilities can be forwarded. The access is audited as a subscription by [caller],
     * recorded when the stream is opened; a failure of the peer's stream later is thrown to the collector.
     */
    public fun dispatchSubscribe(caller: Caller, request: SubscribeRequest, target: Target = Target.Any): Flow<KoraEvent> = flow {
        val id = runCatching { CapabilityId(request.capability) }.getOrNull()
        val routed = id?.let {
            try {
                Result.success(route(it, request.version, target))
            } catch (e: KoraException) {
                Result.failure(e)
            }
        }
        if (routed == null || routed.getOrNull() == Destination.Local) {
            emitAll(subscribe(caller, request))
            return@flow
        }
        val incoming = audited(caller, AuditKind.SUBSCRIBE, request.capability, request.event) {
            val destination = routed.getOrThrow() as Destination.Remote // nobody can serve it: audited as failed
            val scope = registry.find(request.capability, request.version.major)?.findEvent(request.event)?.scope
                ?: throw unknownMember("event", request.event)
            authorize(caller, scope)
            transport.subscribe(destination.peer, request)
        }
        emitAll(incoming.catch { throw toKoraException(it, ErrorCode.PROVIDER_UNAVAILABLE) })
    }

    /**
     * Opens the event stream described by [request] for [caller]. The returned flow is cold: each collection is
     * one subscription. Refusals (`unknown_capability`, `incompatible_version`, `unknown_operation`,
     * `unauthorized`) and failures are thrown as [KoraException] when collection starts, before the provider's
     * flow begins; the subscription ends when the collector is cancelled.
     */
    public fun subscribe(caller: Caller, request: SubscribeRequest): Flow<KoraEvent> = flow {
        val source = audited(caller, AuditKind.SUBSCRIBE, request.capability, request.event) {
            val provider = provider(request.capability, request.version)
            val binding = provider.events[request.event] ?: throw unknownMember("event", request.event)
            authorize(caller, binding.scope)
            binding.open(codec, CallContext(caller, provider.capability.id, request.event))
        }
        emitAll(
            source
                .map { KoraEvent(request.subscriptionId, request.capability, request.event, it) }
                .catch { throw toKoraException(it) },
        )
    }

    // ---- Outbound typed client -----------------------------------------------------------------------------

    /**
     * Calls [spec] on a provider chosen by [target] and returns its typed result.
     *
     * Throws [KoraException] with `provider_unavailable` when nobody offers the capability, `incompatible_version`
     * when providers exist but none implements a version compatible with [spec], `timeout` after the configured
     * call timeout, and any error the provider answered with. An operation that carries a byte stream is called with
     * [callSending] or [callReceiving]; passing one here throws `IllegalArgumentException`.
     */
    public suspend fun <I, O> call(spec: OperationSpec<I, O>, input: I, target: Target = Target.Any): O {
        requireCalledWith(spec, StreamDirection.NONE)
        val destination = route(spec.capabilityId, spec.capabilityVersion, target)
        val request = envelope(spec, input)
        val response = try {
            withTimeout(callTimeout) { send(destination, request) }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // a timeout of an enclosing scope is not ours to translate
            throw KoraException(KoraError(ErrorCode.TIMEOUT, "'${spec.name}' did not answer within $callTimeout"), e)
        }
        return result(spec, request, response)
    }

    /**
     * Calls [spec], an upload (`StreamDirection.UPLOAD`), sending [bytes] beside the request, and returns its typed
     * result. Routing, the local pipeline (`SELF`) and the errors are those of [call], with two differences. It is
     * not bound by the call timeout: a streaming transport bounds the transfer, and a local call has no timer. And a
     * peer is reached only through a [StreamingPeerTransport] that can stream to it; otherwise the call fails with
     * `provider_unavailable` before anything is sent, even when another peer could have taken the stream. When the
     * transport finds that [bytes] do not carry their declared size, or the provider's handler finds it and lets
     * that failure escape, the call fails with `invalid_payload`.
     *
     * A streaming transport fails the call with `timeout` when neither a byte nor the answer moves for its idle
     * timeout, and that includes the time after the last byte: the answer has to arrive within the idle timeout of
     * it. So a provider's upload handler starts reading promptly (the transport sends until its buffer is full and
     * then waits, which counts as idle), does only what the answer needs (for `files.upload`, staging the file) and
     * leaves slow work for later (the `add` that uses it). A caller that retries after a `timeout` uploads the bytes
     * again; for `files` that stages a new file, and the one it abandoned expires unused.
     *
     * [bytes] are closed once this returns or throws, on every path. An operation of another direction throws
     * `IllegalArgumentException`.
     */
    public suspend fun <I, O> callSending(
        spec: OperationSpec<I, O>,
        input: I,
        bytes: ByteStream,
        target: Target = Target.Any,
    ): O =
        try {
            requireCalledWith(spec, StreamDirection.UPLOAD)
            val destination = route(spec.capabilityId, spec.capabilityVersion, target)
            val request = envelope(spec, input)
            val response = when (destination) {
                Destination.Local -> handleSending(Caller(CallerKind.SELF, nodeId), request, bytes)
                is Destination.Remote -> {
                    val streaming = streaming(destination.peer)
                    remote { streaming.callSending(destination.peer, request, bytes) }
                }
            }
            result(spec, request, response)
        } finally {
            bytes.closeQuietly()
        }

    /**
     * Calls [spec], a download (`StreamDirection.DOWNLOAD`), and returns its typed output with the bytes the
     * provider sent; the caller owns them and closes the returned [WithBytes]. Routing, timing and errors are those
     * of [callSending]. An operation of another direction throws `IllegalArgumentException`.
     *
     * When the calling coroutine is cancelled (an enclosing `withTimeout`, say) while a handler or a transport that
     * ignores cancellation is still answering, the bytes it answers are closed, since nobody is left to receive them,
     * and the cancellation propagates.
     */
    public suspend fun <I, O> callReceiving(spec: OperationSpec<I, O>, input: I, target: Target = Target.Any): WithBytes<O> {
        requireCalledWith(spec, StreamDirection.DOWNLOAD)
        val destination = route(spec.capabilityId, spec.capabilityVersion, target)
        val request = envelope(spec, input)
        val answer = when (destination) {
            Destination.Local -> handleReceiving(Caller(CallerKind.SELF, nodeId), request)
            is Destination.Remote -> {
                val streaming = streaming(destination.peer)
                remote { streaming.callReceiving(destination.peer, request) }
            }
        }
        val output = try {
            result(spec, request, answer.response).also {
                currentCoroutineContext().ensureActive() // cancelled meanwhile: nobody would receive the bytes
            }
        } catch (e: Throwable) {
            answer.closeQuietly()
            throw e
        }
        return WithBytes(output, checkNotNull(answer.bytes) { "A success carries bytes" })
    }

    /**
     * Events of [spec] from the provider chosen by [target]. The flow is cold: the provider is chosen and the
     * subscription opened when collection starts, and failures (no provider, refusal, transport error) are thrown
     * as [KoraException] at that point. Cancelling the collector ends the subscription.
     */
    public fun <E> events(spec: EventSpec<E>, target: Target = Target.Any): Flow<E> = flow {
        val destination = route(spec.capabilityId, spec.capabilityVersion, target)
        val request = SubscribeRequest(nextId("sub"), spec.capabilityId.value, spec.capabilityVersion, spec.name)
        val incoming = when (destination) {
            Destination.Local -> subscribe(Caller(CallerKind.SELF, nodeId), request)
            is Destination.Remote -> transport.subscribe(destination.peer, request)
        }
        emitAll(
            incoming
                .catch { throw toKoraException(it, ErrorCode.PROVIDER_UNAVAILABLE) }
                .filter { it.subscriptionId == request.subscriptionId && it.event == spec.name }
                .map {
                    try {
                        spec.decode(codec, it.payload)
                    } catch (e: IllegalArgumentException) {
                        throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed event '${spec.name}'"), e)
                    }
                },
        )
    }

    // ---- Availability --------------------------------------------------------------------------------------

    /** The peers currently reachable, as advertised by the [PeerDirectory]; this node itself is never listed. */
    public val peers: StateFlow<List<PeerInfo>> get() = peerDirectory.peers

    /** True when this node or a peer currently provides a version of [capability] compatible with it. */
    public fun isAvailable(capability: Capability): Boolean = providers(capability).isNotEmpty()

    /**
     * Emits whether [capability] is available now and again each time that changes (a provider registers or a peer
     * appears, disappears or changes what it advertises). Meant for capability-gated UI, e.g. `collectAsState`
     * with `isAvailable(capability)` as the initial value.
     */
    public fun availability(capability: Capability): Flow<Boolean> =
        combine(localProviders, peerDirectory.peers) { local, peers -> offering(capability, local, peers).isNotEmpty() }
            .distinctUntilChanged()

    /** Node ids that currently provide a compatible version of [capability]: this node first, then peers. */
    public fun providers(capability: Capability): List<String> =
        offering(capability, localProviders.value, peerDirectory.peers.value)

    // ---- Internals -----------------------------------------------------------------------------------------

    private data class ProviderKey(val capability: String, val major: Int)

    private sealed interface Destination {
        data object Local : Destination

        data class Remote(val peer: PeerInfo) : Destination
    }

    private fun nextId(kind: String): String = "$nodeId-$kind-${sequence.incrementAndGet()}"

    private fun otherPeers(peers: List<PeerInfo>): List<PeerInfo> = peers.filter { it.nodeId != nodeId }

    private fun offering(
        capability: Capability,
        local: Map<ProviderKey, CapabilityProvider>,
        peers: List<PeerInfo>,
    ): List<String> {
        val provided = local[ProviderKey(capability.id.value, capability.version.major)]?.capability?.version
        val self = if (provided?.isCompatibleWith(capability.version) == true) {
            listOf(nodeId)
        } else {
            emptyList()
        }
        return self + otherPeers(peers).filter { it.offers(capability.id, capability.version) }.map { it.nodeId }
    }

    private fun route(id: CapabilityId, version: CapabilityVersion, target: Target): Destination {
        val providedHere = localProviders.value.filterKeys { it.capability == id.value }.values
            .map { it.capability.version }.sortedBy { it.major }
        val localMatches = providedHere.any { it.isCompatibleWith(version) }
        val peers = otherPeers(peerDirectory.peers.value)
        return when (target) {
            Target.Local -> when {
                localMatches -> Destination.Local
                providedHere.isNotEmpty() -> throw incompatible(id, version, providedHere)
                else -> throw unavailable(id)
            }

            is Target.Peer -> {
                val peer = peers.firstOrNull { it.nodeId == target.nodeId } ?: throw unavailable(id)
                when {
                    peer.offers(id, version) -> Destination.Remote(peer)
                    peer.advertises(id) -> throw incompatible(id, version, emptyList())
                    else -> throw unavailable(id)
                }
            }

            Target.Any -> when {
                localMatches -> Destination.Local
                else -> {
                    val peer = peers.firstOrNull { it.offers(id, version) }
                    when {
                        peer != null -> Destination.Remote(peer)
                        providedHere.isNotEmpty() -> throw incompatible(id, version, providedHere)
                        peers.any { it.advertises(id) } -> throw incompatible(id, version, emptyList())
                        else -> throw unavailable(id)
                    }
                }
            }
        }
    }

    private suspend fun send(destination: Destination, request: KoraRequest): KoraResponse = when (destination) {
        Destination.Local -> handle(Caller(CallerKind.SELF, nodeId), request)
        is Destination.Remote -> remote { transport.call(destination.peer, request) }
    }

    /**
     * Runs a call on [transport]: cancellation passes untouched, and a failure that is not a [KoraException] becomes
     * `provider_unavailable`.
     */
    private suspend inline fun <T> remote(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw toKoraException(e, ErrorCode.PROVIDER_UNAVAILABLE)
    }

    /** The transport for a stream call to [peer]: only a [StreamingPeerTransport] can carry one. */
    private fun streaming(peer: PeerInfo): StreamingPeerTransport = transport as? StreamingPeerTransport
        ?: throw KoraException(
            KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "The transport to '${peer.nodeId}' carries no byte streams"),
        )

    /** The request envelope of an outbound call of [spec]. */
    private fun <I> envelope(spec: OperationSpec<I, *>, input: I): KoraRequest = KoraRequest(
        id = nextId("req"),
        capability = spec.capabilityId.value,
        version = spec.capabilityVersion,
        operation = spec.name,
        payload = try {
            spec.encodeInput(codec, input)
        } catch (e: IllegalArgumentException) {
            throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Cannot encode input of '${spec.name}'"), e)
        },
    )

    /** The typed result of [response] to [request]: the ids must match, a failure is thrown, the output decoded. */
    private fun <O> result(spec: OperationSpec<*, O>, request: KoraRequest, response: KoraResponse): O {
        if (response.id != request.id) {
            throw KoraException(KoraError(ErrorCode.INTERNAL, "Response does not match the request"))
        }
        return when (response) {
            is KoraResponse.Failure -> throw KoraException(response.error)
            is KoraResponse.Success -> try {
                spec.decodeOutput(codec, response.payload)
            } catch (e: IllegalArgumentException) {
                throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed output of '${spec.name}'"), e)
            }
        }
    }

    /** A direction that does not match the method is a programming error, not a failed call. */
    private fun requireCalledWith(spec: OperationSpec<*, *>, stream: StreamDirection) {
        require(spec.stream == stream) {
            val method = when (spec.stream) {
                StreamDirection.NONE -> "call"
                StreamDirection.UPLOAD -> "callSending"
                StreamDirection.DOWNLOAD -> "callReceiving"
            }
            "Operation '${spec.capabilityId}.${spec.name}' streams ${spec.stream}: call it with $method"
        }
    }

    /** An operation found and authorized for one inbound request. */
    private class Bound(val binding: OperationBinding, val context: CallContext)

    /** Version and provider, then the operation, then the policy: the order every inbound call takes. */
    private suspend fun bind(caller: Caller, request: KoraRequest): Bound {
        val provider = provider(request.capability, request.version)
        val binding = provider.operations[request.operation] ?: throw unknownMember("operation", request.operation)
        authorize(caller, binding.scope)
        return Bound(binding, CallContext(caller, provider.capability.id, request.operation))
    }

    private fun provider(capability: String, requested: CapabilityVersion): CapabilityProvider {
        val providers = localProviders.value
        val provider = providers[ProviderKey(capability, requested.major)]
        if (provider == null) {
            val otherMajors = providers.filterKeys { it.capability == capability }.values
                .map { it.capability.version }.sortedBy { it.major }
            if (otherMajors.isEmpty()) {
                throw KoraException(KoraError(ErrorCode.UNKNOWN_CAPABILITY, "Capability '$capability' is not provided"))
            }
            throw incompatible(CapabilityId(capability), requested, otherMajors)
        }
        val provided = provider.capability.version
        if (!provided.isCompatibleWith(requested)) {
            throw KoraException(
                KoraError(ErrorCode.INCOMPATIBLE_VERSION, "'$capability' is at $provided, which cannot serve $requested"),
            )
        }
        return provider
    }

    private suspend fun authorize(caller: Caller, scope: Scope) {
        if (!policy.authorize(caller, scope)) {
            throw KoraException(KoraError(ErrorCode.UNAUTHORIZED, "Scope '$scope' is not granted"))
        }
    }

    /**
     * Runs [block] and records one audit entry for it. Failures are rethrown as [KoraException]; anything that
     * is not already one becomes `internal`. Cancellation is rethrown untouched and not audited.
     */
    private suspend fun <T> audited(
        caller: Caller,
        kind: AuditKind,
        capability: String,
        member: String,
        block: suspend () -> T,
    ): T {
        val start = clock()
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = toKoraException(e)
            record(start, caller, kind, capability, member, AuditOutcome.Failed(failure.error.code))
            throw failure
        }
        record(start, caller, kind, capability, member, AuditOutcome.Ok)
        return result
    }

    private fun record(
        start: Long,
        caller: Caller,
        kind: AuditKind,
        capability: String,
        member: String,
        outcome: AuditOutcome,
    ) {
        val end = clock()
        audit.record(AuditEntry(end, caller, capability, member, kind, outcome, end - start))
    }

    /** Keeps a [KoraException]; hides everything else behind a message-less error of [code], reporting it. */
    private fun toKoraException(e: Throwable, code: ErrorCode = ErrorCode.INTERNAL): KoraException {
        if (e is KoraException) return e
        if (e is CancellationException) throw e
        runCatching { onInternalError(e) }
        val message = if (code == ErrorCode.INTERNAL) "Internal error" else "Peer failure"
        return KoraException(KoraError(code, message), e)
    }

    private fun unknownMember(kind: String, name: String) =
        KoraException(KoraError(ErrorCode.UNKNOWN_OPERATION, "Unknown $kind '$name'"))

    /** A stream operation requested on a path that carries only envelopes. */
    private fun streamOnly(operation: String) = KoraException(
        KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Operation '$operation' carries a byte stream, which this path cannot carry"),
    )

    /** A stream call whose operation does not take the stream it came with. */
    private fun streamMismatch(operation: String, problem: String) =
        KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Operation '$operation' $problem"))

    private fun unavailable(id: CapabilityId) =
        KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "No provider offers '$id'"))

    /** [provided] are the versions this node has of [id] (none when only peers were considered). */
    private fun incompatible(id: CapabilityId, requested: CapabilityVersion, provided: List<CapabilityVersion>) =
        KoraException(
            KoraError(
                ErrorCode.INCOMPATIBLE_VERSION,
                if (provided.isNotEmpty()) {
                    "'$id' is at ${provided.joinToString(" and ")}, which cannot serve $requested"
                } else {
                    "No provider of '$id' can serve $requested"
                },
            ),
        )
}

private fun PeerInfo.advertises(id: CapabilityId): Boolean = descriptor.capabilities.any { it.id == id.value }

private fun PeerInfo.offers(id: CapabilityId, requested: CapabilityVersion): Boolean =
    descriptor.capabilities.any { it.id == id.value && it.version.isCompatibleWith(requested) }
