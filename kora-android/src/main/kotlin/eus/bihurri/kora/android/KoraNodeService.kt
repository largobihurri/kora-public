package eus.bihurri.kora.android

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraEventSink
import eus.bihurri.kora.android.ipc.IKoraNode
import eus.bihurri.kora.android.ipc.IKoraSubscription
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Bound service that serves this app's node to other apps; declared, exported, in this library's manifest.
 *
 * It has no manifest permission on purpose: every Binder method identifies the caller from its uid and signing
 * certificate (see `CallerIdentifier`): the app itself, signed peers, the adb shell and external apps. What each
 * of them may do is decided per scope by the node's caller policy; only a caller that cannot be identified is
 * answered `unauthorized` here. Discovery (`KoraAndroid` peer directory) lists only apps signed with our key, so
 * third parties are served when they bind but never appear as peers. The node it serves is the one created by
 * [KoraAndroid.install]; without it the service refuses to bind.
 */
public class KoraNodeService : Service() {
    private var scope: CoroutineScope? = null
    private var server: NodeServer? = null
    private var binder: IBinder? = null

    @Synchronized
    override fun onBind(intent: Intent?): IBinder? {
        binder?.let { return it }
        val runtime = KoraAndroid.current()
        if (runtime == null) {
            Log.w(TAG, "KoraAndroid.install was not called in Application.onCreate; refusing to bind")
            return null
        }
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled failure while serving a request", e) },
        )
        val server = NodeServer(
            node = runtime.node,
            callers = CallerIdentifier(AndroidPackageInspector(this), runtime.node.nodeId),
            scope = scope,
        )
        runtime.server = server
        this.scope = scope
        this.server = server
        return NodeBinder(server).also { binder = it }
    }

    @Synchronized
    override fun onDestroy() {
        scope?.cancel()
        KoraAndroid.current()?.let { if (it.server === server) it.server = null }
        scope = null
        server = null
        binder = null
        super.onDestroy()
    }

    private class NodeBinder(private val server: NodeServer) : IKoraNode.Stub() {
        override fun protocolVersion(): Int = KoraAndroid.PROTOCOL_VERSION

        override fun describe(): ByteArray = server.describe(Binder.getCallingUid())

        override fun call(request: ByteArray, callback: IKoraCallback) =
            server.call(Binder.getCallingUid(), request, callback)

        override fun subscribe(request: ByteArray, sink: IKoraEventSink): IKoraSubscription =
            server.subscribe(Binder.getCallingUid(), request, sink)
    }

    private companion object {
        const val TAG = "KoraAndroid"
    }
}
