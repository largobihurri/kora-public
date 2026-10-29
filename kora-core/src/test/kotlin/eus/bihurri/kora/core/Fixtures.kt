package eus.bihurri.kora.core

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.SubscribeRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf

val SELF = Caller(CallerKind.SELF, "me")
val PEER = Caller(CallerKind.PEER, "other")
val EXTERNAL = Caller(CallerKind.EXTERNAL_APP, "com.thirdparty.app")
val SHELL = Caller(CallerKind.SHELL, "shell")
val AGENT = Caller(CallerKind.REMOTE_AGENT, "agent-7")

/** Master switch and grants controlled by the test. */
class FakeExternalAccess(var enabled: Boolean = false) : ExternalAccess {
    val grants = mutableMapOf<Caller, Set<Scope>>()
    val denied = mutableListOf<Pair<Caller, Scope>>()

    /** When set, [onDenied] records the denial and then throws it. */
    var deniedFailure: Throwable? = null

    override suspend fun isEnabled(): Boolean = enabled

    override suspend fun grantedScopes(caller: Caller): Set<Scope> = grants[caller].orEmpty()

    override suspend fun onDenied(caller: Caller, scope: Scope) {
        denied += caller to scope
        deniedFailure?.let { throw it }
    }
}

class RecordingAudit : AuditLog {
    val entries = mutableListOf<AuditEntry>()

    override fun record(entry: AuditEntry) {
        entries += entry
    }
}

/** Deterministic clock: every reading advances by [step], tests can also jump with [now]. */
class FakeClock(var now: Long = 1_000, private val step: Long = 0) : () -> Long {
    override fun invoke(): Long = now.also { now += step }
}

fun <I, O> request(
    spec: OperationSpec<I, O>,
    input: I,
    id: String = "r1",
    version: CapabilityVersion = spec.capabilityVersion,
    codec: KoraCodec = CborCodec,
): KoraRequest = KoraRequest(id, spec.capabilityId.value, version, spec.name, spec.encodeInput(codec, input))

fun <E> subscription(
    spec: EventSpec<E>,
    id: String = "s1",
    version: CapabilityVersion = spec.capabilityVersion,
): SubscribeRequest = SubscribeRequest(id, spec.capabilityId.value, version, spec.name)

fun <I, O> KoraResponse.output(spec: OperationSpec<I, O>, codec: KoraCodec = CborCodec): O {
    val success = assertInstanceOf(KoraResponse.Success::class.java, this)
    return spec.decodeOutput(codec, success.payload)
}

fun KoraResponse.errorCode() = assertInstanceOf(KoraResponse.Failure::class.java, this).error.code

fun assertFailure(expected: eus.bihurri.kora.contract.ErrorCode, response: KoraResponse) =
    assertEquals(expected, response.errorCode(), response.toString())
