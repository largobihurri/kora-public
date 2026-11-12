package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.files.FileHandle
import eus.bihurri.kora.contract.files.FileIdRequest
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.files.UploadFileRequest
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `files` capability: the streams it declares, the wire-format checks of its requests and what they leave to the provider. */
class FilesContractTest {
    private fun rejected(build: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    // ---- The capability ---------------------------------------------------------------------------------------

    @Test
    fun `files has upload, download and discard at version 1_0, no event, and separate read and write scopes`() {
        assertEquals(listOf("upload", "download", "discard"), FilesCapability.operations.map { it.name })
        assertEquals(emptyList<String>(), FilesCapability.events.map { it.name })
        assertEquals(CapabilityDescriptor("files", CapabilityVersion(1, 0)), FilesCapability.descriptor())
        assertEquals(Scope("files.read"), FilesCapability.ReadScope)
        assertEquals(Scope("files.write"), FilesCapability.WriteScope)
        assertEquals(FilesCapability.WriteScope, FilesCapability.Upload.scope)
        assertEquals(FilesCapability.ReadScope, FilesCapability.Download.scope)
        assertEquals(FilesCapability.WriteScope, FilesCapability.Discard.scope)
        assertSame(FilesCapability.Upload, FilesCapability.findOperation("upload"))
        assertSame(FilesCapability.Download, FilesCapability.findOperation("download"))
        assertSame(FilesCapability.Discard, FilesCapability.findOperation("discard"))
    }

    @Test
    fun `upload sends bytes to the provider, download brings them back, and discard carries none`() {
        assertEquals(StreamDirection.UPLOAD, FilesCapability.Upload.stream)
        assertEquals(StreamDirection.DOWNLOAD, FilesCapability.Download.stream)
        assertEquals(StreamDirection.NONE, FilesCapability.Discard.stream)
    }

    @Test
    fun `upload and download answer a file handle without bytes, and discard answers nothing`() {
        val handle = FileHandle("file-1", "photo.jpg", "image/jpeg", 5_000_000_000)

        for (codec in listOf(CborCodec, JsonCodec)) {
            assertEquals(handle, FilesCapability.Upload.decodeOutput(codec, FilesCapability.Upload.encodeOutput(codec, handle)))
            assertEquals(handle, FilesCapability.Download.decodeOutput(codec, FilesCapability.Download.encodeOutput(codec, handle)))
        }
        assertEquals(
            """{"fileId":"file-1","name":"photo.jpg","mimeType":"image/jpeg","sizeBytes":5000000000}""",
            FilesCapability.Download.encodeOutput(JsonCodec, handle).decodeToString(),
        )
        assertEquals("{}", FilesCapability.Discard.encodeOutput(JsonCodec, Unit).decodeToString())
    }

    @Test
    fun `the descriptions say what a handle is for and when a file is too large`() {
        val upload = FilesCapability.Upload.description
        assertTrue("attachments" in upload && "add" in upload, upload)
        assertTrue("payload_too_large" in upload, upload)
        assertTrue("open" in FilesCapability.Download.description, FilesCapability.Download.description)
        assertTrue("fileId" in FilesCapability.Discard.description, FilesCapability.Discard.description)
    }

    // ---- Requests ---------------------------------------------------------------------------------------------

    @Test
    fun `an upload declares its exact size, which is not negative`() {
        assertEquals(0L, UploadFileRequest("empty.txt", "text/plain", 0).sizeBytes)
        assertEquals(5_000_000_000L, UploadFileRequest("photo.jpg", "image/jpeg", 5_000_000_000).sizeBytes)
        rejected { UploadFileRequest("photo.jpg", "image/jpeg", -1) }
        rejected { UploadFileRequest("photo.jpg", "image/jpeg", Long.MIN_VALUE) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the size of an upload declares that it is not negative, so a schema shows it`() {
        val descriptor = FilesCapability.Upload.inputSerializer.descriptor
        val range = descriptor.getElementAnnotations(descriptor.getElementIndex("sizeBytes"))
            .filterIsInstance<IntegerRange>()
            .singleOrNull()

        assertEquals(0L, range?.min)
        assertEquals(Long.MAX_VALUE, range?.max)
    }

    @Test
    fun `a file is named by its id, which is not blank`() {
        assertEquals("file-1", FileIdRequest("file-1").fileId)
        rejected { FileIdRequest("") }
        rejected { FileIdRequest("  ") }
    }

    @Test
    fun `the name, the MIME type and the size limit are left to the provider`() {
        assertEquals("", UploadFileRequest("", "image/jpeg", 1).name)
        assertEquals(" ", UploadFileRequest("photo.jpg", " ", 1).mimeType)
        assertEquals("not a type", UploadFileRequest("photo.jpg", "not a type", 1).mimeType)
        assertEquals(Long.MAX_VALUE, UploadFileRequest("photo.jpg", "image/jpeg", Long.MAX_VALUE).sizeBytes)
    }

    // ---- The wire ---------------------------------------------------------------------------------------------

    @Test
    fun `a request decoded from the wire is validated too`() {
        rejectedOnTheWire(FilesCapability.Upload, """{"name":"photo.jpg","mimeType":"image/jpeg","sizeBytes":-1}""")
        rejectedOnTheWire(FilesCapability.Download, """{"fileId":" "}""")
        rejectedOnTheWire(FilesCapability.Discard, """{"fileId":""}""")
        undecodable(FilesCapability.Upload, """{"name":"photo.jpg","mimeType":"image/jpeg"}""")
        undecodable(FilesCapability.Download, "{}")
        assertEquals(
            UploadFileRequest("photo.jpg", "image/jpeg", 5_000_000_000),
            FilesCapability.Upload.decodeInput(
                JsonCodec,
                """{"name":"photo.jpg","mimeType":"image/jpeg","sizeBytes":5000000000}""".encodeToByteArray(),
            ),
        )
        assertEquals(FileIdRequest("file-1"), FilesCapability.Discard.decodeInput(JsonCodec, """{"fileId":"file-1"}""".encodeToByteArray()))
    }
}
