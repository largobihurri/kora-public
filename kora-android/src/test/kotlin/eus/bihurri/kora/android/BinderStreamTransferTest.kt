package eus.bihurri.kora.android

import eus.bihurri.kora.android.ipc.IKoraNode
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.files.FileHandle
import eus.bihurri.kora.contract.files.FileIdRequest
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.files.UploadFileRequest
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.PeerDirectory
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.StreamedResponse
import eus.bihurri.kora.core.Target
import eus.bihurri.kora.core.WithBytes
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Streams end to end between a [BinderPeerTransport] and a [NodeServer], on real threads and real time, through
 * in-memory pipes much smaller than the transfers so that both pumps really wait for each other. The Binder in
 * between is a [LoopbackNode], which duplicates every descriptor that crosses as a transaction does.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BinderStreamTransferTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val descriptors = FakeDescriptors(capacity = 4 * 1024)
    private val peerUid = 10_200
    private val strangerUid = 10_400
    private val resolver = CallerResolver { uid -> if (uid == peerUid) Caller(CallerKind.PEER, "eus.bihurri.caller") else null }

    private var uploadHandler: suspend (UploadFileRequest, ByteStream) -> FileHandle = { input, bytes ->
        val content = withContext(Dispatchers.IO) { bytes.input.readAllBytes() }
        uploaded.complete(content)
        FileHandle("f1", input.name, input.mimeType, content.size.toLong())
    }
    private val uploaded = CompletableDeferred<ByteArray>()
    private val handlerFailure = CompletableDeferred<Throwable>()
    private var downloadBytes: () -> ByteStream = { ByteStream(0, ByteArrayInputStream(ByteArray(0))) }
    private var handlerRuns = 0

    private val provider = KoraNode("eus.bihurri.peer").apply {
        provideStubbed(FilesCapability) {
            onUpload(FilesCapability.Upload) { input, bytes ->
                handlerRuns++
                try {
                    uploadHandler(input, bytes)
                } catch (e: Throwable) {
                    handlerFailure.complete(e)
                    throw e
                }
            }
            onDownload(FilesCapability.Download) { input ->
                handlerRuns++
                val bytes = downloadBytes()
                WithBytes(FileHandle(input.fileId, "photo.jpg", "image/jpeg", bytes.sizeBytes), bytes)
            }
        }
    }
    private val peer = PeerInfo(provider.describe())

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun server(idle: Duration = 5.seconds) =
        NodeServer(provider, resolver, scope, descriptors = descriptors, streamIdleTimeout = idle, pumpContext = Dispatchers.IO)

    private fun transport(remote: IKoraNode, idle: Duration = 5.seconds, connector: FakeConnector = FakeConnector()): BinderPeerTransport {
        connector.autoConnect = remote to KoraAndroid.PROTOCOL_VERSION
        val pool = ConnectionPool(connector, scope, 5.seconds, 30.seconds)
        return BinderPeerTransport(
            pool,
            binderContext = Dispatchers.IO,
            streamScope = scope,
            streamIdleTimeout = idle,
            descriptors = descriptors,
            pumpContext = Dispatchers.IO,
        )
    }

    private fun content(size: Int) = Random(size).nextBytes(size)

    private fun uploadRequest(size: Long, id: String = "u1") = KoraRequest(
        id, FilesCapability.id.value, FilesCapability.version, FilesCapability.Upload.name,
        FilesCapability.Upload.encodeInput(CborCodec, UploadFileRequest("photo.jpg", "image/jpeg", size)),
    )

    private fun downloadRequest(id: String = "d1") = KoraRequest(
        id, FilesCapability.id.value, FilesCapability.version, FilesCapability.Download.name,
        FilesCapability.Download.encodeInput(CborCodec, FileIdRequest("f1")),
    )

    private fun code(response: KoraResponse): ErrorCode = assertInstanceOf(KoraResponse.Failure::class.java, response).error.code

    private fun code(failure: Throwable?): ErrorCode = assertInstanceOf(KoraException::class.java, failure).error.code

    private fun assertAllClosed() = assertEquals(emptyList<FakeDescriptor>(), descriptors.awaitAllClosed())

    /** A source whose closing can be observed. */
    private class TrackedInput(private val inner: InputStream) : InputStream() {
        val closed = CompletableDeferred<Unit>()

        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

        override fun close() {
            inner.close()
            closed.complete(Unit)
        }
    }

    private suspend fun readAll(bytes: ByteStream): ByteArray = withContext(Dispatchers.IO) { bytes.input.readAllBytes() }

    /** Whether [condition] holds within [millis]. */
    private fun awaitTrue(millis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    // ---- happy paths -----------------------------------------------------------------------------------------

    @Test
    fun `an upload reaches the provider whole and its answer comes back`() = runBlocking {
        val data = content(100_000)
        val transport = transport(LoopbackNode(server(), peerUid))

        val response = transport.callSending(peer, uploadRequest(100_000), ByteStream(100_000, ByteArrayInputStream(data)))

        val handle = FilesCapability.Upload.decodeOutput(CborCodec, assertInstanceOf(KoraResponse.Success::class.java, response).payload)
        assertEquals(100_000L, handle.sizeBytes)
        assertArrayEquals(data, uploaded.await())
        assertAllClosed()
    }

    @Test
    fun `a download carries the provider's bytes to the caller`() = runBlocking {
        val data = content(100_000)
        val source = TrackedInput(ByteArrayInputStream(data))
        downloadBytes = { ByteStream(100_000, source) }
        val transport = transport(LoopbackNode(server(), peerUid))

        val answer = transport.callReceiving(peer, downloadRequest())

        assertInstanceOf(KoraResponse.Success::class.java, answer.response)
        answer.use { assertArrayEquals(data, readAll(it.bytes!!)) }
        withTimeout(5.seconds) { source.closed.await() }
        assertAllClosed()
    }

    @Test
    fun `typed stream calls cross through the node, the transport and the server`() = runBlocking {
        val data = content(50_000)
        downloadBytes = { ByteStream(50_000, ByteArrayInputStream(data)) }
        val caller = KoraNode(
            "eus.bihurri.caller",
            peerDirectory = object : PeerDirectory {
                override val peers: StateFlow<List<PeerInfo>> = MutableStateFlow(listOf(peer))
            },
            transport = transport(LoopbackNode(server(), peerUid)),
        )

        val handle = caller.callSending(
            FilesCapability.Upload, UploadFileRequest("photo.jpg", "image/jpeg", 50_000),
            ByteStream(50_000, ByteArrayInputStream(data)), Target.Peer(peer.nodeId),
        )
        val downloaded = caller.callReceiving(FilesCapability.Download, FileIdRequest("f1"), Target.Peer(peer.nodeId))

        assertEquals(50_000L, handle.sizeBytes)
        assertArrayEquals(data, uploaded.await())
        assertEquals("f1", downloaded.output.fileId)
        downloaded.use { assertArrayEquals(data, readAll(it.bytes)) }
        assertAllClosed()
    }

    @Test
    fun `a provider that answers an upload without reading it ends the transfer`() = runBlocking {
        uploadHandler = { input, _ -> FileHandle("f1", input.name, input.mimeType, 0) }
        val transport = transport(LoopbackNode(server(), peerUid))

        val response = transport.callSending(peer, uploadRequest(100_000), ByteStream(100_000, ByteArrayInputStream(content(100_000))))

        assertInstanceOf(KoraResponse.Success::class.java, response)
        assertAllClosed()
    }

    // ---- refusals and failures -------------------------------------------------------------------------------

    @Test
    fun `an unknown caller is unauthorized, runs nothing and its descriptors are closed`() = runBlocking {
        val transport = transport(LoopbackNode(server(), strangerUid))

        val upload = transport.callSending(peer, uploadRequest(100_000), ByteStream(100_000, ByteArrayInputStream(content(100_000))))
        val download = transport.callReceiving(peer, downloadRequest())

        assertEquals(ErrorCode.UNAUTHORIZED, code(upload))
        assertEquals(ErrorCode.UNAUTHORIZED, code(download.response))
        assertNull(download.bytes)
        assertEquals(0, handlerRuns)
        assertAllClosed()
    }

    @Test
    fun `a handler failure is answered and every descriptor is closed`() = runBlocking {
        uploadHandler = { _, _ -> throw KoraException(KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "Over the limit")) }
        downloadBytes = { throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown file")) }
        val transport = transport(LoopbackNode(server(), peerUid))

        val upload = transport.callSending(peer, uploadRequest(100_000), ByteStream(100_000, ByteArrayInputStream(content(100_000))))
        val download = transport.callReceiving(peer, downloadRequest())

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(upload))
        assertEquals(ErrorCode.INVALID_PAYLOAD, code(download.response))
        assertNull(download.bytes)
        assertAllClosed()
    }

    @Test
    fun `an upload source that ends short is rejected on both sides`() = runBlocking {
        val transport = transport(LoopbackNode(server(), peerUid))

        val failure = runCatching {
            transport.callSending(peer, uploadRequest(10_000), ByteStream(10_000, ByteArrayInputStream(content(6_000))))
        }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_PAYLOAD, code(failure))
        val seen = withTimeout(5.seconds) { handlerFailure.await() }
        assertTrue(seen is IOException && seen.message!!.contains("ended after 6000 of 10000"), "provider saw: $seen")
        assertAllClosed()
    }

    @Test
    fun `an upload source that runs over is rejected on both sides`() = runBlocking {
        val transport = transport(LoopbackNode(server(), peerUid))

        val failure = runCatching {
            transport.callSending(peer, uploadRequest(10_000), ByteStream(10_000, ByteArrayInputStream(content(12_000))))
        }.exceptionOrNull()

        assertEquals(ErrorCode.INVALID_PAYLOAD, code(failure))
        val seen = withTimeout(5.seconds) { handlerFailure.await() }
        assertTrue(seen is IOException && seen.message!!.contains("runs over"), "provider saw: $seen")
        assertAllClosed()
    }

    @Test
    fun `a download that ends short or runs over fails the caller's read`() = runBlocking {
        val transport = transport(LoopbackNode(server(), peerUid))

        downloadBytes = { ByteStream(10_000, ByteArrayInputStream(content(6_000))) }
        val short = transport.callReceiving(peer, downloadRequest("short"))
        val shortFailure = short.use { runCatching { readAll(it.bytes!!) }.exceptionOrNull() }

        downloadBytes = { ByteStream(10_000, ByteArrayInputStream(content(12_000))) }
        val over = transport.callReceiving(peer, downloadRequest("over"))
        val overFailure = over.use { runCatching { readAll(it.bytes!!) }.exceptionOrNull() }

        assertTrue(shortFailure is IOException && shortFailure.message!!.contains("ended after 6000 of 10000"), "short: $shortFailure")
        assertTrue(overFailure is IOException && overFailure.message!!.contains("runs over"), "over: $overFailure")
        assertAllClosed()
    }

    // ---- time --------------------------------------------------------------------------------------------------

    @Test
    fun `an upload whose source stalls times out at the caller`() = runBlocking {
        val transport = transport(LoopbackNode(server(idle = 10.seconds), peerUid), idle = 300.milliseconds)
        val source = StalledInput(content(1_000))

        val failure = runCatching { transport.callSending(peer, uploadRequest(10_000), ByteStream(10_000, source)) }.exceptionOrNull()

        assertEquals(ErrorCode.TIMEOUT, code(failure))
        source.close() // the node closes the caller's bytes once the call returns
        assertAllClosed()
    }

    @Test
    fun `a provider that waits for upload bytes that never come times out`() = runBlocking {
        val transport = transport(LoopbackNode(server(idle = 300.milliseconds), peerUid), idle = 10.seconds)
        val source = StalledInput(content(1_000))

        val response = transport.callSending(peer, uploadRequest(10_000), ByteStream(10_000, source))

        assertEquals(ErrorCode.TIMEOUT, code(response))
        assertEquals(ErrorCode.TIMEOUT, code(handlerFailure.await()))
        source.close()
        assertAllClosed()
    }

    @Test
    fun `a download whose provider stalls times out at the caller's read, with the same idle timeout on both sides`() = runBlocking {
        // Both sides take the same streamIdleTimeout, as KoraAndroid.install gives them. The caller's read only starts
        // waiting once it has drained the pipe, after the provider's last byte; the provider side must not give up
        // first, or the caller sees a stream cut short instead of a timeout. Several rounds, as that is a race.
        repeat(5) { round ->
            val stalled = StalledInput(content(500))
            downloadBytes = { ByteStream(10_000, stalled) }
            val transport = transport(LoopbackNode(server(idle = 400.milliseconds), peerUid), idle = 400.milliseconds)

            val answer = transport.callReceiving(peer, downloadRequest("d$round"))
            val failure = answer.use { runCatching { readAll(it.bytes!!) }.exceptionOrNull() }

            assertTrue(failure is KoraException && failure.error.code == ErrorCode.TIMEOUT, "round $round: $failure")
            assertEquals(emptyList<FakeDescriptor>(), descriptors.awaitAllClosed(10_000))
            assertTrue(awaitTrue { stalled.closed }, "round $round: the provider's source is still open")
        }
    }

    @Test
    fun `a caller that stops reading a download does not pin the provider`() = runBlocking {
        val source = TrackedInput(ByteArrayInputStream(content(200_000)))
        downloadBytes = { ByteStream(200_000, source) }
        val transport = transport(LoopbackNode(server(idle = 300.milliseconds), peerUid))

        val answer = transport.callReceiving(peer, downloadRequest())

        withTimeout(5.seconds) { source.closed.await() }
        answer.close()
        assertAllClosed()
    }

    // ---- peer death and cancellation -----------------------------------------------------------------------------

    @Test
    fun `a lost peer ends an upload in flight with provider unavailable`() = runBlocking {
        val remote = FakeRemoteNode()
        val connector = FakeConnector()
        val transport = transport(remote, connector = connector)
        val upload = async(Dispatchers.Default) {
            runCatching { transport.callSending(peer, uploadRequest(100_000), ByteStream(100_000, ByteArrayInputStream(content(100_000)))) }
        }
        while (remote.sendingCalls.isEmpty()) Thread.sleep(5)

        connector.attempts.single().events.onLost("Peer process died")
        remote.sendingCalls.single().bytes!!.close() // the peer's process is gone, and its copy with it

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(upload.await().exceptionOrNull()))
        assertAllClosed()
    }

    @Test
    fun `cancelling an upload whose pump waits for room closes its descriptors`() = runBlocking {
        val remote = FakeRemoteNode()
        val transport = transport(remote)
        val upload = async(Dispatchers.Default) {
            transport.callSending(peer, uploadRequest(100_000), ByteStream(100_000, ByteArrayInputStream(content(100_000))))
        }
        while (remote.sendingCalls.isEmpty()) Thread.sleep(5)
        Thread.sleep(100) // the pump fills the pipe and waits for room

        upload.cancelAndJoin()
        remote.sendingCalls.single().bytes!!.close()

        assertAllClosed()
    }

    @Test
    fun `a stalled download read is ended by a lost peer`() = runBlocking {
        val remote = FakeRemoteNode()
        val connector = FakeConnector()
        var writer: java.io.OutputStream? = null
        remote.onCallReceiving = { request, callback ->
            val (readEnd, out) = descriptors.filled(ByteArray(100), keepWriting = true)
            writer = out
            val id = CborCodec.decode(KoraRequest.serializer(), request).id
            callback.onResponse(CborCodec.encode(KoraResponse.serializer(), KoraResponse.Success(id, byteArrayOf(1))), readEnd, 1_000)
        }
        val transport = transport(remote, connector = connector)
        val answer: StreamedResponse = transport.callReceiving(peer, downloadRequest())
        val reading = async(Dispatchers.Default) { runCatching { readAll(answer.bytes!!) }.exceptionOrNull() }
        Thread.sleep(100)

        connector.attempts.single().events.onLost("Peer process died")

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(reading.await()))
        writer!!.close()
        answer.close()
        assertAllClosed()
    }
}
