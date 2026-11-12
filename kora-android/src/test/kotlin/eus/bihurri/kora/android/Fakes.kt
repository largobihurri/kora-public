package eus.bihurri.kora.android

import android.os.IBinder
import android.os.ParcelFileDescriptor
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraEventSink
import eus.bihurri.kora.android.ipc.IKoraNode
import eus.bihurri.kora.android.ipc.IKoraStreamCallback
import eus.bihurri.kora.android.ipc.IKoraSubscription
import java.util.concurrent.CopyOnWriteArrayList

/** Controls what the system tells the connection pool and records what the pool asked of it. */
internal class FakeConnector : BinderConnector {
    class Attempt(val packageName: String, val events: BindingEvents) {
        var unbound = false
    }

    val attempts = mutableListOf<Attempt>()
    var refuse = false
    var failWith: RuntimeException? = null

    /** When set, every binding connects at once to this node, which reports this protocol version. */
    var autoConnect: Pair<IKoraNode, Int>? = null

    override fun bind(packageName: String, events: BindingEvents): Binding? {
        failWith?.let { throw it }
        if (refuse) return null
        val attempt = Attempt(packageName, events)
        attempts += attempt
        autoConnect?.let { (node, version) -> events.onConnected(node, version) }
        return Binding { attempt.unbound = true }
    }
}

internal class FakeCallback : IKoraCallback {
    val responses = mutableListOf<ByteArray>()
    var failWith: Exception? = null

    override fun onResponse(response: ByteArray) {
        failWith?.let { throw it }
        responses += response
    }

    override fun asBinder(): IBinder? = null
}

internal class FakeSink : IKoraEventSink {
    val events = mutableListOf<ByteArray>()
    val errors = mutableListOf<ByteArray>()
    var completed = false
    var failWith: Exception? = null

    override fun onEvent(event: ByteArray) {
        failWith?.let { throw it }
        events += event
    }

    override fun onError(error: ByteArray) {
        failWith?.let { throw it }
        errors += error
    }

    override fun onComplete() {
        failWith?.let { throw it }
        completed = true
    }

    override fun asBinder(): IBinder? = null
}

/** Records the answers of downloads; [duplicate] keeps a copy of each descriptor, as a Binder transaction gives one. */
internal class FakeStreamCallback(private val duplicate: Boolean = true) : IKoraStreamCallback {
    class Answer(val response: ByteArray, val bytes: ParcelFileDescriptor?, val sizeBytes: Long)

    val answers = CopyOnWriteArrayList<Answer>()
    var failWith: Exception? = null

    override fun onResponse(response: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long) {
        failWith?.let { throw it }
        answers += Answer(response, if (duplicate) bytes?.dup() else bytes, sizeBytes)
    }

    override fun asBinder(): IBinder? = null
}

/** Stands in for the remote node as seen by the client side. */
internal class FakeRemoteNode(
    var version: Int = KoraAndroid.PROTOCOL_VERSION,
    var descriptor: ByteArray = ByteArray(0),
) : IKoraNode {
    val calls = mutableListOf<Pair<ByteArray, IKoraCallback>>()
    val subscriptions = mutableListOf<Pair<ByteArray, IKoraEventSink>>()
    val sendingCalls = CopyOnWriteArrayList<SendingCall>()
    val receivingCalls = CopyOnWriteArrayList<Pair<ByteArray, IKoraStreamCallback>>()
    var cancelled = 0
    var onCall: (ByteArray, IKoraCallback) -> Unit = { _, _ -> }
    var onCallSending: (SendingCall) -> Unit = {}
    var onCallReceiving: (ByteArray, IKoraStreamCallback) -> Unit = { _, _ -> }
    var streamCallFails: Exception? = null

    /** One upload as the peer received it; [bytes] is the peer's own copy of the descriptor. */
    class SendingCall(val request: ByteArray, val bytes: ParcelFileDescriptor?, val sizeBytes: Long, val callback: IKoraCallback)

    /** Every transaction this node received, of any kind. */
    val transactions: Int get() = calls.size + subscriptions.size + sendingCalls.size + receivingCalls.size
    var describeFails: Exception? = null
    var subscribeFails: Exception? = null

    override fun protocolVersion(): Int = version

    override fun describe(): ByteArray = describeFails?.let { throw it } ?: descriptor

    override fun call(request: ByteArray, callback: IKoraCallback) {
        calls += request to callback
        onCall(request, callback)
    }

    override fun callSending(request: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long, callback: IKoraCallback) {
        streamCallFails?.let { throw it }
        val call = SendingCall(request, bytes?.dup(), sizeBytes, callback)
        sendingCalls += call
        onCallSending(call)
    }

    override fun callReceiving(request: ByteArray, callback: IKoraStreamCallback) {
        streamCallFails?.let { throw it }
        receivingCalls += request to callback
        onCallReceiving(request, callback)
    }

    override fun subscribe(request: ByteArray, sink: IKoraEventSink): IKoraSubscription {
        subscribeFails?.let { throw it }
        subscriptions += request to sink
        return object : IKoraSubscription {
            override fun cancel() {
                cancelled++
            }

            override fun asBinder(): IBinder? = null
        }
    }

    override fun asBinder(): IBinder? = null
}
