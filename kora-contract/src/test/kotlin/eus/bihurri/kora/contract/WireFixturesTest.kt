package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fixtures.FixtureCase
import eus.bihurri.kora.contract.fixtures.FixtureFormat
import eus.bihurri.kora.contract.fixtures.FixtureStore
import eus.bihurri.kora.contract.fixtures.WireFixtureVerifier
import eus.bihurri.kora.contract.media.PageRequest
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Golden wire fixtures of this module (`src/test/resources/wire`). The first two tests are the compatibility
 * guarantee of `docs/compatibility.md`; the rest check the samples and the fixture machinery itself.
 */
class WireFixturesTest {
    private val verifier = WireFixtureVerifier(WireSamples.cases)

    @Test
    fun `every stored fixture of every version still decodes with the current code`() {
        verifier.verifyStoredFixturesDecode()
    }

    @Test
    fun `the current encoding equals the stored fixtures of the current versions`() {
        verifier.verifyCurrentEncodingMatches()
    }

    @Test
    fun `samples cover every operation input, output and event of every built-in capability at its version`() {
        for (capability in Capabilities.all) {
            val mine = WireSamples.cases.filter { it.group == capability.id.value }
            assertTrue(mine.all { it.version == capability.version.toString() }, "${capability.id} samples use ${capability.version}")
            val names = mine.map { it.name }.toSet()
            val required = WireSamples.requiredNames(capability)
            assertEquals(emptySet<String>(), required - names, "${capability.id} lacks samples")
            // Variants are named "<required name>.<variant>"; anything else is a typo.
            val stray = names.filter { name -> required.none { name == it || name.startsWith("$it.") } }
            assertEquals(emptyList<String>(), stray, "${capability.id} has samples for members that do not exist")
        }
    }

    @Test
    fun `samples only exist for built-in capabilities and the envelope`() {
        val groups = WireSamples.cases.map { it.group }.toSet()
        assertEquals(Capabilities.all.map { it.id.value }.toSet() + WireSamples.ENVELOPE_GROUP, groups)
    }

    // ---- The machinery ----------------------------------------------------------------------------------------

    private val case = FixtureCase("test.thing", "1.1", "get.output", PageRequest.serializer(), PageRequest(5, 10))

    private fun store(dir: Path, recording: Boolean = false) = FixtureStore(dir, dir, recording)

    private fun write(dir: Path, version: String, name: String, format: FixtureFormat, text: String) {
        val file = dir.resolve("test.thing").resolve(version).resolve("$name.${format.extension}")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun writeBoth(dir: Path, version: String, c: FixtureCase<*> = case) {
        FixtureFormat.entries.forEach { write(dir, version, c.name, it, c.encode(it) + "\n") }
    }

    @Test
    fun `recording adds missing fixtures, a second run passes and nothing is overwritten`(@TempDir dir: Path) {
        WireFixtureVerifier(listOf(case), store(dir, recording = true)).verifyCurrentEncodingMatches()

        assertTrue(Files.exists(dir.resolve("test.thing/1.1/get.output.json")))
        assertTrue(Files.exists(dir.resolve("test.thing/1.1/get.output.cbor.hex")))
        WireFixtureVerifier(listOf(case), store(dir)).verifyCurrentEncodingMatches()
        assertThrows(IllegalStateException::class.java) {
            store(dir, recording = true).record("test.thing", "1.1", "get.output", FixtureFormat.JSON, "{}")
        }
    }

    @Test
    fun `a missing fixture fails when not recording`(@TempDir dir: Path) {
        val error = assertThrows(AssertionError::class.java) { WireFixtureVerifier(listOf(case), store(dir)).verifyCurrentEncodingMatches() }

        assertTrue(error.message!!.contains("-Pkora.recordFixtures=true"), error.message)
    }

    @Test
    fun `an accidental wire change is detected and recording does not hide it`(@TempDir dir: Path) {
        writeBoth(dir, "1.1")
        val changed = FixtureCase("test.thing", "1.1", "get.output", PageRequest.serializer(), PageRequest(5, 11))

        val error = assertThrows(AssertionError::class.java) {
            WireFixtureVerifier(listOf(changed), store(dir, recording = true)).verifyCurrentEncodingMatches()
        }

        assertTrue(error.message!!.contains("changed on the wire"), error.message)
        assertEquals(case.encode(FixtureFormat.JSON) + "\n", Files.readString(dir.resolve("test.thing/1.1/get.output.json")))
    }

    @Test
    fun `fixtures of older versions are only decoded, never compared with the current encoding`(@TempDir dir: Path) {
        writeBoth(dir, "1.0")
        writeBoth(dir, "1.1")
        val newer = FixtureCase("test.thing", "1.2", "get.output", PageRequest.serializer(), PageRequest(7, 7))
        writeBoth(dir, "1.2", newer)

        WireFixtureVerifier(listOf(newer), store(dir)).verifyStoredFixturesDecode()
        WireFixtureVerifier(listOf(newer), store(dir)).verifyCurrentEncodingMatches()
    }

    @Test
    fun `an empty store fails instead of passing without checking anything`(@TempDir dir: Path) {
        val error = assertThrows(AssertionError::class.java) { WireFixtureVerifier(listOf(case), store(dir)).verifyStoredFixturesDecode() }

        assertTrue(error.message!!.contains("no stored fixtures were found"), error.message)
        WireFixtureVerifier(listOf(case), store(dir, recording = true)).verifyStoredFixturesDecode()
    }

    @Test
    fun `an old fixture the current code cannot decode is reported`(@TempDir dir: Path) {
        writeBoth(dir, "1.0")
        write(dir, "1.0", "get.output", FixtureFormat.JSON, """{"limit":"many","offset":0}""")
        write(dir, "1.0", "get.output", FixtureFormat.CBOR, "ff")

        val error = assertThrows(AssertionError::class.java) { WireFixtureVerifier(listOf(case), store(dir)).verifyStoredFixturesDecode() }

        assertTrue(error.message!!.contains("test.thing/1.0/get.output.json no longer decodes"), error.message)
        assertTrue(error.message!!.contains("test.thing/1.0/get.output.cbor.hex no longer decodes"), error.message)
    }

    @Test
    fun `a fixture whose sample was removed is reported because released members cannot go away`(@TempDir dir: Path) {
        writeBoth(dir, "1.0")
        val other = FixtureCase("test.thing", "1.1", "other.output", Int.serializer(), 1)

        val error = assertThrows(AssertionError::class.java) { WireFixtureVerifier(listOf(other), store(dir)).verifyStoredFixturesDecode() }

        assertTrue(error.message!!.contains("has no sample in the code"), error.message)
    }

    @Test
    fun `the two files of a fixture must agree and both must exist`(@TempDir dir: Path) {
        writeBoth(dir, "1.0")
        write(dir, "1.0", "get.output", FixtureFormat.JSON, """{"limit":9,"offset":9}""")
        writeBoth(dir, "1.1", FixtureCase("test.thing", "1.1", "get.output", PageRequest.serializer(), PageRequest(5, 10)))
        Files.delete(dir.resolve("test.thing/1.1/get.output.cbor.hex"))

        val error = assertThrows(AssertionError::class.java) { WireFixtureVerifier(listOf(case), store(dir)).verifyStoredFixturesDecode() }

        assertTrue(error.message!!.contains("test.thing/1.0/get.output decodes to different values"), error.message)
        assertTrue(error.message!!.contains("test.thing/1.1/get.output lacks its cbor.hex file"), error.message)
    }

    @Test
    fun `an empty store lists nothing`() {
        assertFalse(FixtureStore(null, null, false).stored().isNotEmpty())
    }
}
