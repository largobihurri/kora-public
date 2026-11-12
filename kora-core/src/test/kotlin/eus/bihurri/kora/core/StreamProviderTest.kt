package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `onUpload` and `onDownload`, and how they fit with `on`, completeness and the test stubs. */
class StreamProviderTest {
    private fun refusal(register: ProviderBuilder<Blobs>.() -> Unit): String? {
        val node = KoraNode("n")
        val failure = assertThrows(IllegalArgumentException::class.java) { node.provide(Blobs, register) }
        assertEquals(emptyList<Any>(), node.describe().capabilities, "a refused registration registers nothing")
        return failure.message
    }

    @Test
    fun `on refuses a stream operation and the stream registrations refuse any other`() {
        assertEquals("Operation 'put' streams UPLOAD: register it with onUpload", refusal { on(Blobs.Put) { 0L } })
        assertEquals("Operation 'get' streams DOWNLOAD: register it with onDownload", refusal { on(Blobs.Get) { it } })
        assertEquals("Operation 'get' streams DOWNLOAD: register it with onDownload", refusal { onUpload(Blobs.Get) { name, _ -> name } })
        assertEquals("Operation 'drop' streams NONE: register it with on", refusal { onUpload(Blobs.Drop) { _, _ -> } })
        assertEquals(
            "Operation 'put' streams UPLOAD: register it with onUpload",
            refusal { onDownload(Blobs.Put) { WithBytes(0L, ByteStream(0, TrackedSource(""))) } },
        )
        assertEquals(
            "Operation 'drop' streams NONE: register it with on",
            refusal { onDownload(Blobs.Drop) { WithBytes(Unit, ByteStream(0, TrackedSource(""))) } },
        )
    }

    @Test
    fun `provide counts upload and download handlers toward completeness`() {
        assertEquals(
            "Provider of 'test.blobs' must register every operation and event of the capability; missing operations: get",
            refusal {
                onUpload(Blobs.Put) { _, _ -> 0L }
                on(Blobs.Drop) { }
            },
        )
        val node = KoraNode("n").also(BlobStore()::provideOn)
        assertEquals(listOf(Blobs.descriptor()), node.describe().capabilities)
    }

    @Test
    fun `stubs of stream operations are stream stubs`() = runTest {
        val audit = RecordingAudit()
        val node = KoraNode("n", audit = audit).apply { provideStubbed(Blobs) { on(Blobs.Drop) { } } }
        val source = TrackedSource("abc")

        val upload = thrownBy<KoraException> { node.callSending(Blobs.Put, "a", ByteStream(3, source)) }
        val download = thrownBy<KoraException> { node.callReceiving(Blobs.Get, "a") }
        val envelopeOnly = node.handle(PEER, request(Blobs.Put, "a"))

        assertEquals(KoraError(ErrorCode.UNKNOWN_OPERATION, "'put' is not implemented by this stub"), upload.error)
        assertEquals(KoraError(ErrorCode.UNKNOWN_OPERATION, "'get' is not implemented by this stub"), download.error)
        assertFailure(ErrorCode.PROVIDER_UNAVAILABLE, envelopeOnly)
        assertTrue(source.closed)
        assertEquals(
            listOf(ErrorCode.UNKNOWN_OPERATION, ErrorCode.UNKNOWN_OPERATION, ErrorCode.PROVIDER_UNAVAILABLE).map(AuditOutcome::Failed),
            audit.entries.map { it.outcome },
        )
    }
}
