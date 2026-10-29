package eus.bihurri.kora.android

/**
 * Names of the external gate every Kora app exposes: a `ContentProvider` ([KoraGateProvider]) whose `call()`
 * entry serves callers that are not Binder peers (the adb shell, other apps, later remote agents).
 *
 * The authority is `<applicationId>.kora`, e.g. `content://eus.bihurri.kora.sample.provider.kora`. The provider has
 * no manifest permission: it identifies the calling uid in code and the app's `ExternalAccess` (master switch and
 * per-caller scope grants) decides. Every method returns a `Bundle`; when the gate itself refuses a call (not
 * installed, main thread, unidentified caller, unknown method) the bundle also carries [KEY_ERROR_CODE] and
 * [KEY_ERROR_MESSAGE].
 */
public object KoraGate {
    /** No input. Result: [KEY_JSON], the node descriptor as JSON. */
    public const val METHOD_DESCRIBE: String = "kora.describe"

    /**
     * Binary call for apps. Input: [EXTRA_REQUEST], a CBOR `KoraRequest`. Result: [KEY_RESPONSE], a CBOR
     * `KoraResponse` (a success or a failure, never missing).
     */
    public const val METHOD_CALL: String = "kora.call"

    /**
     * JSON call for adb, scripts and agents. Input: the `arg` string, a JSON object
     * `{"capability":"media.library","version":"1.0","operation":"list_playlists","input":{...}}` (`version` and
     * `input` optional). Result: [KEY_JSON], `{"ok":true,"output":...}` or `{"ok":false,"error":{"code","message"}}`.
     */
    public const val METHOD_CALL_JSON: String = "kora.call.json"

    /**
     * MCP over adb. Input: the `arg` string, one JSON-RPC message of the Model Context Protocol (see `:kora-mcp`),
     * either as is or as [PREFIX_BASE64] followed by the UTF-8 message in URL-safe Base64 without padding. Result:
     * the JSON-RPC answer as [KEY_JSON], or as [KEY_JSON_BASE64] (same encoding, no prefix) when the request was
     * Base64; no key at all for a notification, which has no answer. A refusal by the gate itself carries only
     * [KEY_ERROR_CODE] and [KEY_ERROR_MESSAGE]. The MCP session is kept per calling identity, so `initialize` and
     * later requests may arrive in separate calls. Only external callers (the shell, other apps) are served.
     */
    public const val METHOD_MCP: String = "kora.mcp"

    /** Prefix of a Base64-encoded [METHOD_MCP] argument; no JSON-RPC message can begin with it. */
    public const val PREFIX_BASE64: String = "b64:"

    /** Extra of [METHOD_CALL]: the CBOR `KoraRequest` bytes. */
    public const val EXTRA_REQUEST: String = "request"

    /** Result of [METHOD_MCP] when the request was Base64: the JSON-RPC answer, URL-safe Base64 without padding. */
    public const val KEY_JSON_BASE64: String = "json_b64"

    /** Result of [METHOD_CALL]: the CBOR `KoraResponse` bytes. */
    public const val KEY_RESPONSE: String = "response"

    /** Result of [METHOD_DESCRIBE] and [METHOD_CALL_JSON]: a JSON string. */
    public const val KEY_JSON: String = "json"

    /** Present when the gate itself refused the call: a wire error code such as `unauthorized`. */
    public const val KEY_ERROR_CODE: String = "error_code"

    /** Present with [KEY_ERROR_CODE]: a human-readable reason. */
    public const val KEY_ERROR_MESSAGE: String = "error_message"

    /** Authority of the gate of the app with [applicationId]. */
    public fun authority(applicationId: String): String = "$applicationId.kora"
}
