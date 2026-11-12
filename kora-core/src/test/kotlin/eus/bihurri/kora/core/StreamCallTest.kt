package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.StreamDirection
import eus.bihurri.kora.contract.SubscribeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.seconds

// ---- Fixtures shared by the Stream*Test files ------------------------------------------------------------------

/** A synthetic capability with one operation of each stream direction. */
internal object Blobs : Capability(CapabilityId("test.blobs"), CapabilityVersion(1, 0)) {
    val Read = Scope("test.blobs.read")
    val Write = Scope("test.blobs.write")

    /** Stores the uploaded bytes under a name and answers how many it read. */
    val Put: OperationSpec<String, Long> =
        operation("put", String.serializer(), Long.serializer(), Write, "Stores bytes.", StreamDirection.UPLOAD)

    /** Answers the name and, as the stream, the bytes stored under it. */
    val Get: OperationSpec<String, String> =
        operation("get", String.serializer(), String.serializer(), Read, "Reads bytes.", StreamDirection.DOWNLOAD)

    /** Forgets a name; no stream. */
    val Drop: OperationSpec<String, Unit> = operation("drop", String.serializer(), Unit.serializer(), Write, "Forgets.")
}

/** A source of bytes that counts how often it was closed. */
internal class TrackedSource(text: String) : ByteArrayInputStream(text.encodeToByteArray()) {
    var closes = 0
        private set

    val closed: Boolean get() = closes > 0

    override fun close() {
        closes++
        super.close()
    }
}

internal fun ByteStream.text(): String = input.readBytes().decodeToString()

/** Provides [Blobs] from a map and records what the handlers saw. The name `full` fails before reading. */
internal class BlobStore {
    val content = mutableMapOf<String, String>()
    val callers = mutableListOf<Caller>()
    val uploads = mutableListOf<ByteStream>()
    val downloads = mutableListOf<TrackedSource>()

    fun provideOn(node: KoraNode) = node.provide(Blobs) {
        onUpload(Blobs.Put) { name, bytes ->
            callers += caller
            uploads += bytes
            if (name == "full") throw KoraException(KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "No room for '$name'"))
            val text = bytes.text()
            content[name] = text
            text.length.toLong()
        }
        onDownload(Blobs.Get) { name ->
            callers += caller
            val text = content[name] ?: throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown '$name'"))
            val source = TrackedSource(text).also { downloads += it }
            WithBytes(name, ByteStream(text.length.toLong(), source))
        }
        on(Blobs.Drop) { name ->
            callers += caller
            content -= name
        }
    }
}

/** Runs [block] and returns what it threw, failing unless it is a [T]. */
internal inline fun <reified T : Throwable> thrownBy(block: () -> Unit): T {
    val failure = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }
    return assertInstanceOf(T::class.java, failure, "expected ${T::class.simpleName}")
}

// ---- Tests -----------------------------------------------------------------------------------------------------

/** [KoraNode.callSending], [KoraNode.callReceiving] and their inbound counterparts. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamCallTest {
    private val audit = RecordingAudit()
    private val access = FakeExternalAccess(enabled = true)
    private val internalErrors = mutableListOf<Throwable>()
    private val store = BlobStore()
    private val self = Caller(CallerKind.SELF, "files")
    private val node = KoraNode(
        nodeId = "files",
        policy = DefaultCallerPolicy(access),
        audit = audit,
        clock = FakeClock(now = 7_000),
        onInternalError = { internalErrors += it },
    ).also(store::provideOn)

    private fun entry(caller: Caller, member: String, outcome: AuditOutcome) =
        AuditEntry(7_000, caller, "test.blobs", member, AuditKind.CALL, outcome, 0)

    @Test
    fun `a local upload hands the bytes to the handler as SELF and closes them`() = runTest {
        val source = TrackedSource("hello")
        val bytes = ByteStream(5, source)

        val read = node.callSending(Blobs.Put, "greeting", bytes)

        assertEquals(5L, read)
        assertEquals(mapOf("greeting" to "hello"), store.content)
        assertEquals(listOf(self), store.callers)
        assertSame(bytes, store.uploads.single())
        assertEquals(1, source.closes)
        assertEquals(listOf(entry(self, "put", AuditOutcome.Ok)), audit.entries)
    }

    @Test
    fun `a local download answers the output and bytes that the caller reads and closes`() = runTest {
        store.content["song"] = "la la"

        val result = node.callReceiving(Blobs.Get, "song")

        assertEquals("song", result.output)
        assertEquals(5L, result.bytes.sizeBytes)
        assertEquals("la la", result.bytes.text())
        assertFalse(store.downloads.single().closed, "the caller owns the bytes it received")
        result.close()
        assertTrue(store.downloads.single().closed)
        assertEquals(listOf(self), store.callers)
        assertEquals(listOf(entry(self, "get", AuditOutcome.Ok)), audit.entries)
    }

    @Test
    fun `a direction that does not match the operation is a programming error`() = runTest {
        val sources = List(2) { TrackedSource("x") }

        thrownBy<IllegalArgumentException> { node.callSending(Blobs.Get, "a", ByteStream(1, sources[0])) }
        thrownBy<IllegalArgumentException> { node.callSending(Blobs.Drop, "a", ByteStream(1, sources[1])) }
        thrownBy<IllegalArgumentException> { node.callReceiving(Blobs.Put, "a") }
        thrownBy<IllegalArgumentException> { node.callReceiving(Blobs.Drop, "a") }
        thrownBy<IllegalArgumentException> { node.call(Blobs.Put, "a") }
        thrownBy<IllegalArgumentException> { node.call(Blobs.Get, "a") }

        assertTrue(sources.all { it.closed }, "callSending closes the bytes on every path")
        assertEquals(emptyList<Caller>(), store.callers)
        assertEquals(emptyList<AuditEntry>(), audit.entries)
    }

    @Test
    fun `a stream call is not bound by the call timeout`() = runTest {
        val slow = KoraNode("slow", callTimeout = 1.seconds).apply {
            provide(Blobs) {
                onUpload(Blobs.Put) { _, bytes ->
                    delay(30.seconds)
                    bytes.text().length.toLong()
                }
                onDownload(Blobs.Get) { name ->
                    delay(30.seconds)
                    WithBytes(name, ByteStream(2, TrackedSource("ok")))
                }
                on(Blobs.Drop) { }
            }
        }

        assertEquals(3L, slow.callSending(Blobs.Put, "a", ByteStream(3, TrackedSource("abc"))))
        slow.callReceiving(Blobs.Get, "b").use { assertEquals("b" to "ok", it.output to it.bytes.text()) }
        assertEquals(60_000L, currentTime)
    }

    @Test
    fun `a peer behind a transport without streams is unavailable before anything is sent`() = runTest {
        val transport = CountingTransport()
        val directory = object : PeerDirectory {
            override val peers: StateFlow<List<PeerInfo>> =
                MutableStateFlow(listOf(PeerInfo(NodeDescriptor("far", "far", listOf(Blobs.descriptor())))))
        }
        val near = KoraNode("near", audit = audit, peerDirectory = directory, transport = transport)
        val source = TrackedSource("abc")

        val upload = thrownBy<KoraException> { near.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        val download = thrownBy<KoraException> { near.callReceiving(Blobs.Get, "a") }

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, upload.error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, download.error.code)
        assertEquals(0, transport.calls)
        assertEquals(3, source.available(), "not a byte was read")
        assertTrue(source.closed)
        assertEquals(emptyList<AuditEntry>(), audit.entries)
    }

    @Test
    fun `cancelling a stream call propagates, is not audited and closes the upload`() = runTest {
        val started = CompletableDeferred<Unit>()
        val waiting = KoraNode("n", audit = audit, onInternalError = { internalErrors += it }).apply {
            provide(Blobs) {
                onUpload(Blobs.Put) { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }
                onDownload(Blobs.Get) { awaitCancellation() }
                on(Blobs.Drop) { }
            }
        }
        val source = TrackedSource("abc")

        val upload = launch { waiting.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        started.await()
        upload.cancel()
        upload.join()
        val download = launch { waiting.callReceiving(Blobs.Get, "a") }
        testScheduler.runCurrent()
        download.cancel()
        download.join()

        assertTrue(upload.isCancelled && download.isCancelled)
        assertTrue(source.closed)
        assertEquals(emptyList<AuditEntry>(), audit.entries)
        assertEquals(emptyList<Throwable>(), internalErrors)
    }

    @Test
    fun `inbound streams run the audited pipeline for the caller the transport names`() = runTest {
        val source = TrackedSource("abc")

        val sent = node.handleSending(PEER, request(Blobs.Put, "a", id = "u1"), ByteStream(3, source))
        val received = node.handleReceiving(PEER, request(Blobs.Get, "a", id = "d1"))

        assertEquals("u1", sent.id)
        assertEquals(3L, sent.output(Blobs.Put))
        assertTrue(source.closed)
        assertEquals("d1", received.response.id)
        assertEquals("a", received.response.output(Blobs.Get))
        assertEquals("abc", received.bytes?.text())
        received.close()
        assertTrue(store.downloads.single().closed)
        assertEquals(listOf(PEER, PEER), store.callers)
        assertEquals(listOf(entry(PEER, "put", AuditOutcome.Ok), entry(PEER, "get", AuditOutcome.Ok)), audit.entries)
    }

    @Test
    fun `an upload that is refused or fails is audited and its bytes are closed on every path`() = runTest {
        val sources = List(5) { TrackedSource("abc") }
        val unknown = KoraRequest("r", "test.nothing", Blobs.version, "put", ByteArray(0))
        val malformed = KoraRequest("r", "test.blobs", Blobs.version, "put", ByteArray(0))

        val responses = listOf(
            node.handleSending(AGENT, request(Blobs.Put, "a"), ByteStream(3, sources[0])),
            node.handleSending(PEER, unknown, ByteStream(3, sources[1])),
            node.handleSending(PEER, malformed, ByteStream(3, sources[2])),
            node.handleSending(PEER, request(Blobs.Put, "full"), ByteStream(3, sources[3])),
            node.handleSending(PEER, request(Blobs.Drop, "a"), ByteStream(3, sources[4])),
        )

        assertEquals(
            listOf(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNKNOWN_CAPABILITY, ErrorCode.INVALID_PAYLOAD,
                ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.INVALID_PAYLOAD,
            ),
            responses.map { it.errorCode() },
        )
        assertTrue(sources.all { it.closes == 1 }, "every upload is closed once")
        assertEquals(listOf(PEER), store.callers, "only the handler of 'full' ran")
        assertEquals(listOf(AGENT to Blobs.Write), access.denied)
        assertEquals(
            listOf(
                ErrorCode.UNAUTHORIZED, ErrorCode.UNKNOWN_CAPABILITY, ErrorCode.INVALID_PAYLOAD,
                ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.INVALID_PAYLOAD,
            ).map(AuditOutcome::Failed),
            audit.entries.map { it.outcome },
        )
    }

    @Test
    fun `a download that is refused or fails answers without bytes`() = runTest {
        val refused = node.handleReceiving(AGENT, request(Blobs.Get, "a"))
        val failed = node.handleReceiving(PEER, request(Blobs.Get, "missing"))
        val mismatched = node.handleReceiving(PEER, request(Blobs.Drop, "a"))
        val local = thrownBy<KoraException> { node.callReceiving(Blobs.Get, "missing") }

        assertEquals(ErrorCode.UNAUTHORIZED, refused.response.errorCode())
        assertEquals(ErrorCode.INVALID_PAYLOAD, failed.response.errorCode())
        assertEquals(ErrorCode.INVALID_PAYLOAD, mismatched.response.errorCode())
        assertNull(refused.bytes)
        assertNull(failed.bytes)
        assertNull(mismatched.bytes)
        assertEquals(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown 'missing'"), local.error)
        assertEquals(listOf(PEER, self), store.callers)
        assertEquals(4, audit.entries.size)
    }
}

/** A plain [PeerTransport] that counts the calls it was asked to make. */
private class CountingTransport : PeerTransport {
    var calls = 0
        private set

    override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
        calls++
        return KoraResponse.Failure(request.id, KoraError(ErrorCode.INTERNAL, "unexpected"))
    }

    override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = emptyFlow()
}
