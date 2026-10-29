package eus.bihurri.kora.sample.wearcheck

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.wear.KoraWear
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds

const val TAG = "KoraWearCheck"

/**
 * The same app runs on the phone and on the watch. Each side provides stub media data that names its role, so the
 * check can tell whose answer it got, and each side serves the other through [KoraWear].
 */
class WearCheckApp : Application() {
    lateinit var wear: KoraWear
        private set
    lateinit var node: KoraNode
        private set

    /** `watch` on a Wear OS device, `phone` anywhere else. */
    val role: String by lazy {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) "watch" else "phone"
    }

    override fun onCreate() {
        super.onCreate()
        // Calls over the Data Layer can take a few seconds, so allow more than the 10 s / 5 s defaults.
        wear = KoraWear.install(this, callTimeout = CALL_TIMEOUT)
        node = KoraAndroid.install(
            this,
            // The default policy already trusts SELF and PEER (the counterpart is served as PEER "wear:<package>")
            // and keeps every external caller (adb shell, other apps) out: external access is off.
            audit = AuditLog { entry ->
                Log.i(TAG, "$TAG served=${entry.member} caller=${entry.caller.id} kind=${entry.caller.kind} outcome=${entry.outcome}")
            },
            callTimeout = CALL_TIMEOUT,
            peerRoutes = listOf(wear.route),
        ) {
            provideStubs()
        }
        wear.serve(node)
        Log.i(TAG, "$TAG installed role=$role node=${node.nodeId}")
    }

    private fun KoraNode.provideStubs() {
        val playlists = listOf(
            PlaylistSummary(id = "$role-1", name = "$role-1", trackCount = 2),
            PlaylistSummary(id = "$role-2", name = "$role-2", trackCount = 1),
        )
        val tracks = mapOf(
            "$role-1" to listOf(track(1), track(2)),
            "$role-2" to listOf(track(3)),
        )
        val catalog = tracks.values.flatten()
        val state = MutableStateFlow(PlaybackState(status = PlaybackStatus.PAUSED, track = track(1)))

        provide(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page -> paged(playlists, page) }
            on(MediaLibrary.GetPlaylistTracks) { request -> paged(tracks[request.playlistId].orEmpty(), request.page) }
            on(MediaLibrary.SearchTracks) { request ->
                paged(catalog.filter { it.title.contains(request.query.trim(), ignoreCase = true) }, request.page)
            }
        }

        provide(MediaPlayback) {
            on(MediaPlayback.Play) { state.update { it.copy(status = PlaybackStatus.PLAYING) } }
            on(MediaPlayback.Pause) { state.update { it.copy(status = PlaybackStatus.PAUSED) } }
            on(MediaPlayback.Toggle) {
                state.update { it.copy(status = if (it.status == PlaybackStatus.PLAYING) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING) }
            }
            on(MediaPlayback.Next) { state.update { it.copy(track = track(2), positionMs = 0) } }
            on(MediaPlayback.Previous) { state.update { it.copy(track = track(1), positionMs = 0) } }
            on(MediaPlayback.SeekTo) { request -> state.update { it.copy(positionMs = request.positionMs.coerceAtLeast(0)) } }
            on(MediaPlayback.PlayPlaylist) { request ->
                val first = tracks[request.playlistId]?.firstOrNull()
                    ?: throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown playlist '${request.playlistId}'"))
                state.update { it.copy(status = PlaybackStatus.PLAYING, track = first, positionMs = 0) }
            }
            on(MediaPlayback.PlayTracks) { request ->
                val first = request.trackIds.firstNotNullOfOrNull { id -> catalog.firstOrNull { it.id == id } }
                    ?: throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown tracks"))
                state.update { it.copy(status = PlaybackStatus.PLAYING, track = first, positionMs = 0) }
            }
            on(MediaPlayback.GetState) { state.value }
            emits(MediaPlayback.PlaybackStateChanged) { state }
            emits(MediaPlayback.NowPlayingChanged) {
                Log.i(TAG, "$TAG served=NowPlayingChanged state=collecting caller=$caller")
                flow {
                    var n = 1
                    while (true) {
                        emit(NowPlaying(track(n++), startedAtEpochMs = System.currentTimeMillis()))
                        delay(EVENT_INTERVAL)
                    }
                }.onCompletion { Log.i(TAG, "$TAG served=NowPlayingChanged state=ended caller=$caller") }
            }
        }
    }

    private fun <T> paged(all: List<T>, page: PageRequest): Page<T> =
        Page(all.drop(page.offset).take(page.limit), total = all.size, offset = page.offset)

    /** Track `n` as it would be played on this device; for events, `n` counts up from 1 per subscription. */
    private fun track(n: Int) = TrackSummary(id = "$role-t$n", title = "$role track $n", artist = role, durationMs = 180_000)

    private companion object {
        val CALL_TIMEOUT = 20.seconds
        val EVENT_INTERVAL = 2.seconds
    }
}
