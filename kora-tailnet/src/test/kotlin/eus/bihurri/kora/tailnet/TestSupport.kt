package eus.bihurri.kora.tailnet

import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun address(text: String): InetAddress = InetAddress.getByName(text)

/** A free TCP port on loopback (the listener's port is fixed, so tests pick one up front). */
fun freePort(): Int = ServerSocket(0, 1, address("127.0.0.1")).use { it.localPort }

/** Reports addresses when the test says so, like the network callback would. */
internal class FakeAddressSource : TailnetAddressSource {
    var initial: InetAddress? = null
    var failOnStart: Exception? = null
    var started = false
        private set
    var startCount = 0
        private set
    private var report: ((InetAddress?) -> Unit)? = null

    /** The callback of every start, in order: an old one must not reach a later session. */
    val callbacks = mutableListOf<(InetAddress?) -> Unit>()

    override fun start(onAddress: (InetAddress?) -> Unit) {
        failOnStart?.let { throw it }
        started = true
        startCount++
        report = onAddress
        callbacks += onAddress
        onAddress(initial)
    }

    override fun stop() {
        started = false
        report = null
    }

    fun emit(address: InetAddress?) {
        report?.invoke(address)
    }
}

/** Records what the controller asks of the alarm. */
internal class FakeAlarm : DeadlineAlarm {
    val scheduled = mutableListOf<Long>()
    var cancelled = 0
        private set

    override fun scheduleAt(elapsedRealtimeNanos: Long) {
        scheduled += elapsedRealtimeNanos
    }

    override fun cancel() {
        cancelled++
    }
}

/** Master switch on and per-caller grants controlled by the test; records who was refused. */
class TestAccess : ExternalAccess {
    val grants = ConcurrentHashMap<Caller, Set<Scope>>()
    val refused = CopyOnWriteArrayList<Pair<Caller, Scope>>()

    override suspend fun isEnabled(): Boolean = true

    override suspend fun grantedScopes(caller: Caller): Set<Scope> = grants[caller].orEmpty()

    override suspend fun onDenied(caller: Caller, scope: Scope) {
        refused += caller to scope
    }
}

val playlists = List(3) { PlaylistSummary("pl-$it", "Playlist $it", it + 1) }

fun testNode(access: ExternalAccess = TestAccess()): KoraNode =
    KoraNode(nodeId = "kardio", policy = DefaultCallerPolicy(access)).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page -> Page(playlists.drop(page.offset).take(page.limit), playlists.size, page.offset) }
        }
    }

class HttpAnswer(val status: Int, val body: String)

/** One POST to the MCP endpoint over a fresh connection; a refused request is an answer, not an exception. */
fun post(host: String, port: Int, body: String, token: String?, headers: Map<String, String> = emptyMap()): HttpAnswer {
    val url = if (':' in host) "http://[$host]:$port/mcp" else "http://$host:$port/mcp"
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
        return HttpAnswer(connection.responseCode, stream?.use { String(it.readBytes()) }.orEmpty())
    } finally {
        connection.disconnect()
    }
}

const val PING = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

private const val MODERN = "2026-07-28"
private const val MODERN_META =
    """{"io.modelcontextprotocol/protocolVersion":"$MODERN","io.modelcontextprotocol/clientInfo":{"name":"t","version":"1"},"io.modelcontextprotocol/clientCapabilities":{}}"""

/** A stateless-era `tools/call` of `list_playlists` and the headers that go with it. */
const val LIST_PLAYLISTS_CALL =
    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"_meta":$MODERN_META,"name":"media_library__list_playlists","arguments":{"limit":2}}}"""

val LIST_PLAYLISTS_HEADERS = mapOf(
    "MCP-Protocol-Version" to MODERN,
    "Mcp-Method" to "tools/call",
    "Mcp-Name" to "media_library__list_playlists",
)

/** Collects [flow] into [into] on this scope; cancel the returned job when done. */
fun <T> CoroutineScope.launchCollecting(flow: Flow<T>, into: MutableList<T>) =
    launch { flow.collect { into += it } }

/** The first value [flow] emits, collected from now on. */
fun <T> CoroutineScope.launchFirst(flow: Flow<T>): Deferred<T> {
    val first = CompletableDeferred<T>()
    launch { first.complete(flow.first()) }
    return first
}
