package eus.bihurri.kora.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.core.Caller
import kotlinx.coroutines.runBlocking

/**
 * The external gate: a `ContentProvider` with the authority `<applicationId>.kora` (see [KoraGate]) declared,
 * exported, in this library's manifest. It serves the adb shell, other apps and later remote agents through
 * `ContentResolver.call`, with the methods [KoraGate.METHOD_DESCRIBE], [KoraGate.METHOD_CALL],
 * [KoraGate.METHOD_CALL_JSON] and [KoraGate.METHOD_MCP] (MCP over adb). It exposes no data: `query`, `insert`, `update` and `delete` are unsupported.
 *
 * There is no manifest permission, on purpose. A custom permission would be defined by every Kora app, and
 * apps signed with different keys cannot define the same permission (`INSTALL_FAILED_DUPLICATE_PERMISSION`);
 * it would also let another app squat the name. Access is gated in code instead: each call identifies the
 * calling uid (`CallerIdentifier`), and the caller policy asks the app's `ExternalAccess` whether the master
 * switch is on and the scope was granted to that package and certificate. Calls are audited like any other.
 *
 * Calls must come from a background thread. A caller in this same process on the main thread is refused, so the
 * gate can never block the UI. Calls from other processes arrive on Binder threads.
 *
 * Why this entry may block while `KoraNodeService` does not: `ContentProvider.call` is synchronous by
 * contract, so the handler runs under `runBlocking` on the Binder thread, bounded by the node's call timeout.
 * The service instead stays asynchronous (callbacks) because it also carries long-lived subscriptions and many
 * concurrent callers; the gate serves single, short, user- or tool-initiated calls.
 */
public class KoraGateProvider : ContentProvider() {
    // The provider is created before Application.onCreate, so the node is looked up on each call.
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return refusal(method, ErrorCode.PROVIDER_UNAVAILABLE, "The Kora gate cannot be called from the main thread")
        }
        val runtime = KoraAndroid.awaitRuntime(INSTALL_WAIT_MILLIS)
            ?: return refusal(method, ErrorCode.PROVIDER_UNAVAILABLE, "Kora is not installed in this app")
        val gate = runtime.gate
        val context = context ?: return refusal(method, ErrorCode.PROVIDER_UNAVAILABLE, "Provider is not attached")
        val caller: Caller = CallerIdentifier(AndroidPackageInspector(context), runtime.node.nodeId)
            .resolve(Binder.getCallingUid())
            ?: return refusal(method, ErrorCode.UNAUTHORIZED, "Caller is not allowed to use this Kora node")

        return when (method) {
            KoraGate.METHOD_DESCRIBE -> Bundle().apply { putString(KoraGate.KEY_JSON, gate.describe()) }

            KoraGate.METHOD_CALL -> {
                val request = extras?.getByteArray(KoraGate.EXTRA_REQUEST)
                    ?: return refusal(method, ErrorCode.INVALID_PAYLOAD, "Extra '${KoraGate.EXTRA_REQUEST}' is required")
                Bundle().apply { putByteArray(KoraGate.KEY_RESPONSE, runBlocking { gate.callBinary(caller, request) }) }
            }

            KoraGate.METHOD_CALL_JSON -> {
                val argument = arg ?: return refusal(method, ErrorCode.INVALID_PAYLOAD, "The argument is required")
                Bundle().apply { putString(KoraGate.KEY_JSON, runBlocking { gate.callJson(caller, argument) }) }
            }

            KoraGate.METHOD_MCP -> {
                val message = arg ?: return refusal(method, ErrorCode.INVALID_PAYLOAD, "The argument is required")
                when (val answer = runBlocking { runtime.mcp.handle(caller, message) }) {
                    is McpGate.Answer.Refused -> refusal(method, answer.code, answer.message)
                    is McpGate.Answer.Reply -> Bundle().apply {
                        // A notification has no answer: the bundle stays empty.
                        answer.message?.let { putString(if (answer.encoded) KoraGate.KEY_JSON_BASE64 else KoraGate.KEY_JSON, it) }
                    }
                }
            }

            else -> refusal(method, ErrorCode.UNKNOWN_OPERATION, "Unknown method '$method'")
        }
    }

    /** The answer when the gate itself, not the node, refuses: error keys plus the failure in the method's form. */
    private fun refusal(method: String, code: ErrorCode, message: String): Bundle {
        return Bundle().apply {
            putString(KoraGate.KEY_ERROR_CODE, code.wireName)
            putString(KoraGate.KEY_ERROR_MESSAGE, message)
            if (method == KoraGate.METHOD_CALL) {
                putByteArray(KoraGate.KEY_RESPONSE, GateFailures.binary("", code, message))
            } else if (method != KoraGate.METHOD_MCP) { // an MCP answer is a JSON-RPC message, never this envelope
                putString(KoraGate.KEY_JSON, GateFailures.json(code, message))
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("The Kora gate only supports call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("The Kora gate only supports call()")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("The Kora gate only supports call()")

    private companion object {
        /** How long a call that arrives during process start waits for `KoraAndroid.install`. */
        const val INSTALL_WAIT_MILLIS = 2_000L
    }
}
