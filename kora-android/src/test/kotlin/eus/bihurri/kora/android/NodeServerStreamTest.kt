package eus.bihurri.kora.android

import android.os.DeadObjectException
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
import eus.bihurri.kora.core.WithBytes
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The service side of the stream methods with fakes and virtual time: who is served, what is refused before a byte is
 * read, the answers and their limits, and that every descriptor the server holds is closed. Bytes are written into the
 * pipes before the server reads them, so nothing blocks the test thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeServerStreamTest {
    private val peerUid = 10_200
    private val strangerUid = 10_400
    private val resolver = CallerResolver { uid -> if (uid == peerUid) Caller(CallerKind.PEER, "eus.bihurri.peer") else null }
    private val descriptors = FakeDescriptors()

    private var uploaded: ByteArray? = null
    private var uploadRuns = 0
    private var uploadFails: KoraException? = null
    private var uploadReads = true
    private var handleName = "photo.jpg"
    private var downloadRuns = 0
    private var downloadFails: KoraException? = null
    private var downloadSource: InputStream = ByteArrayInputStream(ByteArray(0))
    private var downloadSize = 0L
    private val internalErrors = mutableListOf<Throwable>()

    private val node = KoraNode("me", onInternalError = { internalErrors += it }).apply {
        provideStubbed(FilesCapability) {
            onUpload(FilesCapability.Upload) { input, bytes ->
                uploadRuns++
                uploadFails?.let { throw it }
                if (uploadReads) uploaded = bytes.input.readAllBytes() // catches nothing
                FileHandle("f1", input.name, input.mimeType, input.sizeBytes)
            }
            onDownload(FilesCapability.Download) { input ->
                downloadRuns++
                downloadFails?.let { throw it }
                WithBytes(FileHandle(input.fileId, handleName, "image/jpeg", downloadSize), ByteStream(downloadSize, downloadSource))
            }
        }
    }

    private fun TestScope.server(max: Int = PayloadLimits.MAX_ENVELOPE_BYTES, scope: CoroutineScope = backgroundScope) =
        NodeServer(
            node, resolver, scope,
            maxEnvelopeBytes = max,
            descriptors = descriptors,
            pumpContext = EmptyCoroutineContext,
            timeSource = testScheduler.timeSource,
        )

    private fun upload(size: Long, id: String = "u1") = CborCodec.encode(
        KoraRequest.serializer(),
        KoraRequest(id, FilesCapability.id.value, FilesCapability.version, FilesCapability.Upload.name, FilesCapability.Upload.encodeInput(CborCodec, UploadFileRequest("photo.jpg", "image/jpeg", size))),
    )

    private fun download(id: String = "d1", fileId: String = "f1") = CborCodec.encode(
        KoraRequest.serializer(),
        KoraRequest(id, FilesCapability.id.value, FilesCapability.version, FilesCapability.Download.name, FilesCapability.Download.encodeInput(CborCodec, FileIdRequest(fileId))),
    )

    private fun response(callback: FakeCallback): KoraResponse = CborCodec.decode(KoraResponse.serializer(), callback.responses.single())

    private fun response(answer: FakeStreamCallback.Answer): KoraResponse = CborCodec.decode(KoraResponse.serializer(), answer.response)

    private fun code(response: KoraResponse): ErrorCode = assertInstanceOf(KoraResponse.Failure::class.java, response).error.code

    /** Tracks whether the provider's source was closed. */
    private class TrackedInput(content: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(content)
        var closed = false

        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

        override fun close() {
            closed = true
        }
    }

    // ---- uploads ---------------------------------------------------------------------------------------------

    @Test
    fun `an upload is served with the bytes of its descriptor, which is then closed`() = runTest {
        val content = ByteArray(1_000) { it.toByte() }
        val (readEnd, _) = descriptors.filled(content)
        val callback = FakeCallback()

        server().callSending(peerUid, upload(1_000, "u7"), readEnd, 1_000, callback)
        runCurrent()

        val success = assertInstanceOf(KoraResponse.Success::class.java, response(callback))
        assertEquals("u7", success.id)
        assertEquals(1_000L, FilesCapability.Upload.decodeOutput(CborCodec, success.payload).sizeBytes)
        assertArrayEquals(content, uploaded)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `an upload from an unknown caller is unauthorized, runs nothing and closes the descriptor`() = runTest {
        val (readEnd, _) = descriptors.filled(ByteArray(3))
        val callback = FakeCallback()

        server().callSending(strangerUid, upload(3, "u9"), readEnd, 3, callback)
        runCurrent()

        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(callback))
        assertEquals("u9", failure.id)
        assertEquals(ErrorCode.UNAUTHORIZED, failure.error.code)
        assertEquals(0, uploadRuns)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `an upload envelope above the limit is refused and closes the descriptor`() = runTest {
        val (readEnd, _) = descriptors.filled(ByteArray(3))
        val callback = FakeCallback()

        server(max = 20).callSending(peerUid, upload(3), readEnd, 3, callback)
        runCurrent()

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(response(callback)))
        assertEquals(0, uploadRuns)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a malformed upload envelope is invalid payload and closes the descriptor`() = runTest {
        val (readEnd, _) = descriptors.filled(ByteArray(3))
        val callback = FakeCallback()

        server().callSending(peerUid, byteArrayOf(1, 2, 3), readEnd, 3, callback)
        runCurrent()

        assertEquals(ErrorCode.INVALID_PAYLOAD, code(response(callback)))
        assertEquals(0, uploadRuns)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `an upload without a descriptor or with a negative size is invalid payload`() = runTest {
        val server = server()
        val withoutBytes = FakeCallback()
        server.callSending(peerUid, upload(3, "a"), null, 3, withoutBytes)
        val (readEnd, _) = descriptors.filled(ByteArray(3))
        val negative = FakeCallback()
        server.callSending(peerUid, upload(3, "b"), readEnd, -1, negative)
        runCurrent()

        assertEquals(ErrorCode.INVALID_PAYLOAD, code(response(withoutBytes)))
        assertEquals(ErrorCode.INVALID_PAYLOAD, code(response(negative)))
        assertEquals("b", response(negative).id)
        assertEquals(0, uploadRuns)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a failing upload handler still closes the descriptor`() = runTest {
        uploadFails = KoraException(KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "Over the limit"))
        val (readEnd, _) = descriptors.filled(ByteArray(3))
        val callback = FakeCallback()

        server().callSending(peerUid, upload(3), readEnd, 3, callback)
        runCurrent()

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(response(callback)))
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `an upload whose descriptor carries fewer or more bytes than declared answers invalid payload`() = runTest {
        val server = server()
        val (shortEnd, _) = descriptors.filled(ByteArray(600))
        val short = FakeCallback()
        server.callSending(peerUid, upload(1_000, "short"), shortEnd, 1_000, short)
        val (overEnd, _) = descriptors.filled(ByteArray(1_200))
        val over = FakeCallback()
        server.callSending(peerUid, upload(1_000, "over"), overEnd, 1_000, over)
        runCurrent()

        val shortFailure = assertInstanceOf(KoraResponse.Failure::class.java, response(short))
        val overFailure = assertInstanceOf(KoraResponse.Failure::class.java, response(over))
        assertEquals(KoraError(ErrorCode.INVALID_PAYLOAD, "Byte stream ended after 600 of 1000 bytes"), shortFailure.error)
        assertEquals(KoraError(ErrorCode.INVALID_PAYLOAD, "Byte stream runs over its 1000 bytes"), overFailure.error)
        assertEquals(listOf("short", "over"), listOf(shortFailure.id, overFailure.id))
        assertEquals(emptyList<Throwable>(), internalErrors, "a malformed stream is not a provider bug")
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `an upload answered without reading leaves no watchdog on the service scope`() = runTest {
        uploadReads = false
        val serviceJob = SupervisorJob()
        val service = CoroutineScope(serviceJob + StandardTestDispatcher(testScheduler))
        val (readEnd, _) = descriptors.filled(ByteArray(3))
        val callback = FakeCallback()

        server(scope = service).callSending(peerUid, upload(3), readEnd, 3, callback)
        runCurrent()
        val left = serviceJob.children.toList()
        service.cancel() // a guard left behind would otherwise keep the scheduler busy for ever

        assertInstanceOf(KoraResponse.Success::class.java, response(callback))
        assertEquals(emptyList<Job>(), left)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `an upload the server can no longer serve still closes its descriptor`() = runTest {
        val closed = CoroutineScope(SupervisorJob()).apply { cancel() }
        val (readEnd, _) = descriptors.filled(ByteArray(3))

        server(scope = closed).callSending(peerUid, upload(3), readEnd, 3, FakeCallback())

        assertEquals(emptyList<FakeDescriptor>(), descriptors.awaitAllClosed()) // the cancelled job completes on another thread
        assertEquals(0, uploadRuns)
    }

    @Test
    fun `the plain call of a stream operation is provider unavailable`() = runTest {
        val server = server()
        val sending = FakeCallback()
        val receiving = FakeCallback()

        server.call(peerUid, upload(3, "plain"), sending)
        server.call(peerUid, download("plain2"), receiving)
        runCurrent()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(response(sending)))
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, code(response(receiving)))
        assertEquals(0, uploadRuns)
    }

    // ---- downloads -------------------------------------------------------------------------------------------

    @Test
    fun `a download answers its bytes through a pipe and closes the provider's source`() = runTest {
        val content = ByteArray(1_000) { (it * 3).toByte() }
        val source = TrackedInput(content)
        downloadSource = source
        downloadSize = 1_000
        val callback = FakeStreamCallback()

        server().callReceiving(peerUid, download("d7"), callback)
        runCurrent()

        val answer = callback.answers.single()
        val success = assertInstanceOf(KoraResponse.Success::class.java, response(answer))
        assertEquals("d7", success.id)
        assertEquals(1_000L, answer.sizeBytes)
        assertArrayEquals(content, descriptors.input(answer.bytes!!).use { it.readAllBytes() })
        assertTrue(source.closed)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a failed download answers without a descriptor`() = runTest {
        downloadFails = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown file"))
        val callback = FakeStreamCallback()

        server().callReceiving(peerUid, download("d8"), callback)
        runCurrent()

        val answer = callback.answers.single()
        assertEquals(ErrorCode.INVALID_PAYLOAD, code(response(answer)))
        assertEquals("d8", response(answer).id)
        assertNull(answer.bytes)
        assertEquals(0L, answer.sizeBytes)
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `a download from an unknown caller is unauthorized without a descriptor`() = runTest {
        val callback = FakeStreamCallback()

        server().callReceiving(strangerUid, download("d9"), callback)
        runCurrent()

        val answer = callback.answers.single()
        assertEquals(ErrorCode.UNAUTHORIZED, code(response(answer)))
        assertEquals("d9", response(answer).id)
        assertNull(answer.bytes)
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `malformed and oversized download envelopes are refused without a descriptor`() = runTest {
        val malformed = FakeStreamCallback()
        val oversized = FakeStreamCallback()

        server().callReceiving(peerUid, byteArrayOf(1, 2, 3), malformed)
        server(max = 20).callReceiving(peerUid, download(), oversized)
        runCurrent()

        assertEquals(ErrorCode.INVALID_PAYLOAD, code(response(malformed.answers.single())))
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(response(oversized.answers.single())))
        assertNull(malformed.answers.single().bytes)
        assertNull(oversized.answers.single().bytes)
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `a download request above the envelope limit is refused before decoding and runs no handler`() = runTest {
        handleName = "a"
        val callback = FakeStreamCallback()

        server(max = 200).callReceiving(peerUid, download("big", fileId = "f".repeat(300)), callback)
        runCurrent()

        val answer = callback.answers.single()
        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(answer))
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.error.code)
        assertEquals("", failure.id, "refused before the request is decoded")
        assertEquals(0, downloadRuns)
        assertNull(answer.bytes)
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `a download answer above the limit is payload too large without a descriptor and closes the bytes`() = runTest {
        handleName = "x".repeat(2_000)
        val source = TrackedInput(ByteArray(3))
        downloadSource = source
        downloadSize = 3
        val callback = FakeStreamCallback()

        server(max = 1_000).callReceiving(peerUid, download("big"), callback)
        runCurrent()

        val answer = callback.answers.single()
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, code(response(answer)))
        assertEquals("big", response(answer).id)
        assertNull(answer.bytes)
        assertTrue(source.closed)
        assertTrue(descriptors.opened.isEmpty())
    }

    @Test
    fun `a download whose caller is gone closes the bytes and the pipe`() = runTest {
        val source = TrackedInput(ByteArray(3))
        downloadSource = source
        downloadSize = 3
        val callback = FakeStreamCallback().apply { failWith = DeadObjectException() }

        server().callReceiving(peerUid, download(), callback)
        runCurrent()

        assertTrue(source.closed)
        assertFalse(descriptors.opened.isEmpty())
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a download that cannot open a pipe is internal without a descriptor and closes the bytes`() = runTest {
        descriptors.failCreate = java.io.IOException("Too many open files")
        val source = TrackedInput(ByteArray(3))
        downloadSource = source
        downloadSize = 3
        val callback = FakeStreamCallback()

        server().callReceiving(peerUid, download("d"), callback)
        runCurrent()

        assertEquals(ErrorCode.INTERNAL, code(response(callback.answers.single())))
        assertNull(callback.answers.single().bytes)
        assertTrue(source.closed)
    }
}
