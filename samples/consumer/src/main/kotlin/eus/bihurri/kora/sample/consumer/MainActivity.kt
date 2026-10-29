package eus.bihurri.kora.sample.consumer

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.Target
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Logs what a peer offers (grep `KoraSample`) and mirrors the same lines on screen. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var output: TextView
    private val lines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(output)
    }

    override fun onStart() {
        super.onStart()
        val node = KoraAndroid.node
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
}
