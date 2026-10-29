package eus.bihurri.kora.android

import android.os.DeadObjectException
import android.os.TransactionTooLargeException
import eus.bihurri.kora.android.ipc.IKoraEventSink
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeServerTest {
    private val peerUid = 10_200
    private val selfUid = 10_100
    private val externalUid = 10_300
    private val strangerUid = 10_400
    private val shellUid = 2_000

    private val resolver = CallerResolver { uid ->
        when (uid) {
            selfUid -> Caller(CallerKind.SELF, "me")
            peerUid -> Caller(CallerKind.PEER, "eus.bihurri.peer")
            externalUid -> Caller(CallerKind.EXTERNAL_APP, "com.thirdparty", "ab".repeat(32))
            shellUid -> Caller(CallerKind.SHELL, "shell")
            else -> null
        }
    }

    private val nowPlaying = MutableSharedFlow<NowPlaying>(extraBufferCapacity = 16)
    private var nowPlayingCollectors = 0
    private var nowPlayingEnded = 0
    private var playlistCount = 1

    /** Allows the third-party caller everything, so tests can exercise non-trusted subscriptions. */
    private val openAccess = object : ExternalAccess {
        override suspend fun isEnabled() = true

        override suspend fun grantedScopes(caller: Caller): Set<Scope> =
            if (caller.kind == CallerKind.SHELL && !shellGranted) emptySet() else setOf(MediaPlayback.ReadScope, MediaLibrary.ReadScope)
    }
    private var shellGranted = false

    private val node = KoraNode("me", policy = DefaultCallerPolicy(openAccess)).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) {
                Page(List(playlistCount) { PlaylistSummary("pl-$it", "Playlist number $it", 10) }, playlistCount)
            }
        }
        provideStubbed(MediaPlayback) {
            emits(MediaPlayback.NowPlayingChanged) {
                nowPlayingCollectors++
                nowPlaying.onCompletion { nowPlayingEnded++ }
            }
        }
    }

    private class FakeWatcher : SinkWatcher {
        var onDeath: (() -> Unit)? = null
        var closed = false
        var alreadyDead = false

        override fun watch(sink: IKoraEventSink, onDeath: () -> Unit): AutoCloseable? {
            if (alreadyDead) return null
            this.onDeath = onDeath
            return AutoCloseable { closed = true }
        }
    }

    private val watcher = FakeWatcher()

    private fun TestScope.server(max: Int = PayloadLimits.MAX_ENVELOPE_BYTES) =
        NodeServer(node, resolver, backgroundScope, watcher, maxEnvelopeBytes = max)

    private fun listPlaylistsRequest(id: String = "r1") = CborCodec.encode(
        KoraRequest.serializer(),
        KoraRequest(id, MediaLibrary.id.value, MediaLibrary.version, MediaLibrary.ListPlaylists.name, MediaLibrary.ListPlaylists.encodeInput(CborCodec, PageRequest())),
    )

    private fun nowPlayingRequest(id: String = "s1") = CborCodec.encode(
        SubscribeRequest.serializer(),
        SubscribeRequest(id, MediaPlayback.id.value, MediaPlayback.version, MediaPlayback.NowPlayingChanged.name),
    )

    private fun response(callback: FakeCallback): KoraResponse =
        CborCodec.decode(KoraResponse.serializer(), callback.responses.single())

    private fun failureCode(callback: FakeCallback): ErrorCode =
        assertInstanceOf(KoraResponse.Failure::class.java, response(callback)).error.code

    private fun track(n: Int) = NowPlaying(TrackSummary("t-$n", "Track $n"), startedAtEpochMs = n.toLong())

    private fun errorCode(sink: FakeSink): ErrorCode =
        CborCodec.decode(KoraError.serializer(), sink.errors.single()).code

    // ---- describe --------------------------------------------------------------------------------------------

    @Test
    fun `describe answers trusted callers and refuses everyone else`() = runTest {
        val server = server()
        val descriptor = CborCodec.decode(NodeDescriptor.serializer(), server.describe(peerUid))
        assertEquals("me", descriptor.nodeId)
        assertEquals(listOf("media.library", "media.playback"), descriptor.capabilities.map { it.id })
        assertThrows(SecurityException::class.java) { server.describe(strangerUid) }
    }

    // ---- calls -----------------------------------------------------------------------------------------------

    @Test
    fun `a peer call is served and answered through the callback`() = runTest {
        val server = server()
        val callback = FakeCallback()

        server.call(peerUid, listPlaylistsRequest("r7"), callback)
        runCurrent()

        val success = assertInstanceOf(KoraResponse.Success::class.java, response(callback))
        assertEquals("r7", success.id)
        val page = MediaLibrary.ListPlaylists.decodeOutput(CborCodec, success.payload)
        assertEquals(1, page.total)
    }

    @Test
    fun `the caller is derived from the uid, never from the payload`() = runTest {
        var seen: Caller? = null
        val spy = KoraNode("me").apply {
            provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { seen = caller; Page(emptyList(), 0) } }
        }
        val server = NodeServer(spy, resolver, backgroundScope, watcher)

        server.call(peerUid, listPlaylistsRequest(), FakeCallback())
        runCurrent()

        assertEquals(Caller(CallerKind.PEER, "eus.bihurri.peer"), seen)
    }

    @Test
    fun `an unknown caller gets unauthorized with its request id and nothing runs`() = runTest {
        val server = server()
        val callback = FakeCallback()

        server.call(strangerUid, listPlaylistsRequest("r9"), callback)
        runCurrent()

        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(callback))
        assertEquals("r9", failure.id)
        assertEquals(ErrorCode.UNAUTHORIZED, failure.error.code)
    }

    @Test
    fun `a malformed request is answered invalid payload`() = runTest {
        val server = server()
        val callback = FakeCallback()

        server.call(peerUid, byteArrayOf(0x01, 0x02, 0x03), callback)
        runCurrent()

        assertEquals(ErrorCode.INVALID_PAYLOAD, failureCode(callback))
    }

    @Test
    fun `a response above the limit becomes payload too large`() = runTest {
        playlistCount = 50
        val server = server(max = 2_000)
        val callback = FakeCallback()

        server.call(peerUid, listPlaylistsRequest("big"), callback)
        runCurrent()

        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(callback))
        assertEquals("big", failure.id)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.error.code)
    }

    @Test
    fun `a request above the limit is refused without running the handler`() = runTest {
        val server = server(max = 10)
        val callback = FakeCallback()

        server.call(peerUid, listPlaylistsRequest(), callback)
        runCurrent()

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failureCode(callback))
    }

    @Test
    fun `a caller that died before the answer is ignored`() = runTest {
        val server = server()
        val dead = FakeCallback().apply { failWith = DeadObjectException() }
        val tooLarge = FakeCallback().apply { failWith = TransactionTooLargeException() }

        server.call(peerUid, listPlaylistsRequest(), dead)
        server.call(peerUid, listPlaylistsRequest(), tooLarge)
        runCurrent() // must not throw
    }

    // ---- subscriptions ---------------------------------------------------------------------------------------

    @Test
    fun `events reach the sink in order`() = runTest {
        val server = server()
        val sink = FakeSink()

        server.subscribe(peerUid, nowPlayingRequest("s5"), sink)
        runCurrent()
        nowPlaying.emit(track(1))
        nowPlaying.emit(track(2))
        runCurrent()

        val events = sink.events.map { CborCodec.decode(KoraEvent.serializer(), it) }
        assertEquals(listOf("s5", "s5"), events.map { it.subscriptionId })
        assertEquals(
            listOf("Track 1", "Track 2"),
            events.map { MediaPlayback.NowPlayingChanged.decode(CborCodec, it.payload).track.title },
        )
        assertEquals(1, server.activeSubscriptions)
    }

    @Test
    fun `cancelling the handle stops the provider flow and forgets the subscription`() = runTest {
        val server = server()
        val sink = FakeSink()
        val handle = server.subscribe(peerUid, nowPlayingRequest(), sink)
        runCurrent()
        assertEquals(1, nowPlayingCollectors)

        handle.cancel()
        runCurrent()
        nowPlaying.emit(track(1))
        runCurrent()

        assertEquals(1, nowPlayingEnded)
        assertEquals(0, server.activeSubscriptions)
        assertTrue(sink.events.isEmpty())
        assertTrue(!sink.completed && sink.errors.isEmpty())
        assertTrue(watcher.closed)
        handle.cancel() // idempotent
    }

    @Test
    fun `the death of the subscriber cancels its subscription`() = runTest {
        val server = server()
        server.subscribe(peerUid, nowPlayingRequest(), FakeSink())
        runCurrent()

        watcher.onDeath!!.invoke()
        runCurrent()

        assertEquals(1, nowPlayingEnded)
        assertEquals(0, server.activeSubscriptions)
        assertTrue(watcher.closed)
    }

    @Test
    fun `a subscriber that is already dead never starts the provider flow`() = runTest {
        watcher.alreadyDead = true
        val server = server()

        server.subscribe(peerUid, nowPlayingRequest(), FakeSink())
        runCurrent()

        assertEquals(0, nowPlayingCollectors)
        assertEquals(0, server.activeSubscriptions)
    }

    @Test
    fun `a sink that fails ends the subscription`() = runTest {
        val server = server()
        val sink = FakeSink().apply { failWith = DeadObjectException() }
        server.subscribe(peerUid, nowPlayingRequest(), sink)
        runCurrent()

        nowPlaying.emit(track(1))
        runCurrent()

        assertEquals(1, nowPlayingEnded)
        assertEquals(0, server.activeSubscriptions)
    }

    @Test
    fun `a refused subscription is reported on the sink`() = runTest {
        val server = server()
        val unknown = CborCodec.encode(
            SubscribeRequest.serializer(),
            SubscribeRequest("s1", "no.such.capability", MediaPlayback.version, "x"),
        )
        val sink = FakeSink()

        server.subscribe(peerUid, unknown, sink)
        runCurrent()

        assertEquals(ErrorCode.UNKNOWN_CAPABILITY, errorCode(sink))
        assertEquals(0, server.activeSubscriptions)
    }

    @Test
    fun `an unknown caller cannot subscribe`() = runTest {
        val server = server()
        val sink = FakeSink()

        val handle = server.subscribe(strangerUid, nowPlayingRequest(), sink)
        runCurrent()
        handle.cancel()

        assertEquals(ErrorCode.UNAUTHORIZED, errorCode(sink))
        assertEquals(0, nowPlayingCollectors)
    }

    @Test
    fun `a malformed subscription request is reported on the sink`() = runTest {
        val server = server()
        val sink = FakeSink()

        server.subscribe(peerUid, byteArrayOf(0x01, 0x02), sink)
        runCurrent()

        assertEquals(ErrorCode.INVALID_PAYLOAD, errorCode(sink))
    }

    @Test
    fun `an event above the limit ends the subscription with payload too large`() = runTest {
        val server = server(max = 120)
        val sink = FakeSink()
        server.subscribe(peerUid, nowPlayingRequest(), sink)
        runCurrent()

        nowPlaying.emit(NowPlaying(TrackSummary("t", "T".repeat(500)), 1))
        runCurrent()

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, errorCode(sink))
        assertTrue(sink.events.isEmpty())
        assertEquals(1, nowPlayingEnded)
        assertEquals(0, server.activeSubscriptions)
    }

    // ---- revocation ------------------------------------------------------------------------------------------

    @Test
    fun `revoking untrusted subscriptions cancels only those and tells their sinks`() = runTest {
        val server = server()
        val peerSink = FakeSink()
        val selfSink = FakeSink()
        val externalSink = FakeSink()
        server.subscribe(peerUid, nowPlayingRequest("p"), peerSink)
        server.subscribe(selfUid, nowPlayingRequest("s"), selfSink)
        server.subscribe(externalUid, nowPlayingRequest("e"), externalSink)
        runCurrent()
        assertEquals(3, server.activeSubscriptions)

        assertEquals(1, server.revokeUntrusted())
        runCurrent()

        assertEquals(2, server.activeSubscriptions)
        assertEquals(1, nowPlayingEnded)
        assertEquals(ErrorCode.UNAUTHORIZED, errorCode(externalSink))
        assertTrue(peerSink.errors.isEmpty() && selfSink.errors.isEmpty())

        nowPlaying.emit(track(1))
        runCurrent()
        assertEquals(1, peerSink.events.size)
        assertEquals(1, selfSink.events.size)
        assertTrue(externalSink.events.isEmpty())

        assertEquals(0, server.revokeUntrusted())
    }

    @Test
    fun `revocation reaches every kind of outsider`() = runTest {
        shellGranted = true
        val server = server()
        val sinks = listOf(externalUid, shellUid).map { uid -> FakeSink().also { server.subscribe(uid, nowPlayingRequest("x$uid"), it) } }
        runCurrent()
        assertEquals(2, server.activeSubscriptions)

        assertEquals(2, server.revokeUntrusted())
        runCurrent()

        assertEquals(0, server.activeSubscriptions)
        sinks.forEach { assertEquals(ErrorCode.UNAUTHORIZED, errorCode(it)) }
    }

    @Test
    fun `a shell caller without a grant is refused through the policy`() = runTest {
        val server = server()
        val sink = FakeSink()
        server.subscribe(shellUid, nowPlayingRequest(), sink)
        runCurrent()

        assertEquals(ErrorCode.UNAUTHORIZED, errorCode(sink))
        assertEquals(0, server.activeSubscriptions)
    }
}
