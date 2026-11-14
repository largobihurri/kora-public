package eus.bihurri.kora.sample.consumer

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.files.FileIdRequest
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.files.UploadFileRequest
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The on-device checks of the Binder byte streams (`samples/stream-checks.sh` runs them): each one calls the sample
 * provider's `files@1.0` ([NAMES]) and reports one line, `CHECK <name> PASS|FAIL <detail>`, through [log]. The provider
 * logs its own side (uploads, and when a download's source is closed) under the same logcat tag.
 */
internal class StreamChecks(private val node: KoraNode, private val log: (String) -> Unit) {
    suspend fun run(name: String) {
        val detail = try {
            when (name) {
                "roundtrip" -> roundtrip()
                "upload-stall" -> uploadStall()
                "read-idle" -> readIdle()
                "read-close" -> readClose()
                "unread" -> unread()
                "download-slow" -> downloadSlow()
                "upload-slow" -> uploadSlow()
                else -> Result(false, "unknown check; one of $NAMES")
            }
        } catch (e: Exception) {
            Result(false, "unexpected ${describe(e)}")
        }
        log("CHECK $name ${if (detail.passed) "PASS" else "FAIL"} ${detail.text}")
    }

    private class Result(val passed: Boolean, val text: String)

    /** A file the caller holds reaches the other app and comes back byte for byte; a discarded handle is gone. */
    private suspend fun roundtrip(): Result {
        val notes = mutableListOf<String>()
        for (size in listOf(0, 3 * 1024 * 1024 + 1)) {
            val content = Random.nextBytes(size)
            val started = System.nanoTime()
            val handle = upload("roundtrip-$size.bin", content)
            val back = withContext(Dispatchers.IO) {
                node.callReceiving(FilesCapability.Download, FileIdRequest(handle.fileId)).use { it.bytes.input.readBytes() }
            }
            val ms = elapsedMs(started)
            if (handle.sizeBytes != size.toLong() || sha256(back) != sha256(content)) {
                return Result(false, "size $size: handle ${handle.sizeBytes} bytes, ${back.size} bytes back, sha differs")
            }
            node.call(FilesCapability.Discard, FileIdRequest(handle.fileId))
            val afterDiscard = errorOf { node.callReceiving(FilesCapability.Download, FileIdRequest(handle.fileId)).close() }
            if (afterDiscard != ErrorCode.INVALID_PAYLOAD.wireName) {
                return Result(false, "size $size: download after discard answered $afterDiscard")
            }
            notes += "$size bytes up and down in $ms ms sha256=${sha256(content).take(16)}"
        }
        return Result(true, notes.joinToString("; "))
    }

    /** The provider never reads: the sending pump waits in a full pipe until the idle timeout closes it. */
    private suspend fun uploadStall(): Result {
        val started = System.nanoTime()
        val code = errorOf { upload("stall", ByteArray(4 * 1024 * 1024)) }
        val ms = elapsedMs(started)
        return Result(code == ErrorCode.TIMEOUT.wireName && ms < 30_000, "answered $code after $ms ms")
    }

    /** The provider's source yields nothing: the read blocked on the pipe ends when the idle timeout closes it. */
    private suspend fun readIdle(): Result {
        val started = System.nanoTime()
        val download = node.callReceiving(FilesCapability.Download, FileIdRequest(SILENT_ID))
        val outcome = withContext(Dispatchers.IO) { download.use { runCatching { it.bytes.input.read() } } }
        val ms = elapsedMs(started)
        val error = outcome.exceptionOrNull()
        return Result(error != null && ms < 30_000, "read ended after $ms ms with ${error?.let(::describe) ?: outcome.getOrNull()}")
    }

    /** A read blocked on the pipe ends promptly when another thread closes the stream. */
    private suspend fun readClose(): Result = coroutineScope {
        val download = node.callReceiving(FilesCapability.Download, FileIdRequest(SILENT_ID))
        val read = async(Dispatchers.IO) { runCatching { download.bytes.input.read() } }
        delay(2_000)
        if (!read.isActive) return@coroutineScope Result(false, "the read ended before the close: ${read.await()}")
        val closedAt = System.nanoTime()
        withContext(Dispatchers.IO) { download.close() }
        val outcome = withTimeoutOrNull(5_000) { read.await() }
        val ms = elapsedMs(closedAt)
        Result(outcome != null, "read ended ${if (outcome == null) "not within" else "$ms ms after"} the close: ${outcome?.exceptionOrNull()?.let(::describe) ?: outcome?.getOrNull()}")
    }

    /** The receiver does not read: the provider's pump waits for room until its idle timeout cuts the stream. */
    private suspend fun unread(): Result {
        val download = node.callReceiving(FilesCapability.Download, FileIdRequest(BIG_ID))
        delay(25_000)
        val outcome = withContext(Dispatchers.IO) { download.use { runCatching { it.bytes.input.readBytes().size } } }
        val error = outcome.exceptionOrNull()
        return Result(error != null, "reading after 25 s: ${error?.let(::describe) ?: "${outcome.getOrNull()} bytes"}")
    }

    /** A long download, to be cut by killing the provider (the script does): the read must fail, not hang. */
    private suspend fun downloadSlow(): Result = withContext(Dispatchers.IO) {
        val download = node.callReceiving(FilesCapability.Download, FileIdRequest(SLOW_ID))
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var lastByte = System.nanoTime()
        var lastReport = System.nanoTime()
        val outcome = download.use {
            runCatching {
                while (true) {
                    val count = it.bytes.input.read(buffer)
                    if (count < 0) break
                    total += count
                    lastByte = System.nanoTime()
                    if (elapsedMs(lastReport) >= 1_000) {
                        lastReport = lastByte
                        log("PROGRESS download-slow $total")
                    }
                }
            }
        }
        val gap = elapsedMs(lastByte)
        val error = outcome.exceptionOrNull()
        Result(error != null && gap < 5_000, "after $total bytes, ended $gap ms after the last byte with ${error?.let(::describe) ?: "end of stream"}")
    }

    /** A long upload, to be cut by killing this app (the script does): the provider's read must fail, not hang. */
    private suspend fun uploadSlow(): Result {
        log("PROGRESS upload-slow started")
        val code = errorOf {
            node.callSending(
                FilesCapability.Upload,
                UploadFileRequest(SLOW_UPLOAD_NAME, "application/octet-stream", SLOW_UPLOAD_BYTES),
                ByteStream(SLOW_UPLOAD_BYTES, SlowSource(SLOW_UPLOAD_BYTES)),
            )
        }
        return Result(false, "the upload ended ($code) before this app was killed")
    }

    private suspend fun upload(name: String, content: ByteArray) = node.callSending(
        FilesCapability.Upload,
        UploadFileRequest(name, "application/octet-stream", content.size.toLong()),
        ByteStream(content.size.toLong(), ByteArrayInputStream(content)),
    )

    /** The error code [block] fails with, or `none`. */
    private suspend fun errorOf(block: suspend () -> Unit): String =
        try {
            block()
            "none"
        } catch (e: KoraException) {
            e.error.code.wireName
        }

    /** Yields [size] bytes, 1 KiB every 50 ms. */
    private class SlowSource(size: Long) : InputStream() {
        private var left = size

        override fun read(): Int = throw UnsupportedOperationException("read in chunks")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            Thread.sleep(50)
            val count = minOf(len.toLong(), 1024L, left).toInt()
            left -= count
            return count
        }
    }

    companion object {
        val NAMES = listOf("roundtrip", "upload-stall", "read-idle", "read-close", "unread", "download-slow", "upload-slow")
        private const val SILENT_ID = "silent"
        private const val BIG_ID = "big"
        private const val SLOW_ID = "slow"
        private const val SLOW_UPLOAD_NAME = "slow-upload"
        private const val SLOW_UPLOAD_BYTES = 8L * 1024 * 1024 // under the provider's 16 MiB; minutes at 20 KiB/s

        private fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000

        private fun describe(e: Throwable): String =
            (e as? KoraException)?.let { "kora ${it.error.code.wireName}: ${it.error.message}" }
                ?: "${e.javaClass.simpleName}: ${e.message}"

        private fun sha256(content: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    }
}
