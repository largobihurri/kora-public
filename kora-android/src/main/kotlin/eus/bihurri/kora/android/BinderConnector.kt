package eus.bihurri.kora.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import eus.bihurri.kora.android.ipc.IKoraNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Why a peer that reports protocol [version] cannot be used, or null when it can. The version is a minimum, not an
 * equality: every peer from [KoraAndroid.MIN_PEER_PROTOCOL_VERSION] on is accepted, older or newer than this app, so
 * an app on a newer Kora keeps seeing the apps still on an older one. What a peer's version does not offer (byte
 * streams below [KoraAndroid.STREAMS_PROTOCOL_VERSION]) is refused per call by the transport instead.
 */
internal fun peerProtocolRefusal(version: Int): String? =
    if (version < KoraAndroid.MIN_PEER_PROTOCOL_VERSION) {
        "Peer speaks protocol $version, which is older than ${KoraAndroid.MIN_PEER_PROTOCOL_VERSION}"
    } else {
        null
    }

/**
 * [BinderConnector] over `Context.bindService`: binds the [KoraAndroid.ACTION_NODE] service of the peer package
 * with `BIND_AUTO_CREATE` and checks the protocol version off the main thread (see [peerProtocolRefusal]) before
 * reporting it connected with that version.
 */
internal class AndroidBinderConnector(
    private val context: Context,
    private val scope: CoroutineScope,
) : BinderConnector {
    override fun bind(packageName: String, events: BindingEvents): Binding? {
        val intent = Intent(KoraAndroid.ACTION_NODE).setPackage(packageName)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val node = IKoraNode.Stub.asInterface(service)
                // The version check is a Binder call; ServiceConnection callbacks run on the main thread.
                scope.launch(Dispatchers.IO) {
                    val version = try {
                        node.protocolVersion()
                    } catch (e: RemoteException) {
                        events.onLost("Peer died during the handshake")
                        return@launch
                    }
                    val refusal = peerProtocolRefusal(version)
                    if (refusal != null) events.onLost(refusal) else events.onConnected(node, version)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = events.onLost("Peer process died")

            override fun onBindingDied(name: ComponentName) = events.onLost("Binding died")

            override fun onNullBinding(name: ComponentName) = events.onLost("Peer does not serve a Kora node")
        }
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to bind to $packageName", e)
            false
        }
        val binding = Binding {
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Already unbound.
            }
        }
        if (!bound) {
            binding.unbind() // bindService asks for an unbind even when it returns false
            return null
        }
        return binding
    }

    private companion object {
        const val TAG = "KoraAndroid"
    }
}
