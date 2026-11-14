package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.files.FileIdRequest
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.files.UploadFileRequest
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The sample's `files@1.0` provider, called through a real node (local calls, so the caller is always the node). */
class SampleFilesTest {
    private val logs = mutableListOf<String>()
    private val files = SampleFiles(maxBytes = 1_000) { synchronized(logs) { logs += it } }
    private val node = KoraNode("provider").apply { provideSampleFiles(files) }

    private fun upload(name: String, content: ByteArray) = runBlocking {
        node.callSending(
            FilesCapability.Upload,
            UploadFileRequest(name, "application/octet-stream", content.size.toLong()),
            ByteStream(content.size.toLong(), ByteArrayInputStream(content)),
        )
    }

    private fun download(fileId: String): ByteArray = runBlocking {
        node.callReceiving(FilesCapability.Download, FileIdRequest(fileId)).use { it.bytes.input.readAllBytes() }
    }

    @Test
    fun `an uploaded file downloads with the same bytes and description`() {
        val content = ByteArray(777) { it.toByte() }

        val handle = upload("photo.jpg", content)
        val answer = runBlocking { node.callReceiving(FilesCapability.Download, FileIdRequest(handle.fileId)) }

        assertEquals("photo.jpg", handle.name)
        assertEquals(777L, handle.sizeBytes)
        assertEquals(handle, answer.output)
        assertArrayEquals(content, answer.use { it.bytes.input.readAllBytes() })
        assertTrue(logs.any { it.contains(SampleFiles.sha256(content)) }, logs.toString())
    }

    @Test
    fun `an empty file round-trips`() {
        val handle = upload("empty.txt", ByteArray(0))

        assertEquals(0L, handle.sizeBytes)
        assertArrayEquals(ByteArray(0), download(handle.fileId))
    }

    @Test
    fun `a discarded handle is an unknown id`() {
        val handle = upload("note.txt", byteArrayOf(1, 2, 3))

        runBlocking { node.call(FilesCapability.Discard, FileIdRequest(handle.fileId)) }
        val error = assertThrows<KoraException> { download(handle.fileId) }

        assertEquals(ErrorCode.INVALID_PAYLOAD, error.error.code)
    }

    @Test
    fun `an upload over the limit is payload_too_large before a byte is read`() {
        val neverRead = object : InputStream() {
            override fun read(): Int = throw AssertionError("read an upload over the limit")
        }

        val error = assertThrows<KoraException> {
            runBlocking {
                node.callSending(
                    FilesCapability.Upload,
                    UploadFileRequest("big.bin", "application/octet-stream", 1_001),
                    ByteStream(1_001, neverRead),
                )
            }
        }

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, error.error.code)
    }

    @Test
    fun `a blank name is invalid_payload`() {
        val error = assertThrows<KoraException> { upload(" ", byteArrayOf(1)) }

        assertEquals(ErrorCode.INVALID_PAYLOAD, error.error.code)
    }

    @Test
    fun `the silent file blocks its reader until the receiver closes it, and logs the close`() = runBlocking {
        val answer = node.callReceiving(FilesCapability.Download, FileIdRequest(SampleFiles.SILENT_ID))
        val read = async(Dispatchers.IO) { runCatching { answer.bytes.input.read() } }

        delay(200)
        assertTrue(read.isActive, "the silent file answered a byte")
        answer.close()
        val result = withTimeout(2_000) { read.await() }

        assertTrue(result.exceptionOrNull() is IOException, "read after close: $result")
        assertTrue(logs.any { it.startsWith("source ${SampleFiles.SILENT_ID} closed") }, logs.toString())
    }

    @Test
    fun `the slow file yields bytes in small steps`() = runBlocking {
        node.callReceiving(FilesCapability.Download, FileIdRequest(SampleFiles.SLOW_ID)).use { answer ->
            val buffer = ByteArray(64 * 1024)
            val count = answer.bytes.input.read(buffer)

            assertEquals(SampleFiles.SLOW_CHUNK_BYTES, count)
            assertTrue(answer.output.sizeBytes > 1_000_000, "slow file of ${answer.output.sizeBytes} bytes")
        }
    }
}
