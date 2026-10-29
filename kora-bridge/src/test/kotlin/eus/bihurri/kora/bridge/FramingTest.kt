package eus.bihurri.kora.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FramingTest {
    private val nasty = "{\"a\":\"q\\\"uote \$HOME `tick` 'single' ; | & > < * ? \\n \u00e1\u3053\uD83C\uDFA7\"}"

    @Test
    fun `a message is encoded to characters no shell treats specially`() {
        val argument = Framing.encode(nasty)

        assertTrue(argument.startsWith("b64:"))
        assertTrue(argument.removePrefix("b64:").all { it.isLetterOrDigit() || it == '-' || it == '_' }, argument)
        assertEquals(nasty, Framing.decode(argument.removePrefix("b64:")))
    }

    @Test
    fun `fixed vectors match the phone side`() {
        // The same vectors are asserted by the gate tests of :kora-android.
        assertEquals("b64:eyJhIjoxfQ", Framing.encode("""{"a":1}"""))
        assertEquals("b64:e30", Framing.encode("{}"))
        assertEquals("b64:w6HDqSDjgZM", Framing.encode("\u00e1\u00e9 \u3053"))
    }

    @Test
    fun `decode rejects what is not UTF-8 Base64`() {
        assertNull(Framing.decode("***"))
        assertNull(Framing.decode("wyg")) // 0xC3 0x28: invalid UTF-8
    }

    @Test
    fun `an answer is read from the bundle`() {
        val body = Framing.encode("""{"jsonrpc":"2.0","id":1,"result":{}}""").removePrefix("b64:")

        val outcome = assertInstanceOf(Framing.Outcome.Answer::class.java, Framing.parse("Result: Bundle[{json_b64=$body}]\n"))

        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", outcome.message)
    }

    @Test
    fun `an empty bundle is a notification`() {
        assertNull(assertInstanceOf(Framing.Outcome.Answer::class.java, Framing.parse("Result: Bundle[{}]\n")).message)
    }

    @Test
    fun `a refusal is read whatever the order of the keys and the commas in its message`() {
        val a = Framing.parse("Result: Bundle[{error_code=unauthorized, error_message=Caller is not allowed, really: no}]")
        val b = Framing.parse("Result: Bundle[{error_message=Too big, 300 > 256, error_code=payload_too_large}]")
        val c = Framing.parse("Result: Bundle[{error_code=invalid_payload}]")

        assertEquals("unauthorized" to "Caller is not allowed, really: no", (a as Framing.Outcome.Refused).let { it.code to it.message })
        assertEquals("payload_too_large" to "Too big, 300 > 256", (b as Framing.Outcome.Refused).let { it.code to it.message })
        assertEquals("invalid_payload" to "", (c as Framing.Outcome.Refused).let { it.code to it.message })
    }

    @Test
    fun `anything else is unreadable`() {
        for (output in listOf("", "Result: null", "Error while accessing provider:x.kora\njava.lang.SecurityException", "Result: Bundle[{json_b64=!!}]")) {
            assertInstanceOf(Framing.Outcome.Unreadable::class.java, Framing.parse(output), output)
        }
    }

    @Test
    fun `warnings before the result are ignored`() {
        val outcome = Framing.parse("* daemon started successfully\nResult: Bundle[{}]")

        assertInstanceOf(Framing.Outcome.Answer::class.java, outcome)
    }
}
