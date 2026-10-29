package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fitness.SessionPhase
import eus.bihurri.kora.contract.media.PlaybackStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Enum forward compatibility: a v1.1 writer adds a constant, a v1.0 reader must not fail. */
class WireEnumTest {
    /** What a newer minor version of [PlaybackStatus] could look like: same names plus one more. */
    @Serializable(with = FutureStatus.Serializer::class)
    enum class FutureStatus(override val wireName: String) : WireEnum {
        IDLE("idle"),
        PLAYING("playing"),
        HOLOGRAM("hologram"),
        UNKNOWN("unknown"),
        ;

        object Serializer : TolerantEnumSerializer<FutureStatus>("test.FutureStatus", entries, UNKNOWN)
    }

    @Serializable
    private data class Reading(val status: PlaybackStatus, val label: String = "")

    @Serializable
    private data class FutureReading(val status: FutureStatus, val label: String = "")

    @Serializable
    private data class ErrorHolder(val code: ErrorCode)

    private val codecs = listOf(CborCodec, JsonCodec)

    @Test
    fun `constant added by a newer version decodes as unknown in both codecs`() {
        for (codec in codecs) {
            val payload = codec.encode(FutureReading.serializer(), FutureReading(FutureStatus.HOLOGRAM, "x"))
            val decoded = codec.decode(Reading.serializer(), payload)
            assertEquals(Reading(PlaybackStatus.UNKNOWN, "x"), decoded, codec.javaClass.simpleName)
        }
    }

    @Test
    fun `known constants round trip unchanged in both codecs`() {
        for (codec in codecs) {
            for (status in PlaybackStatus.entries) {
                val value = Reading(status, "l")
                assertEquals(value, codec.decode(Reading.serializer(), codec.encode(Reading.serializer(), value)))
            }
            for (phase in SessionPhase.entries) {
                val bytes = codec.encode(SessionPhase.serializer(), phase)
                assertEquals(phase, codec.decode(SessionPhase.serializer(), bytes))
            }
            for (code in ErrorCode.entries) {
                val bytes = codec.encode(ErrorHolder.serializer(), ErrorHolder(code))
                assertEquals(ErrorHolder(code), codec.decode(ErrorHolder.serializer(), bytes))
            }
        }
    }

    @Test
    fun `unknown error code reads as unknown and the message survives`() {
        for (codec in codecs) {
            val future = codec.encode(FutureErrorKoraError.serializer(), FutureErrorKoraError(FutureCode.RATE_LIMITED, "slow down"))
            val decoded = codec.decode(KoraError.serializer(), future)
            assertEquals(KoraError(ErrorCode.UNKNOWN, "slow down"), decoded, codec.javaClass.simpleName)
        }
    }

    @Test
    fun `known constants keep their wire strings`() {
        val json = JsonCodec.encode(Reading.serializer(), Reading(PlaybackStatus.BUFFERING)).decodeToString()
        assertEquals("""{"status":"buffering","label":""}""", json)
    }

    @Test
    fun `fallback must be one of the constants and wire names must be unique`() {
        assertThrows(IllegalArgumentException::class.java) {
            TolerantEnumSerializer("test.Dup", listOf(Dup.A, Dup.B), Dup.A)
        }
    }

    enum class Dup(override val wireName: String) : WireEnum {
        A("same"),
        B("same"),
    }

    @Serializable(with = FutureCode.Serializer::class)
    enum class FutureCode(override val wireName: String) : WireEnum {
        INTERNAL("internal"),
        RATE_LIMITED("rate_limited"),
        UNKNOWN("unknown"),
        ;

        object Serializer : TolerantEnumSerializer<FutureCode>("test.FutureCode", entries, UNKNOWN)
    }

    @Serializable
    data class FutureErrorKoraError(val code: FutureCode, val message: String = "")

    @Test
    fun `allowed values list the wire names without the fallback`() {
        assertEquals(
            listOf("idle", "buffering", "playing", "paused", "ended"),
            WireEnums.allowedValues(PlaybackStatus.serializer().descriptor),
        )
        assertEquals(listOf("recording", "paused", "ended"), WireEnums.allowedValues(SessionPhase.serializer().descriptor))
        assertEquals(
            listOf("idle", "playing", "hologram"),
            WireEnums.allowedValues(FutureStatus.serializer().descriptor),
        )
    }

    @Test
    fun `allowed values are found through element and nullable descriptors`() {
        val element = Reading.serializer().descriptor.getElementDescriptor(0)
        assertEquals(PlaybackStatus.entries.dropLast(1).map { it.wireName }, WireEnums.allowedValues(element))
        assertEquals(
            WireEnums.allowedValues(element),
            WireEnums.allowedValues(PlaybackStatus.serializer().nullable.descriptor),
        )
    }

    @Test
    fun `allowed values are absent for descriptors that are not wire enums`() {
        assertNull(WireEnums.allowedValues(String.serializer().descriptor))
        assertNull(WireEnums.allowedValues(Reading.serializer().descriptor))
    }

    @Test
    fun `the fallback value is the wire name a reader produces for an unrecognised constant`() {
        assertEquals("unknown", WireEnums.fallbackValue(PlaybackStatus.serializer().descriptor))
        assertEquals("unknown", WireEnums.fallbackValue(FutureCode.serializer().descriptor))
        assertEquals(
            WireEnums.fallbackValue(PlaybackStatus.serializer().descriptor),
            WireEnums.fallbackValue(Reading.serializer().descriptor.getElementDescriptor(0)),
        )
        assertEquals("unknown", WireEnums.fallbackValue(PlaybackStatus.serializer().nullable.descriptor))
        assertEquals("unknown", JsonCodec.encode(PlaybackStatus.serializer(), PlaybackStatus.UNKNOWN).decodeToString().trim('"'))
    }

    @Test
    fun `the fallback value is absent for descriptors that are not wire enums`() {
        assertNull(WireEnums.fallbackValue(String.serializer().descriptor))
        assertNull(WireEnums.fallbackValue(Reading.serializer().descriptor))
    }
}
