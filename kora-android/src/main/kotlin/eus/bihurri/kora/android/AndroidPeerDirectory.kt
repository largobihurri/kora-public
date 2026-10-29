package eus.bihurri.kora.android

import android.util.Log
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.core.PeerDirectory
import eus.bihurri.kora.core.PeerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** An installed package that serves a Kora node and is signed with our key. [stamp] changes when it is updated. */
internal data class PeerCandidate(val packageName: String, val stamp: String)

/** Port: which trusted packages serve a node right now. */
internal fun interface PeerScanner {
    fun scan(): List<PeerCandidate>
}

/** Port: reads the descriptor a peer advertises. May fail with any exception. */
internal fun interface DescriptorFetcher {
    suspend fun fetch(packageName: String): NodeDescriptor
}

/** Port: tells when packages were installed, replaced, removed or changed. */
internal fun interface PackageChangeSource {
    /** Calls [onChange] on every change until the result is closed. */
    fun register(onChange: () -> Unit): AutoCloseable
}

/**
 * Finds the peer apps on the device and what each one provides.
 *
 * Peers are packages that handle the [KoraAndroid.ACTION_NODE] action **and** are signed with our key; apps that
 * are not never appear. The node id of a peer is its package name: the directory rewrites
 * `NodeDescriptor.nodeId` to the package, so routing, the caller id the peer sees and the bound service always
 * agree on one name. Apps should use their package name as node id, which `KoraAndroid.install` does.
 *
 * A descriptor is fetched once per package version (the [PeerCandidate.stamp]) and kept in memory; package
 * broadcasts trigger a rescan, debounced by [debounce], and only new or updated packages are fetched again.
 * Capabilities a running peer registers later, without an update of its package, are not noticed.
 */
internal class AndroidPeerDirectory(
    private val scanner: PeerScanner,
    private val fetcher: DescriptorFetcher,
    private val changes: PackageChangeSource,
    private val scope: CoroutineScope,
    private val fetchTimeout: Duration = 5.seconds,
    private val debounce: Duration = 250.milliseconds,
) : PeerDirectory {
    private val state = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val peers: StateFlow<List<PeerInfo>> = state

    private class Cached(val stamp: String, val descriptor: NodeDescriptor)

    // Concurrent: refresh() fans out one describe() per candidate, and those run in parallel on the scope's dispatcher.
    private val cache = ConcurrentHashMap<String, Cached>()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var registration: AutoCloseable? = null

    /** Starts watching package changes and scans once now. Idempotent. */
    @Synchronized
    fun start() {
        if (loop != null) return
        registration = changes.register { requests.trySend(Unit) }
        loop = scope.launch {
            for (request in requests) {
                delay(debounce)
                refresh()
            }
        }
        requests.trySend(Unit)
    }

    @Synchronized
    fun close() {
        registration?.close()
        registration = null
        loop?.cancel()
        loop = null
    }

    /** Rescans now. Public to tests; production code goes through [start]. */
    suspend fun refresh() {
        val candidates = try {
            scanner.scan()
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot list peers", e)
            return
        }
        cache.keys.retainAll(candidates.map { it.packageName }.toSet())
        val found = coroutineScope {
            candidates.map { candidate -> async { describe(candidate) } }.awaitAll()
        }
        val list = found.filterNotNull().sortedBy { it.nodeId }
        if (list != state.value) state.value = list
    }

    private suspend fun describe(candidate: PeerCandidate): PeerInfo? {
        cache[candidate.packageName]?.let { cached ->
            if (cached.stamp == candidate.stamp) return PeerInfo(cached.descriptor)
        }
        val descriptor = try {
            withTimeoutOrNull(fetchTimeout) { fetcher.fetch(candidate.packageName) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "No descriptor from ${candidate.packageName}", e)
            null
        }
        if (descriptor == null) {
            cache.remove(candidate.packageName)
            return null
        }
        val normalized = descriptor.copy(nodeId = candidate.packageName)
        cache[candidate.packageName] = Cached(candidate.stamp, normalized)
        return PeerInfo(normalized)
    }

    private companion object {
        const val TAG = "KoraAndroid"
    }
}
