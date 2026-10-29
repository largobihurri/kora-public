package eus.bihurri.kora.tailnet

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.mcp.McpServerInfo
import eus.bihurri.kora.mcp.http.DeniedAttempt
import eus.bihurri.kora.mcp.http.McpHttpConfig
import eus.bihurri.kora.mcp.http.McpHttpServer
import eus.bihurri.kora.mcp.http.Pairing
import eus.bihurri.kora.mcp.http.PairingTokens
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Lets the owner's agents use this app over MCP **through Tailscale, and only there**: the app's [KoraNode] is served
 * on `http://<tailscale address>:<port>/mcp` while a user switch is on, behind a foreground service, with one revocable
 * pairing (a token) per agent and an auto-off after an idle period.
 *
 * ```
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         val node = KoraAndroid.install(this, policy = DefaultCallerPolicy(myExternalAccess)) { ... }
 *         KoraTailnet.install(
 *             this, node,
 *             TailnetConfig(port = 47831, idleTimeout = 30.minutes, notification = TailnetNotification(R.drawable.ic_stat_agent)),
 *         )
 *     }
 * }
 * // The switch:   if (on) KoraTailnet.start() else KoraTailnet.stop()   (observe KoraTailnet.status)
 * // Pair agent:   val issued = KoraTailnet.pair("Claude on my-desktop")  -> show issued.token once
 * ```
 *
 * ## Security model
 * - The listener binds the address of the Tailscale **VPN** interface (a VPN that also carries Tailscale's
 *   fd7a:115c:a1e0::/48 address), never the wildcard and never the LAN or the carrier network. 100.64.0.0/10 addresses
 *   on any other interface (the carrier's CGNAT, another VPN) are never chosen.
 * - Every request needs a pairing token; each pairing is its own caller (`Caller(REMOTE_AGENT, "tailnet:<id>")`, see
 *   [caller]) and the node's policy decides what it may do, exactly as for the adb shell. A fresh pairing may do
 *   **nothing** until the app grants it scopes.
 * - No TLS: the tailnet (WireGuard) encrypts, and only the owner's devices are in it.
 * - The listener is on only while the switch is, and switches itself off after [TailnetConfig.idleTimeout] without an
 *   authenticated request, counting the time the device sleeps (an alarm enforces it; precision is in the README).
 *
 * ## Permissions and threads
 * The library manifest declares what it needs, except `POST_NOTIFICATIONS` (Android 13+): the app asks the user for it
 * before the first [start], or the notification is not shown (the listener still runs). [start] must be called while
 * the app may start a foreground service (typically from the user's tap on the switch); otherwise the status becomes
 * [TailnetStatus.Error] instead of crashing. [pair], [revoke] and [pairings] touch a file: call them off the main thread.
 * The state does not survive the process: after the process dies the switch is off, and the app decides whether to
 * offer it again. [status], [denied], [start], [stop], [pair], [pairings] and [revoke] throw
 * `IllegalStateException` before [install].
 */
public object KoraTailnet {
    private const val TAG = "KoraTailnet"
    private const val PAIRINGS_FILE = "kora-tailnet-pairings.json"
    private const val MAX_NAME_CHARS = 80

    @Volatile
    private var runtime: TailnetRuntime? = null

    /**
     * Prepares the listener; call once from `Application.onCreate`, after the node is installed. Nothing listens until
     * [start]. [node] is the node to serve (usually the one `KoraAndroid.install` returned); its caller policy is the
     * only authority over what a paired agent may do.
     *
     * @throws IllegalStateException when called twice.
     */
    public fun install(application: Application, node: KoraNode, config: TailnetConfig) {
        install(application, node, config, AndroidTailnetAddressSource(application))
    }

    /**
     * [install] with the pieces a test replaces: the address source, the scope and clock, the bind-address check and
     * the context that starts and stops the service.
     */
    internal fun install(
        application: Application,
        node: KoraNode,
        config: TailnetConfig,
        addresses: TailnetAddressSource,
        scope: CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled failure", e) },
        ),
        clock: () -> Long = SystemClock::elapsedRealtimeNanos,
        alarm: DeadlineAlarm = AndroidDeadlineAlarm(application),
        allowNonTailnetAddress: Boolean = false,
        serviceContext: Context = application,
        pairingsMove: (Path, Path) -> Unit = ::atomicMove,
    ) {
        val serverInfo = config.serverInfo ?: McpServerInfo(application.packageName, versionNameOf(application))
        val store = FilePairingStore(File(application.noBackupFilesDir, PAIRINGS_FILE), log = { Log.w(TAG, it) }, move = pairingsMove)
        val controller = TailnetController(
            addresses = addresses,
            port = config.port,
            path = config.path,
            idleTimeout = config.idleTimeout,
            scope = scope,
            clock = clock,
            alarm = alarm,
            pairings = store,
            log = { Log.i(TAG, it) },
        ) { guarded, listener ->
            McpHttpServer(
                node = node,
                serverInfo = serverInfo,
                pairings = guarded,
                config = McpHttpConfig(
                    path = config.path,
                    allowedHosts = config.allowedHosts,
                    allowNonTailnetAddress = allowNonTailnetAddress,
                    log = { Log.i(TAG, it) },
                ),
                listener = listener,
            )
        }
        install(TailnetRuntime(serviceContext, config, store, controller, scope))
    }

    internal fun install(runtime: TailnetRuntime) = synchronized(this) {
        check(this.runtime == null) { "KoraTailnet is already installed" }
        this.runtime = runtime
    }

    /** What the listener is doing now. */
    public val status: StateFlow<TailnetStatus> get() = installed().controller.status

    /** Requests that were refused (no or wrong token, foreign `Origin` or `Host`), at most one per address and reason per 10 s. */
    public val denied: SharedFlow<DeniedAttempt> get() = installed().controller.denied

    /**
     * Turns the switch on: starts watching for the Tailscale address, listens there when it is up, and shows the
     * notification. Does nothing when already on. Failures (the foreground service may not start, the network
     * cannot be watched, a revocation that could not be saved yet) are reported through [status], not thrown. The
     * listener refuses to start while the pairings file cannot be brought up to date: it fails closed.
     */
    public fun start() {
        val runtime = installed()
        try {
            // A revocation that could not be written earlier must reach the disk before anything is served again.
            runtime.store.ensurePersisted()
        } catch (e: IOException) {
            Log.w(TAG, "Not starting: the pairings file cannot be written", e)
            runtime.controller.reject("Not listening: a revoked pairing could not be saved (${e.message ?: "I/O error"}); free storage and try again")
            return
        }
        runtime.controller.start()
        if (!runtime.controller.running.value) return
        try {
            runtime.application.startForegroundService(Intent(runtime.application, TailnetService::class.java))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the foreground service", e)
            runtime.controller.fail("Could not start the foreground service: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Turns the switch off: stops listening and removes the notification. Does nothing when already off. */
    public fun stop() {
        installed().stop()
    }

    /**
     * Creates a pairing named [name] (what the owner calls the agent; shown in the app, never used for access) and
     * returns it with its token. The token is shown **once**: only its hash is stored, and nothing can recover it.
     * A new pairing may do nothing until the app grants it scopes (see [caller]).
     *
     * @throws IllegalArgumentException when [name] is blank, longer than 80 characters or holds control characters.
     * @throws IOException when the pairings file cannot be written.
     */
    public fun pair(name: String): IssuedPairing {
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS && name.none { it.isISOControl() }) {
            "A pairing name is 1 to $MAX_NAME_CHARS printable characters"
        }
        val runtime = installed()
        val token = PairingTokens.generate()
        // Ids are random 72-bit values; a clash is not expected, but a revoked or live id must never be reused.
        repeat(ID_ATTEMPTS) {
            val pairing = Pairing(PairingTokens.generateId(), name.trim(), System.currentTimeMillis())
            try {
                runtime.store.add(pairing, token.hash)
                return IssuedPairing(pairing, token.token)
            } catch (_: IllegalArgumentException) {
                // Clash with an existing or revoked id: draw another.
            }
        }
        throw IOException("Could not allocate a pairing id")
    }

    /** The pairings that exist, oldest first. */
    public fun pairings(): List<Pairing> = installed().store.pairings()

    /**
     * Revokes the pairing: its token is refused from the next request on, its live MCP session is dropped, and its id
     * is never accepted again. The app should also remove the grants it keeps for [caller] of that id. Returns whether
     * the pairing existed.
     *
     * @throws IOException when the file cannot be written. The pairing is revoked in memory regardless; it could come
     *   back after a restart until the file is written successfully, so tell the user and retry.
     */
    public fun revoke(pairingId: String): Boolean {
        val runtime = installed()
        try {
            return runtime.store.revoke(pairingId)
        } finally {
            runtime.controller.evictSession(pairingId)
        }
    }

    /**
     * The caller the node sees for requests that carry the token of [pairing]: `Caller(REMOTE_AGENT, "tailnet:<id>")`.
     * Grant scopes to this value in the app's `ExternalAccess` (`grantedScopes(caller)`), exactly as for the adb shell.
     * The pairing's name is not part of it.
     */
    public fun caller(pairing: Pairing): Caller = caller(pairing.id)

    /** [caller] for a pairing id, for code that kept only the id. */
    public fun caller(pairingId: String): Caller = Caller(CallerKind.REMOTE_AGENT, "tailnet:$pairingId")

    /** Switches off and forgets the installation. Mostly for tests; apps keep it for the life of the process. */
    public fun uninstall() {
        val current = synchronized(this) { runtime.also { runtime = null } } ?: return
        current.stop()
        current.scope.cancel()
    }

    internal fun current(): TailnetRuntime? = runtime

    private fun installed(): TailnetRuntime = checkNotNull(runtime) { "KoraTailnet.install has not been called" }

    private const val ID_ATTEMPTS = 5

    private fun versionNameOf(application: Application): String = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }?.takeIf { it.isNotBlank() } ?: "unknown"
}

/**
 * A pairing just created. [token] is the secret the agent presents (`Authorization: Bearer <token>`): show it once and
 * do not keep it; the app keeps only [pairing]. Its text form never includes the token.
 */
public class IssuedPairing internal constructor(
    public val pairing: Pairing,
    public val token: String,
) {
    /** The caller this pairing is for the node's policy; see [KoraTailnet.caller]. */
    public val caller: Caller get() = KoraTailnet.caller(pairing)

    override fun toString(): String = "IssuedPairing(id=${pairing.id}, name=${pairing.name}, token=redacted)"
}

/** Everything the installed listener is made of; the service and the API reach it through [KoraTailnet]. */
internal class TailnetRuntime(
    val application: Context,
    val config: TailnetConfig,
    val store: FilePairingStore,
    val controller: TailnetController,
    val scope: CoroutineScope,
) {
    /** The switch off: the session ends and the service, if up, is stopped. */
    fun stop() {
        controller.stop()
        try {
            application.stopService(Intent(application, TailnetService::class.java))
        } catch (e: RuntimeException) {
            Log.w("KoraTailnet", "Could not stop the service", e)
        }
    }
}
