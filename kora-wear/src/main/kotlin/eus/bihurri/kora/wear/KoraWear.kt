package eus.bihurri.kora.wear

import android.app.Application
import android.util.Log
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.PeerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Connects a node to the same app on the paired device (phone <-> watch) over the Wear OS Data Layer, so each
 * side sees the other's Kora peers as `wear:<node id>` and can call them and subscribe to their events. The same
 * code runs on the phone app and on the watch app.
 *
 * ```
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         val wear = KoraWear.install(this)
 *         val node = KoraAndroid.install(this, peerRoutes = listOf(wear.route)) {
 *             provide(...) { ... }
 *         }
 *         wear.serve(node)          // from here the other device can use this node and its on-device peers
 *     }
 * }
 * // later, when the UI needs the other device (music page opened, recording started):
 * wear.requestRefresh()
 * node.availability(MediaPlayback)   // true when a provider exists on this device or on the other one
 * ```
 *
 * Install and serve in `Application.onCreate`: the service that receives messages may start the process in the
 * background, and messages that arrive before [serve] are lost.
 *
 * The app on the other device must also include `kora-wear`. Both declare the `kora_node` capability in the
 * library's resources (`android_wear_capabilities`); an app that declares its own array with that name must list
 * `kora_node` in it too.
 */
public class KoraWear private constructor(
    private val application: Application,
    private val scope: CoroutineScope,
    private val link: MessageLink,
    private val relay: WearRelay,
) {
    /**
     * The counterpart's peers, to pass to `KoraAndroid.install(peerRoutes = listOf(route))` (or to combine with
     * `CompositePeerDirectory` and `RoutingPeerTransport` in a node built by hand).
     */
    public val route: PeerRoute get() = relay.route

    /** The peers of the other device (`wear:<id>`), including the other app itself; empty until a refresh. */
    public val remotePeers: StateFlow<List<PeerInfo>> get() = relay.remotePeers

    /**
     * Starts serving [node] to the other device and reading messages. The other device is served as
     * `Caller(PEER, "wear:<package>")`: it is the same app, authenticated by the Data Layer, and the node's caller
     * policy and audit apply to what it asks.
     */
    public fun serve(node: KoraNode) {
        relay.serve(node, Caller(CallerKind.PEER, WearProtocol.PEER_PREFIX + application.packageName))
    }

    /**
     * Asks the other device for its peers now (fire and forget). Nothing is sent before the first call, because a
     * message can start the other device's app; afterwards the list follows changes without further calls.
     */
    public fun requestRefresh(): Unit = relay.requestRefresh()

    /** Like [requestRefresh], but waits and tells whether the other device answered. */
    public suspend fun refresh(): Boolean = relay.refresh()

    /** Stops listening and cancels the relay's work. Mostly for tests; apps keep it for life. */
    public fun close() {
        clear()
        link.close()
        scope.cancel()
    }

    internal fun deliver(sourceNodeId: String, path: String, data: ByteArray) = link.deliver(sourceNodeId, path, data)

    public companion object {
        @Volatile
        private var installed: KoraWear? = null

        /** The instance created by [install], or null. */
        public val current: KoraWear? get() = installed

        /**
         * Creates the relay. Nothing touches the Data Layer until the first message is sent or received.
         *
         * @param callTimeout how long to wait for the other device to answer one call; it crosses two radios and
         *   may start a cold app, so give nodes that call over it a `callTimeout` of the same order.
         * @throws IllegalStateException if called twice.
         */
        @JvmOverloads
        public fun install(application: Application, callTimeout: Duration = 10.seconds): KoraWear = synchronized(Companion) {
            check(installed == null) { "KoraWear is already installed" }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val link = MessageLink(GmsDataLayer(application), scope) { message, cause -> Log.w(TAG, message, cause) }
            val relay = WearRelay(link, scope, callTimeout = callTimeout, describeTimeout = callTimeout) { Log.w(TAG, "Dropped frame: $it") }
            KoraWear(application, scope, link, relay).also { installed = it }
        }

        private const val TAG = "KoraWear"
    }

    private fun clear() {
        synchronized(Companion) { if (installed === this) installed = null }
    }
}
