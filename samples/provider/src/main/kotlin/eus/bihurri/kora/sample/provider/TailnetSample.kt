package eus.bihurri.kora.sample.provider

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.mcp.http.DeniedAttempt
import eus.bihurri.kora.tailnet.KoraTailnet
import eus.bihurri.kora.tailnet.TailnetConfig
import eus.bihurri.kora.tailnet.TailnetNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Wires `kora-tailnet` into the sample for the device test (README, "Tailnet test"). The sample is test-only and
 * never published; the choices below are the test's, not recommendations.
 */
object TailnetSample {
    /** Fixed so the URL `http://<tailscale address>:8765/mcp` stays the same across switches and restarts. */
    const val PORT = 8765

    /** Auto-off period for normal use of the test. */
    val IDLE_TIMEOUT: Duration = 5.minutes

    /** Shorter period, chosen in the UI, to check the auto-off without waiting five minutes. */
    val SHORT_IDLE_TIMEOUT: Duration = 1.minutes

    private const val PREFS = "tailnet-sample"
    private const val KEY_SHORT_IDLE = "short_idle"
    private const val MAX_DENIED = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deniedJob: Job? = null
    private val deniedList = MutableStateFlow<List<DeniedAttempt>>(emptyList())

    /** The last refused requests (newest first), kept for the process lifetime: nothing is stored on disk. */
    val denied: StateFlow<List<DeniedAttempt>> get() = deniedList

    /** Whether the short (1 minute) auto-off period is selected. Persisted; applied by [install] and [reinstall]. */
    fun isShortIdle(context: Context): Boolean = prefs(context).getBoolean(KEY_SHORT_IDLE, false)

    /** Call once from `Application.onCreate`, after the node is installed. Nothing listens until the user's switch. */
    @Synchronized
    fun install(app: Application, node: KoraNode) {
        val idle = if (isShortIdle(app)) SHORT_IDLE_TIMEOUT else IDLE_TIMEOUT
        KoraTailnet.install(
            app,
            node,
            TailnetConfig(
                port = PORT,
                idleTimeout = idle,
                notification = TailnetNotification(
                    smallIcon = R.drawable.ic_stat_agent,
                    contentIntent = PendingIntent.getActivity(
                        app,
                        0,
                        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ),
            ),
        )
        deniedJob?.cancel()
        deniedJob = scope.launch {
            KoraTailnet.denied.collect { attempt -> deniedList.update { (listOf(attempt) + it).take(MAX_DENIED) } }
        }
    }

    /**
     * Applies a new auto-off period. The period is fixed at install time, so this switches the listener off,
     * forgets the installation and installs again; pairings are kept (they live in a file). The activity must
     * recreate itself afterwards, because the flows it observed belonged to the old installation.
     */
    @Synchronized
    fun reinstall(app: Application, node: KoraNode, shortIdle: Boolean) {
        prefs(app).edit().putBoolean(KEY_SHORT_IDLE, shortIdle).apply()
        KoraTailnet.uninstall()
        install(app, node)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
