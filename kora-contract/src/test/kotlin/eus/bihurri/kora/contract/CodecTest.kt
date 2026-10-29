package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fitness.ActiveSession
import eus.bihurri.kora.contract.fitness.SessionPhase
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.LocationFixBatch
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.location.StreamRequest
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.contract.media.TrackSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlinx.serialization.SerializationException
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodecTest {
    class Sample<T>(val name: String, val serializer: KSerializer<T>, val value: T) {
        fun roundTrip(codec: KoraCodec): T = codec.decode(serializer, codec.encode(serializer, value))

        override fun toString(): String = name
    }

    private val track = TrackSummary("t1", "Title", "Artist", "Album", 215_000, "art-1")
    private val minimalTrack = TrackSummary("t2", "Only title")
    private val session = ActiveSession("s1", "hiking", SessionPhase.RECORDING, 1_700_000_000_000, 61_000, 1234.5)
    private val fix = LocationFix(43.26, -2.93, 12.0, 4.5, 1_700_000_000_000)
    private val playbackState = PlaybackState(PlaybackStatus.PLAYING, 42_000, 1_700_000_000_000, track, shuffle = true)

    private val samples: List<Sample<*>> = listOf(
        Sample("PageRequest", PageRequest.serializer(), PageRequest(20, 40)),
        Sample("PlaylistSummary", PlaylistSummary.serializer(), PlaylistSummary("p1", "Mix", 12, "art-2")),
        Sample("TrackSummary", TrackSummary.serializer(), track),
        Sample("TrackSummary minimal", TrackSummary.serializer(), minimalTrack),
        Sample(
            "Page<PlaylistSummary>",
            Page.serializer(PlaylistSummary.serializer()),
            Page(listOf(PlaylistSummary("p1", "Mix", 12)), total = 31, offset = 20),
        ),
        Sample("Page<TrackSummary> empty", Page.serializer(TrackSummary.serializer()), Page(emptyList(), 0, 0)),
        Sample("PlaylistTracksRequest", PlaylistTracksRequest.serializer(), PlaylistTracksRequest("p1", PageRequest(5, 10))),
        Sample("SearchRequest", SearchRequest.serializer(), SearchRequest("rock")),
        Sample("SeekRequest", SeekRequest.serializer(), SeekRequest(90_000)),
        Sample("PlayPlaylistRequest", PlayPlaylistRequest.serializer(), PlayPlaylistRequest("p1", 3, true)),
        Sample("PlayTracksRequest", PlayTracksRequest.serializer(), PlayTracksRequest(listOf("a", "b"), 1)),
        Sample("PlaybackState", PlaybackState.serializer(), playbackState),
        Sample("PlaybackState idle", PlaybackState.serializer(), PlaybackState(PlaybackStatus.IDLE)),
        Sample("NowPlaying", NowPlaying.serializer(), NowPlaying(track, 1_700_000_000_000)),
        Sample("PlayRecord", PlayRecord.serializer(), PlayRecord(track, 1_700_000_000_000, 61_000)),
        Sample("PlayRecord minimal", PlayRecord.serializer(), PlayRecord(minimalTrack, 1_700_000_000_000)),
        Sample("PlaysRequest", PlaysRequest.serializer(), PlaysRequest(1_700_000_000_000, 1_700_003_600_000, PageRequest(10, 20))),
        Sample("PlaysRequest default page", PlaysRequest.serializer(), PlaysRequest(5, 5)),
        Sample(
            "Page<PlayRecord>",
            Page.serializer(PlayRecord.serializer()),
            Page(listOf(PlayRecord(track, 1_700_000_000_000, 61_000)), total = 3, offset = 2),
        ),
        Sample("ActiveSession", ActiveSession.serializer(), session),
        Sample("ActiveSession? present", ActiveSession.serializer().nullable, session),
        Sample("ActiveSession? absent", ActiveSession.serializer().nullable, null),
        Sample("LocationFix", LocationFix.serializer(), fix),
        Sample("LocationFix minimal", LocationFix.serializer(), LocationFix(0.0, 0.0, timeEpochMs = 1)),
        Sample("LocationFix? absent", LocationFix.serializer().nullable, null),
        Sample("LocationFixBatch", LocationFixBatch.serializer(), LocationFixBatch(listOf(fix, LocationFix(43.27, -2.94, timeEpochMs = 1_700_000_001_000)))),
        Sample("StreamRequest", StreamRequest.serializer(), StreamRequest(1_000, 5_000)),
        Sample("StreamRequest defaults", StreamRequest.serializer(), StreamRequest()),
        Sample("StreamConfig", StreamConfig.serializer(), StreamConfig(2_000, 4_000)),
        Sample("NoInput", NoInput.serializer(), NoInput),
        Sample("Unit", Unit.serializer(), Unit),
        Sample("CapabilityVersion", CapabilityVersion.serializer(), CapabilityVersion(1, 3)),
        Sample("KoraError", KoraError.serializer(), KoraError(ErrorCode.UNAUTHORIZED, "missing scope")),
        Sample(
            "KoraRequest",
            KoraRequest.serializer(),
            KoraRequest("r1", "media.playback", CapabilityVersion(1, 0), "seek_to", byteArrayOf(0, 1, -1, 127, -128)),
        ),
        Sample("KoraResponse.Success", KoraResponse.serializer(), KoraResponse.Success("r1", byteArrayOf(9, 8, 7))),
        Sample(
            "KoraResponse.Failure",
            KoraResponse.serializer(),
            KoraResponse.Failure("r1", KoraError(ErrorCode.INCOMPATIBLE_VERSION, "need 1.2")),
        ),
        Sample("KoraEvent", KoraEvent.serializer(), KoraEvent("sub-1", "location", "fix_changed", byteArrayOf(1, 2, 3))),
        Sample("NodeDescriptor", NodeDescriptor.serializer(), NodeDescriptor("sonora", "Sonora", listOf(MediaPlayback.descriptor()))),
        Sample("SubscribeRequest", SubscribeRequest.serializer(), SubscribeRequest("sub-1", "location", CapabilityVersion(1, 0), "fix_changed")),
    )

    fun cases(): Stream<org.junit.jupiter.params.provider.Arguments> =
        listOf<KoraCodec>(CborCodec, JsonCodec).flatMap { codec ->
            samples.map { org.junit.jupiter.params.provider.Arguments.of(codec.javaClass.simpleName, codec, it) }
        }.stream()

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("cases")
    fun `round trips every model and envelope`(codecName: String, codec: KoraCodec, sample: Sample<*>) {
        assertEquals(sample.value, sample.roundTrip(codec), codecName)
    }

    @Test
    fun `a window that ends before it starts is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PlaysRequest(10, 9) }
        for (codec in listOf<KoraCodec>(CborCodec, JsonCodec)) {
            val bytes = codec.encode(PlaysRequest.serializer(), PlaysRequest(1, 2))
            val inverted = when (codec) {
                JsonCodec -> """{"fromEpochMs":10,"toEpochMs":9}""".encodeToByteArray()
                else -> codec.encode(
                    kotlinx.serialization.builtins.MapSerializer(String.serializer(), Long.serializer()),
                    mapOf("fromEpochMs" to 10L, "toEpochMs" to 9L),
                )
            }
            assertEquals(PlaysRequest(1, 2), codec.decode(PlaysRequest.serializer(), bytes))
            assertThrows(IllegalArgumentException::class.java) { codec.decode(PlaysRequest.serializer(), inverted) }
        }
        assertEquals(10L, PlaysRequest(10, 10).fromEpochMs)
    }

    @Test
    fun `an empty location batch is rejected on construction and on decode`() {
        assertThrows(IllegalArgumentException::class.java) { LocationFixBatch(emptyList()) }
        for (codec in listOf<KoraCodec>(CborCodec, JsonCodec)) {
            val empty = codec.encode(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    kotlinx.serialization.builtins.ListSerializer(LocationFix.serializer()),
                ),
                mapOf("fixes" to emptyList()),
            )
            assertThrows(IllegalArgumentException::class.java) { codec.decode(LocationFixBatch.serializer(), empty) }
        }
    }

    @Test
    fun `a stream request with a non positive value is rejected on decode`() {
        for (codec in listOf<KoraCodec>(CborCodec, JsonCodec)) {
            val invalid = codec.encode(StreamConfig.serializer(), StreamConfig(0, 4_000))
            assertThrows(IllegalArgumentException::class.java) { codec.decode(StreamRequest.serializer(), mapKeys(codec, invalid)) }
        }
    }

    // StreamConfig and StreamRequest share a shape only by field count; re-encode under the request's field names.
    private fun mapKeys(codec: KoraCodec, config: ByteArray): ByteArray = when (codec) {
        JsonCodec -> config.decodeToString().replace("intervalMs", "desiredIntervalMs").encodeToByteArray()
        else -> codec.encode(
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), Long.serializer()),
            mapOf("desiredIntervalMs" to 0L, "maxLatencyMs" to 4_000L),
        )
    }

    @Test
    fun `payload equality compares content`() {
        val a = KoraRequest("r", "location", CapabilityVersion(1, 0), "get_last_fix", byteArrayOf(1, 2))
        val b = KoraRequest("r", "location", CapabilityVersion(1, 0), "get_last_fix", byteArrayOf(1, 2))
        val c = KoraRequest("r", "location", CapabilityVersion(1, 0), "get_last_fix", byteArrayOf(1, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `cbor carries payload as a compact byte string`() {
        val payload = ByteArray(300) { it.toByte() }
        val bytes = CborCodec.encode(KoraEvent.serializer(), KoraEvent("s", "location", "fix_changed", payload))
        // An array-of-ints encoding would need about 550 bytes; a byte string adds only its header.
        assertTrue(bytes.size < payload.size + 100, "encoded size ${bytes.size}")
    }

    @Test
    fun `operation specs encode and decode through the codec`() {
        val bytes = MediaPlayback.SeekTo.encodeInput(CborCodec, SeekRequest(5))
        assertEquals(SeekRequest(5), MediaPlayback.SeekTo.decodeInput(CborCodec, bytes))
    }

    @Test
    fun `error codes use stable wire strings`() {
        val expected = mapOf(
            ErrorCode.UNKNOWN_CAPABILITY to "unknown_capability",
            ErrorCode.UNKNOWN_OPERATION to "unknown_operation",
            ErrorCode.INCOMPATIBLE_VERSION to "incompatible_version",
            ErrorCode.UNAUTHORIZED to "unauthorized",
            ErrorCode.INVALID_PAYLOAD to "invalid_payload",
            ErrorCode.PROVIDER_UNAVAILABLE to "provider_unavailable",
            ErrorCode.TIMEOUT to "timeout",
            ErrorCode.PAYLOAD_TOO_LARGE to "payload_too_large",
            ErrorCode.INTERNAL to "internal",
            ErrorCode.UNKNOWN to "unknown",
        )
        assertEquals(ErrorCode.entries.toSet(), expected.keys)
        for ((code, wire) in expected) {
            val json = JsonCodec.encode(KoraError.serializer(), KoraError(code, "m")).decodeToString()
            assertEquals("""{"code":"$wire","message":"m"}""", json)
        }
    }

    @Test
    fun `response json carries a type discriminator`() {
        val json = JsonCodec.encode(KoraResponse.serializer(), KoraResponse.Failure("r", KoraError(ErrorCode.TIMEOUT))).decodeToString()
        assertTrue(json.contains(""""type":"failure""""), json)
    }

    @Test
    fun `garbage bytes fail with a serialization or argument error`() {
        assertThrows(Exception::class.java) { CborCodec.decode(SeekRequest.serializer(), byteArrayOf(0xFF.toByte(), 0x00)) }
        assertThrows(SerializationException::class.java) { JsonCodec.decode(SeekRequest.serializer(), "not json".encodeToByteArray()) }
    }

    @Test
    fun `exception carries its error`() {
        val error = KoraError(ErrorCode.TIMEOUT, "slow")
        val exception = KoraException(error)
        assertEquals(error, exception.error)
        assertTrue(exception.message!!.contains("TIMEOUT"))
    }

    @Test
    fun `byte payloads survive unchanged inside nested envelopes`() {
        val inner = CborCodec.encode(PlaybackState.serializer(), playbackState)
        val outer = CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success("r", inner))
        val decoded = CborCodec.decode(KoraResponse.serializer(), outer) as KoraResponse.Success
        assertArrayEquals(inner, decoded.payload)
        assertEquals(playbackState, CborCodec.decode(PlaybackState.serializer(), decoded.payload))
    }
}
