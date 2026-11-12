package eus.bihurri.kora.contract

import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** A synthetic capability with one operation of each stream direction. */
private object Transfers : Capability(CapabilityId("test.transfers"), CapabilityVersion(1, 0)) {
    val Write = Scope("test.transfers.write")

    val Rename: OperationSpec<String, Unit> =
        operation("rename", String.serializer(), Unit.serializer(), Write, "No stream: the five-argument form.")

    val Explicit: OperationSpec<String, Unit> =
        operation("explicit", String.serializer(), Unit.serializer(), Write, "No stream, said explicitly.", StreamDirection.NONE)

    val Put: OperationSpec<String, Long> =
        operation("put", String.serializer(), Long.serializer(), Write, "Bytes from caller to provider.", StreamDirection.UPLOAD)

    val Get: OperationSpec<String, Long> =
        operation("get", String.serializer(), Long.serializer(), Write, "Bytes from provider to caller.", StreamDirection.DOWNLOAD)

    fun declareTwice(): OperationSpec<String, Long> =
        operation("put", String.serializer(), Long.serializer(), Write, "Duplicate.", StreamDirection.DOWNLOAD)
}

class StreamDirectionTest {
    @Test
    fun `an operation carries no stream unless it declares one`() {
        assertEquals(StreamDirection.NONE, Transfers.Rename.stream)
        assertEquals(StreamDirection.NONE, Transfers.Explicit.stream)
        assertEquals(StreamDirection.UPLOAD, Transfers.Put.stream)
        assertEquals(StreamDirection.DOWNLOAD, Transfers.Get.stream)
        assertEquals(listOf(Transfers.Rename, Transfers.Explicit, Transfers.Put, Transfers.Get), Transfers.operations)
        assertThrows(IllegalArgumentException::class.java) { Transfers.declareTwice() }
    }

    @Test
    fun `the direction stays off the wire`() {
        // Only id and version are advertised, and the envelope carries the operation by name.
        assertEquals(CapabilityDescriptor("test.transfers", CapabilityVersion(1, 0)), Transfers.descriptor())
        val request = KoraRequest("r1", "test.transfers", Transfers.version, "put", Transfers.Put.encodeInput(CborCodec, "a"))
        assertEquals(request, CborCodec.decode(KoraRequest.serializer(), CborCodec.encode(KoraRequest.serializer(), request)))
        assertEquals("a", Transfers.Put.decodeInput(CborCodec, request.payload))
    }

    @Test
    fun `among the built-ins only the transfers of files carry a stream`() {
        val streaming = Capabilities.all.flatMap { capability ->
            capability.operations.filter { it.stream != StreamDirection.NONE }.map { "${capability.id}.${it.name}" to it.stream }
        }
        assertEquals(listOf("files.upload" to StreamDirection.UPLOAD, "files.download" to StreamDirection.DOWNLOAD), streaming)
    }
}
