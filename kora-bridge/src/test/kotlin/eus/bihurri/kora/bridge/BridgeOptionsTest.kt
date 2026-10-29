package eus.bihurri.kora.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BridgeOptionsTest {
    @Test
    fun `serial and package are required, the rest has defaults`() {
        val options = BridgeOptions.parse(listOf("--serial", "emulator-5554", "--package", "eus.bihurri.kora.sample.provider"))

        assertEquals("emulator-5554", options.serial)
        assertEquals("eus.bihurri.kora.sample.provider", options.applicationId)
        assertEquals("adb", options.adb)
        assertEquals(30, options.timeoutSeconds)
    }

    @Test
    fun `the order does not matter and adb and timeout can be set`() {
        val options = BridgeOptions.parse(listOf("--adb", "/opt/adb", "--package", "a.b", "--timeout", "5", "--serial", "s"))

        assertEquals("/opt/adb", options.adb)
        assertEquals(5, options.timeoutSeconds)
    }

    @Test
    fun `bad command lines are rejected with a reason`() {
        for (args in listOf(
            listOf("--serial", "s"),
            listOf("--package", "a.b"),
            listOf("--serial", "s", "--package"),
            listOf("--serial", "s", "--package", "a.b", "--wat", "x"),
            listOf("--serial", "s", "--serial", "t", "--package", "a.b"),
            listOf("--serial", "s", "--package", "a.b", "--timeout", "0"),
            listOf("--serial", "s", "--package", "a.b", "--timeout", "soon"),
        )) {
            assertThrows(IllegalArgumentException::class.java, { BridgeOptions.parse(args) }, args.toString())
        }
    }
}
