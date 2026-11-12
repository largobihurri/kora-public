package eus.bihurri.kora.android

import android.os.IBinder
import android.os.ParcelFileDescriptor
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraEventSink
import eus.bihurri.kora.android.ipc.IKoraNode
import eus.bihurri.kora.android.ipc.IKoraStreamCallback
import eus.bihurri.kora.android.ipc.IKoraSubscription
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An in-memory pipe that behaves like an OS pipe for the transport: a read waits for bytes and answers -1 once every
 * write end is closed; a write waits for room and fails once every read end is closed; and closing the descriptor a
 * thread is blocked on fails its read or write, as closing a descriptor does on Android. Robolectric cannot stand
 * in here: its `ParcelFileDescriptor.createPipe` is a temporary file, which never blocks.
 */
internal class MemoryPipe(private val capacity: Int) {
    val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ring = ByteArray(capacity)
    private var head = 0
    private var count = 0
    private var readEnds = 0
    private var writeEnds = 0

    fun retain(end: FakeDescriptor.End) = lock.withLock {
        if (end == FakeDescriptor.End.READ) readEnds++ else writeEnds++
    }

    fun release(end: FakeDescriptor.End) = lock.withLock {
        if (end == FakeDescriptor.End.READ) readEnds-- else writeEnds--
        changed.signalAll()
    }

    fun read(handle: FakeDescriptor, b: ByteArray, off: Int, len: Int): Int {
        lock.withLock {
            while (true) {
                if (handle.closed) throw IOException("Descriptor is closed")
                if (len == 0) return 0
                if (count > 0) {
                    val n = minOf(len, count)
                    for (i in 0 until n) b[off + i] = ring[(head + i) % capacity]
                    head = (head + n) % capacity
                    count -= n
                    changed.signalAll()
                    return n
                }
                if (writeEnds == 0) return -1
                changed.await()
            }
        }
    }

    fun write(handle: FakeDescriptor, b: ByteArray, off: Int, len: Int) = lock.withLock {
        var written = 0
        while (written < len) {
            if (handle.closed) throw IOException("Descriptor is closed")
            if (readEnds == 0) throw IOException("Broken pipe")
            val room = capacity - count
            if (room == 0) {
                changed.await()
                continue
            }
            val n = minOf(room, len - written)
            for (i in 0 until n) ring[(head + count + i) % capacity] = b[off + written + i]
            count += n
            written += n
            changed.signalAll()
        }
    }
}

/**
 * One end of a [MemoryPipe] held by one side, as a descriptor. [dup] gives another descriptor of the same end, as a
 * Binder transaction does for the receiver; the end stays open until every descriptor of it is closed.
 */
internal class FakeDescriptor(
    val pipe: MemoryPipe,
    val end: End,
    private val registry: FakeDescriptors,
) : ParcelFileDescriptor(null) {
    enum class End { READ, WRITE }

    @Volatile
    var closed = false
        private set

    override fun close() {
        pipe.lock.withLock {
            if (closed) return
            closed = true
            pipe.release(end)
        }
    }

    override fun dup(): ParcelFileDescriptor {
        check(!closed) { "dup of a closed descriptor" }
        return registry.open(pipe, end)
    }

    override fun toString(): String = "FakeDescriptor($end, closed=$closed)"
}

/** [Descriptors] over [MemoryPipe]s; remembers every descriptor it opened so tests can check that all were closed. */
internal class FakeDescriptors(private val capacity: Int = 64 * 1024) : Descriptors {
    val opened = CopyOnWriteArrayList<FakeDescriptor>()
    var failCreate: IOException? = null

    override fun createPipe(): Pipe {
        failCreate?.let { throw it }
        val pipe = MemoryPipe(capacity)
        return Pipe(open(pipe, FakeDescriptor.End.READ), open(pipe, FakeDescriptor.End.WRITE))
    }

    override fun input(descriptor: ParcelFileDescriptor): InputStream {
        val handle = descriptor as FakeDescriptor
        return object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = handle.pipe.read(handle, b, off, len)

            override fun close() = handle.close()
        }
    }

    override fun output(descriptor: ParcelFileDescriptor): OutputStream {
        val handle = descriptor as FakeDescriptor
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = handle.pipe.write(handle, b, off, len)

            override fun close() = handle.close()
        }
    }

    fun open(pipe: MemoryPipe, end: FakeDescriptor.End): FakeDescriptor {
        pipe.retain(end)
        return FakeDescriptor(pipe, end, this).also { opened += it }
    }

    /** The read end of a new pipe that already holds [content]; its write end is closed unless [keepWriting]. */
    fun filled(content: ByteArray, keepWriting: Boolean = false): Pair<FakeDescriptor, OutputStream> {
        val pipe = createPipe()
        val out = output(pipe.writeEnd)
        out.write(content)
        if (!keepWriting) out.close()
        return pipe.readEnd as FakeDescriptor to out
    }

    /** The descriptors still open. */
    fun stillOpen(): List<FakeDescriptor> = opened.filter { !it.closed }

    /** Waits up to [millis] for every descriptor to be closed (some close on another thread) and answers the rest. */
    fun awaitAllClosed(millis: Long = 5_000): List<FakeDescriptor> {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (stillOpen().isEmpty()) return emptyList()
            Thread.sleep(10)
        }
        return stillOpen()
    }
}

/**
 * The remote node as Binder delivers it to [server], calling as [uid]: every descriptor that crosses is duplicated,
 * so each side holds and closes its own copy, as with a real transaction.
 */
internal class LoopbackNode(
    private val server: NodeServer,
    private val uid: Int,
    var version: Int = KoraAndroid.PROTOCOL_VERSION,
) : IKoraNode {
    override fun protocolVersion(): Int = version

    override fun describe(): ByteArray = server.describe(uid)

    override fun call(request: ByteArray, callback: IKoraCallback) = server.call(uid, request, callback)

    override fun subscribe(request: ByteArray, sink: IKoraEventSink): IKoraSubscription = server.subscribe(uid, request, sink)

    override fun callSending(request: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long, callback: IKoraCallback) =
        server.callSending(uid, request, bytes?.dup(), sizeBytes, callback)

    override fun callReceiving(request: ByteArray, callback: IKoraStreamCallback) =
        server.callReceiving(uid, request, object : IKoraStreamCallback {
            override fun onResponse(response: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long) =
                callback.onResponse(response, bytes?.dup(), sizeBytes)

            override fun asBinder(): IBinder? = null
        })

    override fun asBinder(): IBinder? = null
}

/** An input that never answers until it is closed, like a source that stalls; closing it fails the waiting read. */
internal class StalledInput(private val before: ByteArray = ByteArray(0)) : InputStream() {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var position = 0

    @Volatile
    var closed = false
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = lock.withLock {
        if (position < before.size) {
            val n = minOf(len, before.size - position)
            before.copyInto(b, off, position, position + n)
            position += n
            return n
        }
        while (!closed) changed.await()
        throw IOException("Source is closed")
    }

    override fun close() = lock.withLock {
        closed = true
        changed.signalAll()
    }
}
