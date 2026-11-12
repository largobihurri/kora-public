package eus.bihurri.kora.core.testing

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.StreamDirection
import eus.bihurri.kora.core.DownloadBinding
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.EventBinding
import eus.bihurri.kora.core.PlainBinding
import eus.bihurri.kora.core.ProviderBuilder
import eus.bihurri.kora.core.UploadBinding
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow

/**
 * Completes a registration for tests that exercise only part of a capability.
 *
 * `KoraNode.provide` requires every operation and event of the capability to be registered. Call this last in the
 * block to cover the rest: each operation not handled yet fails with `unknown_operation` ("not implemented by this
 * stub") and each event not offered yet is a subscription that stays open and never emits. An operation that carries
 * a stream gets a stub of its own kind, so it is reached through the stream calls only, like a real handler.
 *
 * Meant for tests only; a real provider implements the whole capability.
 */
public fun <C : Capability> ProviderBuilder<C>.stubUnregistered() {
    for (spec in capability.operations) {
        if (isHandled(spec.name)) continue
        val stub = {
            throw KoraException(KoraError(ErrorCode.UNKNOWN_OPERATION, "'${spec.name}' is not implemented by this stub"))
        }
        registerOperation(
            spec.name,
            when (spec.stream) {
                StreamDirection.NONE -> PlainBinding(spec.scope) { _, _, _ -> stub() }
                StreamDirection.UPLOAD -> UploadBinding(spec.scope) { _, _, _, _ -> stub() }
                StreamDirection.DOWNLOAD -> DownloadBinding(spec.scope) { _, _, _ -> stub() }
            },
        )
    }
    for (spec in capability.events) {
        if (isOffered(spec.name)) continue
        registerEvent(spec.name, EventBinding(spec.scope) { _, _ -> flow { awaitCancellation() } })
    }
}

/** Like `KoraNode.provide`, but completes the registration with [stubUnregistered]; for tests of part of a capability. */
public fun <C : Capability> KoraNode.provideStubbed(capability: C, configure: ProviderBuilder<C>.() -> Unit) {
    provide(capability) {
        configure()
        stubUnregistered()
    }
}
