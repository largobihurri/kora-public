package eus.bihurri.kora.contract

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Minor versions add optional fields. These tests model a provider on v1.1 talking to a caller on v1.0 and
 * the other way round, for both codecs.
 */
class CompatibilityTest {
    @Serializable
    private data class FixV1(val latitude: Double, val longitude: Double)

    @Serializable
    private data class FixV1Dot1(
        val latitude: Double,
        val longitude: Double,
        val speedMetersPerSecond: Double? = null,
        val provider: String = "unknown",
        val tags: List<String> = emptyList(),
    )

    private val codecs = listOf(CborCodec, JsonCodec)

    @Test
    fun `old reader ignores fields added by a newer minor`() {
        for (codec in codecs) {
            val newer = FixV1Dot1(43.0, -2.0, speedMetersPerSecond = 1.5, provider = "gps", tags = listOf("a"))
            val payload = codec.encode(FixV1Dot1.serializer(), newer)
            assertEquals(FixV1(43.0, -2.0), codec.decode(FixV1.serializer(), payload), codec.javaClass.simpleName)
        }
    }

    @Test
    fun `new reader applies defaults for fields an older minor does not send`() {
        for (codec in codecs) {
            val payload = codec.encode(FixV1.serializer(), FixV1(43.0, -2.0))
            assertEquals(FixV1Dot1(43.0, -2.0), codec.decode(FixV1Dot1.serializer(), payload), codec.javaClass.simpleName)
        }
    }

    @Test
    fun `defaults are written explicitly so a changed default cannot alter old payloads`() {
        val json = JsonCodec.encode(FixV1Dot1.serializer(), FixV1Dot1(1.0, 2.0)).decodeToString()
        assertEquals("""{"latitude":1.0,"longitude":2.0,"speedMetersPerSecond":null,"provider":"unknown","tags":[]}""", json)
    }

    @Test
    fun `envelope tolerates unknown fields added in a later version`() {
        val json = """{"id":"r1","capability":"location","version":{"major":1,"minor":0},"operation":"get_last_fix","payload":[1,2],"traceId":"x"}"""
        val request = JsonCodec.decode(KoraRequest.serializer(), json.encodeToByteArray())
        assertEquals("r1", request.id)
        assertEquals(listOf<Byte>(1, 2), request.payload.toList())
    }

    @Test
    fun `descriptor of a peer built before koraVersion existed decodes with a null version`() {
        val old = """{"nodeId":"kardio","displayName":"Kardio","capabilities":[{"id":"location","version":{"major":1,"minor":0}}]}"""
        val descriptor = JsonCodec.decode(NodeDescriptor.serializer(), old.encodeToByteArray())
        assertEquals(null, descriptor.koraVersion)
        assertEquals("location", descriptor.capabilities.single().id)
    }

    @Test
    fun `the generated library version is a plain version number`() {
        assertEquals(true, Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(KoraBuild.VERSION), KoraBuild.VERSION)
    }
}
