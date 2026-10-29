package eus.bihurri.kora.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbDeviceChannelTest {
    private val calls = mutableListOf<List<String>>()
    private var next: (List<String>) -> AdbResult = { AdbResult(0, "Result: Bundle[{}]") }
    private val adb = AdbRunner { args -> calls += args; next(args) }
    private val channel = AdbDeviceChannel(adb, "emulator-5554", "eus.bihurri.kora.sample.provider")

    /** A phone that decodes the argument and answers with the message echoed in a result envelope. */
    private fun echoPhone(args: List<String>): AdbResult {
        val argument = args[args.indexOf("--arg") + 1]
        val message = Framing.decode(argument.removePrefix("b64:"))!!
        val answer = """{"jsonrpc":"2.0","id":1,"result":${kotlinx.serialization.json.JsonPrimitive(message)}}"""
        return AdbResult(0, "Result: Bundle[{json_b64=${Framing.encode(answer).removePrefix("b64:")}}]\n")
    }

    @Test
    fun `the command targets the serial and the gate of the app`() {
        channel.send("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")

        assertEquals(
            listOf(
                "-s", "emulator-5554", "shell", "content", "call",
                "--uri", "content://eus.bihurri.kora.sample.provider.kora",
                "--method", "kora.mcp",
                "--arg", "b64:" + Framing.encode("""{"jsonrpc":"2.0","id":1,"method":"ping"}""").removePrefix("b64:"),
            ),
            calls.single(),
        )
    }

    @Test
    fun `a notification yields no answer`() {
        assertNull(channel.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `hostile characters survive the round trip and never reach the shell unescaped`() {
        next = ::echoPhone
        val message = "{\"params\":{\"x\":\"\$(reboot) `id` ' \\\" ; & | > \u00e1\u3053\uD83C\uDFA7 \\n\"}}"

        val answer = channel.send(message)!!

        val echoed = kotlinx.serialization.json.Json.parseToJsonElement(answer).let {
            (it as kotlinx.serialization.json.JsonObject)["result"] as kotlinx.serialization.json.JsonPrimitive
        }.content
        assertEquals(message, echoed)
        val argument = calls.single().last()
        assertTrue(argument.removePrefix("b64:").all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `adb failures carry its first line`() {
        next = { AdbResult(1, "error: device 'x' not found\n") }

        val e = assertThrows(DeviceException::class.java) { channel.send("{}") }

        assertTrue(e.message!!.contains("error: device 'x' not found"), e.message)
    }

    @Test
    fun `a missing adb is reported by the runner`() {
        val missing = AdbDeviceChannel({ throw DeviceException("Cannot run 'adb'") }, "s", "a.b")

        assertThrows(DeviceException::class.java) { missing.send("{}") }
    }

    @Test
    fun `a refusal by the gate keeps its code`() {
        next = { AdbResult(0, "Result: Bundle[{error_code=unauthorized, error_message=Caller is not allowed to use this Kora node}]") }

        val e = assertThrows(DeviceException::class.java) { channel.send("{}") }

        assertEquals("unauthorized", e.code)
        assertTrue(e.message!!.contains("Caller is not allowed"))
    }

    @Test
    fun `output that is not a bundle is an error naming the app`() {
        for (output in listOf("Result: null", "Error while accessing provider:x\n", "")) {
            next = { AdbResult(0, output) }

            val e = assertThrows(DeviceException::class.java) { channel.send("{}") }

            assertTrue(e.message!!.contains("eus.bihurri.kora.sample.provider"), e.message)
        }
    }

    @Test
    fun `a message too large for the command line is refused before adb runs`() {
        val e = assertThrows(DeviceException::class.java) { channel.send("x".repeat(80_000)) }

        assertEquals("payload_too_large", e.code)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `serial and application id are validated because they reach a shell`() {
        for (id in listOf("", "nodots", "a.b; reboot", "a.b c", "a..b", ".a.b", "a.b\$(x)", "a.1b")) {
            assertThrows(IllegalArgumentException::class.java, { AdbDeviceChannel(adb, "s", id) }, id)
        }
        for (serial in listOf("", " ", "-e", "a b")) {
            assertThrows(IllegalArgumentException::class.java, { AdbDeviceChannel(adb, serial, "a.b") }, serial)
        }
        AdbDeviceChannel(adb, "192.168.1.5:5555", "eus.bihurri.kardio.debug_x")
    }
}
