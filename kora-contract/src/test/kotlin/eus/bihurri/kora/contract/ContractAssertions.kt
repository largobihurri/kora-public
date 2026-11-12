package eus.bihurri.kora.contract

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows

/*
 * Assertions shared by the contract tests of the organizer capabilities. A `SerializationException` is an
 * `IllegalArgumentException` too, so a plain `assertThrows(IllegalArgumentException)` cannot tell a check of the
 * request from a payload that does not decode at all. These two keep the cases apart.
 */

/** [json] decodes, and the checks of the request (its `init`) reject it. A decoding failure does not count. */
internal fun <I> rejectedOnTheWire(spec: OperationSpec<I, *>, json: String) {
    val error = assertThrows(IllegalArgumentException::class.java) {
        spec.decodeInput(JsonCodec, json.encodeToByteArray())
    }
    assertFalse(error is SerializationException, "the payload did not decode, the request never checked it: $error")
}

/** [json] does not decode: a required field is missing, or a value has the wrong type. */
internal fun <I> undecodable(spec: OperationSpec<I, *>, json: String) {
    assertThrows(SerializationException::class.java) { spec.decodeInput(JsonCodec, json.encodeToByteArray()) }
}
