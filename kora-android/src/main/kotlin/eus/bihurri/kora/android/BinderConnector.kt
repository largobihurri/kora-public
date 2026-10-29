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
 * [BinderConnector] over `Context.bindService`: binds the [KoraAndroid.ACTION_NODE] service of the peer package
 * with `BIND_AUTO_CREATE` and checks the protocol version off the main thread before reporting it connected.
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
                    if (version < KoraAndroid.PROTOCOL_VERSION) {
                        events.onLost("Peer speaks protocol $version, which is older than ${KoraAndroid.PROTOCOL_VERSION}")
                    } else {
                        events.onConnected(node)
                    }
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
