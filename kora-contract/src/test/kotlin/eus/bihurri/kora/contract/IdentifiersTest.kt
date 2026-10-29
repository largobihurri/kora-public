package eus.bihurri.kora.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class IdentifiersTest {
    @ParameterizedTest
    @ValueSource(strings = ["location", "media.playback", "fitness.session", "a1.b2.c3"])
    fun `accepts dotted lowercase ids and scopes`(value: String) {
        assertEquals(value, CapabilityId(value).value)
        assertEquals(value, Scope(value).value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Media", "media.", ".media", "media..playback", "media playback", "1media", "media-playback", "media_playback", "media.Playback"])
    fun `rejects malformed ids and scopes`(value: String) {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId(value) }
        assertThrows(IllegalArgumentException::class.java) { Scope(value) }
    }

    @Test
    fun `rejects over-long ids`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityId("a".repeat(65)) }
    }

    // provided, requested, compatible
    @ParameterizedTest
    @CsvSource(
        "1.0, 1.0, true",
        "1.2, 1.0, true",
        "1.2, 1.2, true",
        "1.1, 1.2, false",
        "2.0, 1.0, false",
        "1.5, 2.0, false",
        "0.3, 0.3, true",
        "0.2, 0.3, false",
    )
    fun `version compatibility`(provided: String, requested: String, expected: Boolean) {
        assertEquals(expected, version(provided).isCompatibleWith(version(requested)))
    }

    @Test
    fun `compatibility is not symmetric`() {
        assertTrue(CapabilityVersion(1, 1).isCompatibleWith(CapabilityVersion(1, 0)))
        assertFalse(CapabilityVersion(1, 0).isCompatibleWith(CapabilityVersion(1, 1)))
    }

    @Test
    fun `rejects negative version components`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityVersion(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityVersion(1, -1) }
    }

    private fun version(text: String): CapabilityVersion {
        val (major, minor) = text.trim().split('.').map(String::toInt)
        return CapabilityVersion(major, minor)
    }
}
