package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.files.FileHandle
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.WithBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The files of the sample's `files@1.0` provider, there for the on-device stream checks (`samples/stream-checks.sh`).
 * An upload is kept in memory, up to [maxBytes], for the caller that sent it; a download answers any handle given to
 * the same caller; a discard drops one. Every upload and every close of a download's source is reported to [log],
 * which the checks read from logcat.
 *
 * Besides real handles, fixed files and names exercise the transport rather than the contract:
 * - [SILENT_ID] downloads a file whose source yields no byte until it is closed (a reader blocked on the pipe);
 * - [SLOW_ID] downloads a long file in steps of [SLOW_CHUNK_BYTES] (a stream to cut by killing a process);
 * - [BIG_ID] downloads [BIG_BYTES] at once (more than a pipe holds, for a receiver that does not read);
 * - an upload named [STALL_NAME] is never read, so the sender's pump waits for room in a full pipe.
 */
internal class SampleFiles(private val maxBytes: Long = DEFAULT_MAX_BYTES, private val log: (String) -> Unit) {
    private class Stored(val owner: Caller, val handle: FileHandle, val content: ByteArray)

    private val stored = ConcurrentHashMap<String, Stored>()
    private val nextId = AtomicInteger(1)

    /** Reads an upload whole (to its end, so a stream that runs over fails) and keeps it for [caller]. */
    suspend fun upload(caller: Caller, name: String, mimeType: String, declaredSize: Long, bytes: ByteStream): FileHandle {
        if (name.isBlank() || mimeType.isBlank()) throw invalid("name and mimeType must not be blank")
        if (declaredSize != bytes.sizeBytes) throw invalid("sizeBytes $declaredSize, but the stream carries ${bytes.sizeBytes}")
        if (bytes.sizeBytes > maxBytes) {
            throw KoraException(
                KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "${bytes.sizeBytes} bytes exceed the sample's $maxBytes"),
            )
        }
        log("upload $name started: ${bytes.sizeBytes} bytes")
        if (name == STALL_NAME) {
            delay(STALL_SECONDS.seconds) // never reads: the sender's pump fills the pipe and waits
            throw invalid("'$STALL_NAME' is never read")
        }
        val started = System.nanoTime()
        val content = try {
            withContext(Dispatchers.IO) { bytes.input.readBytes() }
        } catch (e: Throwable) { // a read that fails, or the call cancelled because the caller died
            log("upload $name failed after ${elapsedMs(started)} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val handle = FileHandle("f-${nextId.getAndIncrement()}", name, mimeType, content.size.toLong())
        stored[handle.fileId] = Stored(caller, handle, content)
        log("upload $name staged ${handle.fileId}: ${content.size} bytes sha256=${sha256(content)}")
        return handle
    }

    /** Answers a stored handle of [caller], or one of the fixed files; anything else is an unknown id. */
    fun download(caller: Caller, fileId: String): WithBytes<FileHandle> = when (fileId) {
        SILENT_ID -> fixed(fileId, SILENT_BYTES, SilentInputStream())
        SLOW_ID -> fixed(fileId, SLOW_BYTES, SlowInputStream(SLOW_BYTES))
        BIG_ID -> fixed(fileId, BIG_BYTES, ByteArrayInputStream(ByteArray(BIG_BYTES.toInt())))
        else -> {
            val file = owned(caller, fileId)
            WithBytes(file.handle, ByteStream(file.content.size.toLong(), logged(fileId, ByteArrayInputStream(file.content))))
        }
    }

    /** Drops a stored handle of [caller]. */
    fun discard(caller: Caller, fileId: String) {
        stored.remove(owned(caller, fileId).handle.fileId)
        log("discarded $fileId")
    }

    private fun owned(caller: Caller, fileId: String): Stored =
        stored[fileId]?.takeIf { it.owner == caller } ?: throw invalid("Unknown file '$fileId'")

    private fun fixed(fileId: String, sizeBytes: Long, source: InputStream): WithBytes<FileHandle> {
        log("download $fileId started: $sizeBytes bytes")
        return WithBytes(
            FileHandle(fileId, "$fileId.bin", "application/octet-stream", sizeBytes),
            ByteStream(sizeBytes, logged(fileId, source)),
        )
    }

    /** Reports when [source] is closed, how long after it was opened and how many bytes were read from it. */
    private fun logged(fileId: String, source: InputStream): InputStream = object : FilterInputStream(source) {
        private val opened = System.nanoTime()
        private val closed = AtomicBoolean(false)
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun close() {
            if (closed.compareAndSet(false, true)) log("source $fileId closed after ${elapsedMs(opened)} ms, $count bytes read")
            super.close()
        }
    }

    /** Yields no byte: a read blocks until the stream is closed, then fails. */
    private class SilentInputStream : InputStream() {
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            throw IOException("closed")
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = read()

        override fun close() = closed.countDown()
    }

    /** Yields [size] bytes, [SLOW_CHUNK_BYTES] at a time, one step every [SLOW_STEP_MS]. */
    private class SlowInputStream(private val size: Long) : InputStream() {
        private var left = size

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            Thread.sleep(SLOW_STEP_MS)
            val count = minOf(len.toLong(), SLOW_CHUNK_BYTES.toLong(), left).toInt()
            b.fill(0x5a, off, off + count)
            left -= count
            return count
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024
        const val SILENT_ID = "silent"
        const val SILENT_BYTES = 1024L * 1024
        const val SLOW_ID = "slow"
        const val SLOW_CHUNK_BYTES = 1024
        const val SLOW_STEP_MS = 50L
        const val SLOW_BYTES = 64L * 1024 * 1024
        const val BIG_ID = "big"
        const val BIG_BYTES = 4L * 1024 * 1024
        const val STALL_NAME = "stall"
        const val STALL_SECONDS = 60

        fun sha256(content: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

        private fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000

        private fun invalid(message: String) = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, message))
    }
}

/** Provides `files@1.0` from [files] (see [SampleFiles]). */
internal fun KoraNode.provideSampleFiles(files: SampleFiles) {
    provide(FilesCapability) {
        onUpload(FilesCapability.Upload) { request, bytes ->
            files.upload(caller, request.name, request.mimeType, request.sizeBytes, bytes)
        }
        onDownload(FilesCapability.Download) { request -> files.download(caller, request.fileId) }
        on(FilesCapability.Discard) { request -> files.discard(caller, request.fileId) }
    }
}
