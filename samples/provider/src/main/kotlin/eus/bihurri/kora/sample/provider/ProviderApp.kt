package eus.bihurri.kora.sample.provider

import android.app.Application
import android.util.Log
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.tailnet.KoraTailnet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

const val TAG = "KoraSample"

/**
 * Provides a fake music library with generated cover art and a fake player, to show what a peer app sees. It also
 * opens the node to the adb shell through [SampleExternalAccess] (see `content call` in the kora-android README)
 * and, through [TailnetSample], to paired agents over Tailscale (README, "Tailnet test").
 */
class ProviderApp : Application() {
    private val playlists = listOf(
        playlist(id = "pl-1", name = "Morning run", trackCount = 3),
        playlist(id = "pl-2", name = "Late night", trackCount = 2),
    )
    private val artwork = SampleArtwork(playlistIds = playlists.map { it.id }.toSet())
    private val tracks = mapOf(
        "pl-1" to listOf(track(1), track(2), track(3)),
        "pl-2" to listOf(track(4), track(5)),
    )
    private val catalog = tracks.values.flatten()
    private val state = MutableStateFlow(PlaybackState(status = PlaybackStatus.PAUSED, track = track(1)))
    private val queueLock = Any()
    private var queue: List<TrackSummary> = tracks.getValue("pl-1")
    private var queueIndex = 0

    private lateinit var node: KoraNode

    override fun onCreate() {
        super.onCreate()
        node = KoraAndroid.install(
            this,
            policy = DefaultCallerPolicy(SampleExternalAccess(isPairedAgent = ::isPairedAgent)),
            audit = AuditLog { Log.i(TAG, "audit $it") },
        ) {
            provideSampleCapabilities()
        }
        TailnetSample.install(this, node)
        Log.i(TAG, "provider installed: node=${node.nodeId}")
    }

    /** Applies the auto-off period chosen in the UI (switches the agent access off; pairings are kept). */
    fun setShortIdle(shortIdle: Boolean) = TailnetSample.reinstall(this, node, shortIdle)

    /** True while [caller] is one of the existing pairings (the sample's auto-grant, see [SampleExternalAccess]). */
    private fun isPairedAgent(caller: Caller): Boolean =
        KoraTailnet.pairings().any { KoraTailnet.caller(it) == caller }

    private fun KoraNode.provideSampleCapabilities() {
        provide(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page ->
                Log.i(TAG, "ListPlaylists by $caller")
                paged(playlists, page)
            }
            on(MediaLibrary.GetPlaylistTracks) { request: PlaylistTracksRequest ->
                paged(tracks[request.playlistId].orEmpty(), request.page)
            }
            on(MediaLibrary.SearchTracks) { request ->
                val needle = request.query.trim()
                paged(catalog.filter { it.matches(needle) }, request.page)
            }
        }

        provideSampleArtwork(artwork, ArtworkRenderer::renderPng)

        provide(MediaPlayback) {
            on(MediaPlayback.Play) { update { it.copy(status = PlaybackStatus.PLAYING) } }
            on(MediaPlayback.Pause) { update { it.copy(status = PlaybackStatus.PAUSED) } }
            on(MediaPlayback.Toggle) {
                update { it.copy(status = if (it.status == PlaybackStatus.PLAYING) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING) }
            }
            on(MediaPlayback.Next) { skip(+1) }
            on(MediaPlayback.Previous) { skip(-1) }
            on(MediaPlayback.SeekTo) { request ->
                update { current ->
                    val duration = current.track?.durationMs ?: 0
                    current.copy(positionMs = request.positionMs.coerceIn(0, duration))
                }
            }
            on(MediaPlayback.PlayPlaylist) { request ->
                val playlist = tracks[request.playlistId]
                    ?: throw invalid("Unknown playlist '${request.playlistId}'")
                startQueue(if (request.shuffle) playlist.shuffled() else playlist, request.startIndex, request.shuffle)
            }
            on(MediaPlayback.PlayTracks) { request ->
                val selected = request.trackIds.map { id -> catalog.firstOrNull { it.id == id } ?: throw invalid("Unknown track '$id'") }
                startQueue(selected, request.startIndex, shuffle = false)
            }
            on(MediaPlayback.GetState) { state.value }
            emits(MediaPlayback.PlaybackStateChanged) { state }
            emits(MediaPlayback.NowPlayingChanged) {
                Log.i(TAG, "NowPlayingChanged subscribed by $caller")
                flow {
                    var index = 10
                    while (true) {
                        val next = track(index++)
                        update { it.copy(track = next, positionMs = 0) }
                        emit(NowPlaying(next, startedAtEpochMs = System.currentTimeMillis()))
                        delay(3_000)
                    }
                }
            }
        }
    }

    private fun <T> paged(all: List<T>, page: PageRequest): Page<T> =
        Page(all.drop(page.offset).take(page.limit), total = all.size, offset = page.offset)

    private fun TrackSummary.matches(needle: String): Boolean =
        title.contains(needle, ignoreCase = true) ||
            artist.orEmpty().contains(needle, ignoreCase = true) ||
            album.orEmpty().contains(needle, ignoreCase = true)

    /** Applies [change] to the state, first settling the position a playing track advanced since the last update. */
    private fun update(change: (PlaybackState) -> PlaybackState) {
        val now = System.currentTimeMillis()
        state.update { current ->
            val elapsed = if (current.status == PlaybackStatus.PLAYING) now - current.updatedAtEpochMs else 0
            val settled = current.copy(positionMs = (current.positionMs + elapsed).coerceAtMost(current.track?.durationMs ?: 0))
            change(settled).copy(updatedAtEpochMs = now)
        }
    }

    private fun skip(step: Int) {
        synchronized(queueLock) {
            if (queue.isEmpty()) return
            queueIndex = (queueIndex + step).mod(queue.size)
            val current = queue[queueIndex]
            update { it.copy(track = current, positionMs = 0) }
        }
    }

    private fun startQueue(newQueue: List<TrackSummary>, startIndex: Int, shuffle: Boolean) {
        if (startIndex !in newQueue.indices) throw invalid("startIndex $startIndex is outside the ${newQueue.size} queued tracks")
        synchronized(queueLock) {
            queue = newQueue
            queueIndex = startIndex
            update { it.copy(status = PlaybackStatus.PLAYING, track = newQueue[startIndex], positionMs = 0, shuffle = shuffle) }
        }
    }

    private fun invalid(message: String) = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, message))

    private fun track(n: Int) = TrackSummary(
        id = "t-$n",
        title = "Track $n",
        artist = "Sample Artist",
        album = "Sample Album",
        durationMs = 180_000,
        artworkId = SampleArtwork.trackArtworkId(n),
    )

    private fun playlist(id: String, name: String, trackCount: Int) = PlaylistSummary(
        id = id,
        name = name,
        trackCount = trackCount,
        artworkId = SampleArtwork.playlistArtworkId(id),
    )
}
