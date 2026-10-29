package eus.bihurri.kora.android

import android.app.Application
import android.util.Log
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.CallerPolicy
import eus.bihurri.kora.core.CompositePeerDirectory
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.PeerRoute
import eus.bihurri.kora.core.RoutingPeerTransport
import eus.bihurri.kora.mcp.McpServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point of the Android integration: wires a [KoraNode] to the device.
 *
 * ```
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         val node = KoraAndroid.install(this)
 *         node.provide(MediaLibrary) {
 *             on(MediaLibrary.ListPlaylists) { ... }
 *             on(MediaLibrary.GetPlaylistTracks) { ... }
 *             on(MediaLibrary.SearchTracks) { ... }
 *         }
 *     }
 * }
 * ```
 *
 * `provide` requires every operation and event of the capability (a partial registration throws
 * `IllegalArgumentException`).
 *
 * [install] builds the node with a Binder transport and a peer directory, registers it so that the library's
 * `KoraNodeService` (declared in this library's manifest) serves it to other apps, and starts discovering peers.
 * Call it once, from `Application.onCreate`, so the node exists before any peer binds.
 *
 * Besides the typed gate methods, the content provider serves MCP (`KoraGate.METHOD_MCP`): a JSON-RPC message per
 * call, one session per calling identity, so an MCP client on a PC can reach the node through adb with no
 * network listener on the phone (see `:kora-bridge`).
 *
 * Trust: the service and the content provider gate identify the caller from its uid: the app itself
 * (`SELF`), apps signed with the same key (`PEER`, trusted, id = package name), the adb shell (`SHELL`) and
 * any other app (`EXTERNAL_APP`, id = package name plus signing certificate digest). Peers may use every scope;
 * the others only what the user granted through [policy] and its `ExternalAccess`. Android grants nothing by
 * itself: there is deliberately no custom permission for external access.
 */
public object KoraAndroid {
    /** Action of the bound service every Kora app exposes; peers are found by querying for it. */
    public const val ACTION_NODE: String = "eus.bihurri.kora.action.NODE"

    /**
     * Version of the Binder interface between peers. It only grows, and only with additive changes; a breaking
     * change would ship under a new interface and action.
     */
    public const val PROTOCOL_VERSION: Int = 1

    private const val TAG = "KoraAndroid"

    internal class Runtime(
        val node: KoraNode,
        val application: Application,
        val directory: AndroidPeerDirectory,
        val pool: ConnectionPool,
        val scope: CoroutineScope,
        val gate: ExternalGate,
        val mcp: McpGate,
    ) {
        @Volatile
        var server: NodeServer? = null
    }

    @Volatile
    private var runtime: Runtime? = null

    /** Opens when [install] has run; the content provider can be asked before `Application.onCreate` completes. */
    @Volatile
    private var installed = CountDownLatch(1)

    /**
     * Creates this app's node, registers what it provides through [configure], and only then makes it reachable
     * to peers, the gate and the shell. Register every provider inside [configure]: content providers are created
     * before `Application.onCreate` and can be called as soon as the node is published, so a provider registered
     * after `install` returns may be missed by the first callers. Returns the node.
     *
     * @param displayName human-readable name advertised to peers; defaults to the app label.
     * @param policy who may do what; the default lets the app itself and signed peers use every scope and refuses
     *   everyone else. To open the app to external callers pass `DefaultCallerPolicy(externalAccess)` with an
     *   `ExternalAccess` backed by the app's settings (master switch off by default, explicit per-caller grants).
     * @param audit receives one entry per access served.
     * @param callTimeout limit for one outbound call, including binding to a peer that is not running yet.
     * @param connectTimeout how long to wait for a peer to accept the binding.
     * @param idleTimeout a connection to a peer with no call and no subscription in flight is unbound after this.
     * @param onInternalError notified when a failure is reported to a caller as `internal`.
     * @param peerRoutes further ways of reaching peers, next to Binder (for example `KoraWear.route` for the paired
     *   watch or phone). The node lists the peers of every route; when two list the same node id, Binder wins,
     *   then the routes in the order given.
     * @param configure registers the app's capability providers before the node is published.
     * @throws IllegalStateException if called twice.
     */
    @JvmOverloads
    public fun install(
        application: Application,
        displayName: String = application.applicationInfo.loadLabel(application.packageManager).toString(),
        policy: CallerPolicy = DefaultCallerPolicy(),
        audit: AuditLog = AuditLog.None,
        callTimeout: Duration = 5.seconds,
        connectTimeout: Duration = 3.seconds,
        idleTimeout: Duration = 30.seconds,
        onInternalError: (Throwable) -> Unit = { Log.w(TAG, "Internal error", it) },
        peerRoutes: List<PeerRoute> = emptyList(),
        configure: KoraNode.() -> Unit = {},
    ): KoraNode = synchronized(this) {
        check(runtime == null) { "KoraAndroid is already installed" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pool = ConnectionPool(AndroidBinderConnector(application, scope), scope, connectTimeout, idleTimeout)
        val transport = BinderPeerTransport(pool)
        val directory = AndroidPeerDirectory(
            scanner = AndroidPeerScanner(application),
            fetcher = { transport.describe(it) },
            changes = BroadcastPackageChangeSource(application),
            scope = scope,
        )
        val routes = listOf(PeerRoute(directory, transport)) + peerRoutes
        val node = KoraNode(
            nodeId = application.packageName,
            displayName = displayName,
            codec = CborCodec,
            policy = policy,
            audit = audit,
            peerDirectory = if (peerRoutes.isEmpty()) directory else CompositePeerDirectory(routes.map { it.directory }, scope),
            transport = if (peerRoutes.isEmpty()) transport else RoutingPeerTransport(routes),
            callTimeout = callTimeout,
            onInternalError = onInternalError,
        )
        node.configure()
        val serverInfo = McpServerInfo(application.packageName, versionNameOf(application), title = displayName)
        runtime = Runtime(node, application, directory, pool, scope, ExternalGate(node, callTimeout), McpGate(node, serverInfo, callTimeout))
        installed.countDown()
        directory.start()
        node
    }

    private fun versionNameOf(application: Application): String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }?.takeIf { it.isNotBlank() } ?: "unknown"

    /** The node created by [install]. */
    public val node: KoraNode
        get() = checkNotNull(runtime) { "KoraAndroid.install has not been called" }.node

    /**
     * Cancels every live subscription whose caller is neither this app nor a signed peer (external apps, the
     * shell, remote agents), telling its subscriber `unauthorized`. The caller policy is consulted when a
     * subscription opens, not while it runs, so call this whenever the user turns the master switch off or
     * removes a grant. Returns how many subscriptions were cancelled. Single calls need no revocation: each one
     * is authorized when it arrives.
     */
    public fun revokeUntrustedSubscriptions(): Int = runtime?.server?.revokeUntrusted() ?: 0

    /** Stops discovery, closes peer connections and forgets the node. Mostly for tests; apps keep it for life. */
    public fun uninstall() {
        val current = synchronized(this) {
            runtime.also {
                runtime = null
                installed = CountDownLatch(1)
            }
        } ?: return
        current.directory.close()
        current.pool.close()
        current.scope.cancel()
    }

    internal fun current(): Runtime? = runtime

    /**
     * The installed runtime, waiting up to [timeoutMillis] for [install] to run. Content providers are created
     * (and can be called by another process) before `Application.onCreate`, so the gate may arrive first. Only
     * for threads that may block: never the main thread.
     */
    internal fun awaitRuntime(timeoutMillis: Long): Runtime? {
        runtime?.let { return it }
        installed.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return runtime
    }
}
