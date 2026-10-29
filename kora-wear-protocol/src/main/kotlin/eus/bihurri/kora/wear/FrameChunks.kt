package eus.bihurri.kora.wear

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Splits frames into chunks small enough for one Data Layer message (about 100 KB at most) and puts them back
 * together. A chunk is `[format 1][frame id: int][index: u16][count: u16][payload]`.
 *
 * Chunking was chosen over a stream (ChannelClient) because it needs no connection lifecycle: every chunk is an
 * ordinary message, sent and received through the same service as everything else, and a frame is at most
 * [WearProtocol.MAX_FRAME_BYTES], that is a handful of messages. Typical frames (calls, events) are one chunk.
 */
public object FrameChunks {
    /** Bytes before the payload of each chunk. */
    public const val HEADER_BYTES: Int = 9

    /** Default payload per chunk: leaves a wide margin under the ~100 KB message limit. */
    public const val DEFAULT_CHUNK_PAYLOAD: Int = 65_000

    private const val FORMAT = 1
    private val nextId = AtomicInteger(Random.nextInt())

    /** Splits [frame] into chunks of at most [chunkPayload] payload bytes. An empty frame is one empty chunk. */
    public fun split(frame: ByteArray, chunkPayload: Int = DEFAULT_CHUNK_PAYLOAD): List<ByteArray> {
        require(chunkPayload > 0) { "chunkPayload must be positive" }
        require(frame.size <= WearProtocol.MAX_FRAME_BYTES) { "frame of ${frame.size} bytes is too large" }
        val count = maxOf(1, (frame.size + chunkPayload - 1) / chunkPayload)
        require(count <= MAX_CHUNKS) { "frame needs $count chunks, more than $MAX_CHUNKS" }
        val id = nextId.getAndIncrement()
        return List(count) { index ->
            val from = index * chunkPayload
            val to = minOf(frame.size, from + chunkPayload)
            ByteBuffer.allocate(HEADER_BYTES + (to - from))
                .put(FORMAT.toByte()).putInt(id).putShort(index.toShort()).putShort(count.toShort())
                .put(frame, from, to - from)
                .array()
        }
    }

    internal const val MAX_CHUNKS = 64

    internal class Chunk(val id: Int, val index: Int, val count: Int, val payload: ByteArray)

    internal fun parse(bytes: ByteArray): Chunk? {
        if (bytes.size < HEADER_BYTES || bytes[0].toInt() != FORMAT) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val id = buffer.getInt()
        val index = buffer.getShort().toInt() and 0xFFFF
        val count = buffer.getShort().toInt() and 0xFFFF
        if (count !in 1..MAX_CHUNKS || index >= count) return null
        return Chunk(id, index, count, bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }
}

/**
 * Reassembles frames from chunks, in any order. Bounded: at most [maxPartial] frames are pending (the oldest is
 * forgotten first, so a frame whose chunks were lost does not accumulate), and a frame never exceeds
 * [WearProtocol.MAX_FRAME_BYTES]. Chunks that do not parse or contradict each other are ignored.
 * Thread-safe.
 */
public class FrameAssembler(private val maxPartial: Int = 4) {
    private class Partial(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val pending = LinkedHashMap<Int, Partial>()

    /** Takes one chunk; returns the whole frame when [chunk] completes it, otherwise null. */
    @Synchronized
    public fun accept(chunk: ByteArray): ByteArray? {
        val parsed = FrameChunks.parse(chunk) ?: return null
        if (parsed.count == 1) return parsed.payload.takeIf { it.size <= WearProtocol.MAX_FRAME_BYTES }
        val partial = pending[parsed.id] ?: Partial(parsed.count).also {
            pending[parsed.id] = it
            while (pending.size > maxPartial) pending.remove(pending.keys.first())
        }
        if (partial.count != parsed.count) {
            pending.remove(parsed.id)
            return null
        }
        if (partial.parts[parsed.index] != null) return null // duplicate
        if (partial.bytes + parsed.payload.size > WearProtocol.MAX_FRAME_BYTES) {
            pending.remove(parsed.id)
            return null
        }
        partial.parts[parsed.index] = parsed.payload
        partial.received++
        partial.bytes += parsed.payload.size
        if (partial.received < partial.count) return null
        pending.remove(parsed.id)
        val whole = ByteBuffer.allocate(partial.bytes)
        partial.parts.forEach { whole.put(it!!) }
        return whole.array()
    }
}
