package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.Target
import eus.bihurri.kora.wear.KoraWear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Checks the relay from this device towards the other one: refresh, list playlists, subscribe, cancel.
 * Every step is reported as one greppable line: `KoraWearCheck step=<name> ok=<true|false> detail=<...>`.
 */
class CheckRunner(
    private val node: KoraNode,
    private val wear: KoraWear,
    private val report: (String) -> Unit,
) {
    private var passed = 0
    private var failed = 0

    suspend fun run() {
        report("$TAG start")
        val peerId = refresh()
        if (peerId == null) {
            skip("list_playlists")
            skip("subscribe")
            skip("cancel")
        } else {
            listPlaylists(peerId)
            val eventsAtCancel = subscribe(peerId)
            cancel(eventsAtCancel)
        }
        report("$TAG step=summary ok=${failed == 0} passed=$passed failed=$failed")
    }

    /** Asks the other device for its peers and waits for a `wear:` one. Returns its id, or null if none showed up. */
    private suspend fun refresh(): String? {
        wear.requestRefresh()
        val found = withTimeoutOrNull(REFRESH_WAIT) {
            node.peers.first { peers -> peers.any { it.nodeId.startsWith(WEAR_PREFIX) } }
        }
        val ids = node.peers.value.map { it.nodeId }.filter { it.startsWith(WEAR_PREFIX) }
        record("refresh", found != null, "wearPeers=$ids")
        // The other device's own app is also listed; prefer whichever wear: peer offers the capability we call.
        return node.providers(MediaLibrary).firstOrNull { it.startsWith(WEAR_PREFIX) } ?: ids.firstOrNull()
    }

    private suspend fun listPlaylists(peerId: String) = step("list_playlists") {
        val page = node.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer(peerId))
        val names = page.items.map { it.name }
        true to "peer=$peerId total=${page.total} names=$names"
    }

    /** Collects for [SUBSCRIBE_WINDOW]; returns the number of events received, so [cancel] can check none follow. */
    private suspend fun subscribe(peerId: String): AtomicInteger {
        val count = AtomicInteger()
        val titles = CopyOnWriteArrayList<String>()
        var error: String? = null
        step("subscribe") {
            coroutineScope {
                val collector: Job = launch {
                    try {
                        node.events(MediaPlayback.NowPlayingChanged, Target.Peer(peerId)).collect { event ->
                            titles += event.track.title
                            report("$TAG step=event ok=true detail=${event.track.title} n=${count.incrementAndGet()}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = describe(e)
                    }
                }
                delay(SUBSCRIBE_WINDOW)
                collector.cancelAndJoin()
            }
            (error == null && count.get() >= 2) to "peer=$peerId count=${count.get()} titles=$titles" +
                (error?.let { " error=$it" } ?: "")
        }
        return count
    }

    /** The collector is gone (see [subscribe]); wait and confirm its event counter no longer moves. */
    private suspend fun cancel(eventsAtCancel: AtomicInteger) = step("cancel") {
        val before = eventsAtCancel.get()
        delay(CANCEL_WAIT)
        val after = eventsAtCancel.get()
        // The serving device logs "served=NowPlayingChanged state=ended" when it learns about the cancel.
        (after == before) to "eventsAtCancel=$before eventsAfterWait=$after"
    }

    private suspend fun step(name: String, block: suspend () -> Pair<Boolean, String>) {
        try {
            val (ok, detail) = block()
            record(name, ok, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            record(name, false, "error=${describe(e)}")
        }
    }

    private fun skip(name: String) = record(name, false, "skipped: no wear peer")

    private fun record(name: String, ok: Boolean, detail: String) {
        if (ok) passed++ else failed++
        report("$TAG step=$name ok=$ok detail=$detail")
    }

    private fun describe(e: Throwable): String =
        (e as? KoraException)?.let { "${it.error.code.wireName}: ${it.error.message}" } ?: "${e.javaClass.simpleName}: ${e.message}"

    private companion object {
        const val WEAR_PREFIX = "wear:"
        val REFRESH_WAIT = 20.seconds
        val SUBSCRIBE_WINDOW = 7.seconds
        val CANCEL_WAIT = 3.seconds
    }
}
