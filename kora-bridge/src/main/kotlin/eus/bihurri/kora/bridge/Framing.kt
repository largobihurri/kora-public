package eus.bihurri.kora.bridge

import java.nio.charset.CharacterCodingException
import java.util.Base64

/**
 * How a JSON-RPC message travels through `adb shell content call`, and how the answer is read back.
 *
 * The remote shell parses the command again, so a raw JSON argument would need escaping that differs between shells
 * and adb versions. The argument is therefore `b64:` plus the UTF-8 message in URL-safe Base64 without padding
 * (characters `A-Z a-z 0-9 - _` only), which the shell passes through untouched, and the phone answers in the same
 * encoding under the key `json_b64`. These names are the contract of the phone side (`KoraGate.METHOD_MCP`,
 * `PREFIX_BASE64` and `KEY_JSON_BASE64` in `:kora-android`); `FramingTest` and the gate tests pin the same vectors.
 */
internal object Framing {
    const val METHOD = "kora.mcp"
    const val PREFIX = "b64:"
    const val KEY_JSON = "json_b64"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_ERROR_MESSAGE = "error_message"

    fun encode(message: String): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(message.encodeToByteArray())

    /** The text behind [encode]'s URL-safe Base64, or null when it is not UTF-8 text in that encoding. */
    fun decode(base64: String): String? = try {
        Base64.getUrlDecoder().decode(base64).decodeToString(throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: CharacterCodingException) {
        null
    }

    /** What the phone answered in a `content call`. */
    sealed interface Outcome {
        /** The JSON-RPC answer; null when the message was a notification and the bundle is empty. */
        class Answer(val message: String?) : Outcome

        /** The gate refused the call before MCP saw it (`unauthorized`, `payload_too_large`...). */
        class Refused(val code: String, val message: String) : Outcome

        /** Anything else: the output is not a bundle of ours. [detail] is a short excerpt for the log. */
        class Unreadable(val detail: String) : Outcome
    }

    private val ANSWER = Regex("""[{ ,]$KEY_JSON=([^,}]*)(?=[,}])""")
    private val CODE = Regex("""[{ ,]$KEY_ERROR_CODE=([a-z_]+)(?=[,}])""")
    private const val MESSAGE_KEY = "$KEY_ERROR_MESSAGE="
    private const val MAX_DETAIL = 300

    /**
     * Reads the output of `content call`: `Result: Bundle[{json_b64=...}]`, `Result: Bundle[{}]` for a notification,
     * `Result: Bundle[{error_code=..., error_message=...}]` for a refusal. A bundle prints its keys in hash order and
     * messages may contain commas, so keys are matched at their boundaries instead of splitting on commas.
     */
    fun parse(output: String): Outcome {
        val start = output.indexOf("Result: ")
        if (start < 0) return Outcome.Unreadable(excerpt(output))
        val result = output.substring(start + "Result: ".length).trim()
        if (!result.startsWith("Bundle[{") || !result.endsWith("}]")) return Outcome.Unreadable(excerpt(result))
        val body = result.removePrefix("Bundle[").removeSuffix("]")
        CODE.find(body)?.let { code ->
            val from = body.indexOf(MESSAGE_KEY)
            val message = if (from < 0) "" else {
                val tail = body.substring(from + MESSAGE_KEY.length, body.length - 1)
                // error_message may be followed by another key; the bundle prints no other key with a comma inside.
                val next = Regex(""", (?:$KEY_ERROR_CODE|$KEY_JSON)=""").find(tail)
                (if (next != null) tail.substring(0, next.range.first) else tail)
            }
            return Outcome.Refused(code.groupValues[1], message)
        }
        val encoded = ANSWER.find(body)?.groupValues?.get(1) ?: return Outcome.Answer(null)
        val message = decode(encoded)?.takeIf { it.isNotEmpty() } ?: return Outcome.Unreadable("the answer is not UTF-8 Base64")
        return Outcome.Answer(message)
    }

    private fun excerpt(text: String): String = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MAX_DETAIL)
}
