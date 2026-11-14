package eus.bihurri.kora.sample.consumer

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Logs what a peer offers (grep `KoraSample`) and mirrors the same lines on screen. "Fetch artwork" resolves one
 * artwork from the provider peer over Binder and reports mime type, size, bytes and round-trip time. Started with
 * the extra [CHECK_EXTRA] (`am start ... --es check <name>`), it runs that one stream check ([StreamChecks]) instead.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var output: TextView
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(Button(this@MainActivity).apply {
                text = "Fetch artwork"
                setOnClickListener { fetchArtwork() }
            })
            addView(output)
        }
        // Edge-to-edge is enforced on current Android: keep the content clear of the system bars.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onStart() {
        super.onStart()
        val node = KoraAndroid.node
        intent.getStringExtra(CHECK_EXTRA)?.let { check ->
            scope.launch {
                awaitProvider(node, FilesCapability)
                StreamChecks(node, ::log).run(check)
            }
            return
        }
        scope.launch {
            node.peers.collect { peers -> log("peers=${peers.map { it.nodeId }}") }
        }
        scope.launch {
            try {
                awaitProvider(node, MediaLibrary)
                val page = node.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Any)
                log("playlists=${page.total} first=${page.items.firstOrNull()?.name}")
            } catch (e: KoraException) {
                log("error=${e.error.code.wireName}")
            }
        }
        scope.launch {
            awaitProvider(node, MediaPlayback)
            node.events(MediaPlayback.NowPlayingChanged)
                .catch { e -> log("error=${(e as? KoraException)?.error?.code?.wireName ?: e.javaClass.simpleName}") }
                .collect { log("nowPlaying=${it.track.title}") }
        }
    }

    override fun onStop() {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Calls `get_artwork` on the provider peer over Binder for the artwork of its first listed track ([FALLBACK_ARTWORK_ID]
     * when none is listed) and logs `artwork id=... peer=... mime=... px=WxH bytes=... ms=...`.
     */
    private fun fetchArtwork() {
        val node = KoraAndroid.node
        scope.launch {
            try {
                awaitProvider(node, MediaArtwork)
                val peer = node.providers(MediaArtwork).firstOrNull()
                if (peer == null) {
                    log("artwork error=no peer provides ${MediaArtwork.id.value}")
                    return@launch
                }
                val artworkId = firstTrackArtworkId(node, peer) ?: FALLBACK_ARTWORK_ID
                val started = TimeSource.Monotonic.markNow()
                val image = node.call(MediaArtwork.GetArtwork, ArtworkRequest(artworkId, ARTWORK_EDGE_PX), Target.Peer(peer))
                val ms = started.elapsedNow().inWholeMilliseconds
                log(
                    "artwork id=$artworkId peer=$peer mime=${image.mimeType.wireName} " +
                        "px=${image.widthPx}x${image.heightPx} bytes=${image.bytes.size} ms=$ms",
                )
            } catch (e: KoraException) {
                log("artwork error=${e.error.code.wireName}: ${e.error.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A check app logs every failure instead of crashing on one the node did not wrap.
                log("artwork error=${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /** The first listed track's artwork id, or null when the peer lists none or cannot list (no `media.library`). */
    private suspend fun firstTrackArtworkId(node: KoraNode, peer: String): String? = try {
        val playlist = node.call(MediaLibrary.ListPlaylists, PageRequest(limit = 1), Target.Peer(peer)).items.firstOrNull()
        playlist?.let {
            node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest(it.id, PageRequest(limit = 1)), Target.Peer(peer))
                .items.firstOrNull()?.artworkId
        }
    } catch (_: KoraException) {
        null
    }

    /** Discovery runs in the background after start-up, so wait (briefly) until a peer offers [capability]. */
    private suspend fun awaitProvider(node: KoraNode, capability: Capability) {
        withTimeoutOrNull(10.seconds) { node.availability(capability).first { it } }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        val text = synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 20) lines.removeFirst()
            lines.joinToString("\n")
        }
        runOnUiThread { output.text = text }
    }

    private companion object {
        const val FALLBACK_ARTWORK_ID = "art-t-1"
        const val ARTWORK_EDGE_PX = 256
    }
}

/** Intent extra naming the stream check to run (`samples/stream-checks.sh`). */
const val CHECK_EXTRA = "check"
