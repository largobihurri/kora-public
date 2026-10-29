package eus.bihurri.kora.core

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderCompletenessTest {
    @Test
    fun `a missing operation is rejected naming the capability and every missing operation`() {
        val node = KoraNode("n")
        val e = assertThrows(IllegalArgumentException::class.java) {
            node.provide(MediaPlayback) {
                on(MediaPlayback.Play) { }
                on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.IDLE) }
                emits(MediaPlayback.PlaybackStateChanged) { emptyFlow() }
                emits(MediaPlayback.NowPlayingChanged) { emptyFlow() }
            }
        }
        val message = e.message!!
        assertTrue("media.playback" in message, message)
        for (missing in listOf("pause", "toggle", "next", "previous", "seek_to", "play_playlist", "play_tracks")) {
            assertTrue(missing in message, "$missing should be listed: $message")
        }
        assertFalse("get_state" in message, message)
        assertFalse("missing events" in message, message)
    }

    @Test
    fun `a missing event is rejected naming it`() {
        val node = KoraNode("n")
        val e = assertThrows(IllegalArgumentException::class.java) {
            node.provide(LocationCapability) { on(LocationCapability.GetLastFix) { null } }
        }
        val message = e.message!!
        assertTrue("location" in message && "fix_changed" in message && "missing events" in message, message)
        assertTrue("fixes_batched" in message && "missing operations" in message && "configure_stream" in message, message)
    }

    @Test
    fun `a rejected registration leaves nothing registered`() = runTest {
        val node = KoraNode("n")
        assertThrows(IllegalArgumentException::class.java) { node.provide(MediaPlayback) { on(MediaPlayback.Play) { } } }

        assertTrue(node.describe().capabilities.isEmpty())
        assertFalse(node.isAvailable(MediaPlayback))
    }

    @Test
    fun `a complete registration is accepted and advertised`() {
        val node = KoraNode("n")
        node.provide(LocationCapability) {
            on(LocationCapability.GetLastFix) { LocationFix(1.0, 2.0, timeEpochMs = 3) }
            on(LocationCapability.ConfigureStream) { StreamConfig(it.desiredIntervalMs, it.maxLatencyMs) }
            emits(LocationCapability.FixChanged) { emptyFlow() }
            emits(LocationCapability.FixesBatched) { emptyFlow() }
        }

        assertEquals(listOf("location"), node.describe().capabilities.map { it.id })
        assertTrue(node.isAvailable(LocationCapability))
    }

    @Test
    fun `media history is provided with its single operation and answers a typed call`() = runTest {
        val play = PlayRecord(TrackSummary("t1", "Song"), startedAtEpochMs = 10)
        val node = KoraNode("n")
        node.provide(MediaHistory) {
            on(MediaHistory.ListPlays) { request -> Page(listOf(play).filter { it.startedAtEpochMs in request.fromEpochMs..request.toEpochMs }, 1) }
        }

        assertEquals(listOf("media.history"), node.describe().capabilities.map { it.id })
        assertEquals(listOf(play), node.call(MediaHistory.ListPlays, PlaysRequest(0, 10)).items)
        assertEquals(emptyList<PlayRecord>(), node.call(MediaHistory.ListPlays, PlaysRequest(11, 20)).items)
    }

    @Test
    fun `a request naming an undeclared operation is still unknown_operation`() = runTest {
        val node = KoraNode("n").apply { provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { } } }
        val request = KoraRequest("r1", "media.playback", MediaPlayback.version, "no_such_operation", ByteArray(0))

        val response = node.handle(Caller(CallerKind.SELF, "n"), request)

        assertEquals(ErrorCode.UNKNOWN_OPERATION, (response as KoraResponse.Failure).error.code)
    }

    @Test
    fun `stubbed members answer unknown_operation and never emit`() = runTest {
        val node = KoraNode("n").apply { provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { } } }

        node.call(MediaPlayback.Play, NoInput)
        val failure = try {
            node.call(MediaPlayback.Pause, NoInput)
            null
        } catch (e: KoraException) {
            e
        }

        assertEquals(KoraError(ErrorCode.UNKNOWN_OPERATION, "'pause' is not implemented by this stub"), failure?.error)
    }
}
