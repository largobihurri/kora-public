package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.fitness.ActiveSession
import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.fitness.SessionPhase
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.AuditEntry
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

val AGENT = Caller(CallerKind.REMOTE_AGENT, "agent-7")

val SERVER_INFO = McpServerInfo("kora-test", "1.2.3", title = "Kora test", instructions = "Use the tools.")

/** Master switch and per-caller grants controlled by the test. */
class TestAccess(var enabled: Boolean = true) : ExternalAccess {
    val grants = mutableMapOf<Caller, Set<Scope>>()

    override suspend fun isEnabled(): Boolean = enabled

    override suspend fun grantedScopes(caller: Caller): Set<Scope> = grants[caller].orEmpty()
}

class TestAudit : AuditLog {
    val entries = mutableListOf<AuditEntry>()

    override fun record(entry: AuditEntry) {
        entries += entry
    }
}

val playlists = List(3) { PlaylistSummary("pl-$it", "Playlist $it", it + 1, artworkId = if (it == 0) "art-0" else null) }
val trackOne = TrackSummary("t1", "Song", "Artist", durationMs = 1_000)
val plays = listOf(PlayRecord(trackOne, startedAtEpochMs = 1_000, playedMs = 900), PlayRecord(trackOne, startedAtEpochMs = 5_000))
val activeSession = ActiveSession("s1", "hiking", SessionPhase.RECORDING, startedAtEpochMs = 5, elapsedMs = 10, distanceMeters = 1.5)
val lastFix = LocationFix(43.26, -2.93, timeEpochMs = 99)
val artworkImage = ArtworkImage(byteArrayOf(1, 2, 3), ArtworkMimeType.PNG, widthPx = 2, heightPx = 2)

/** Gives [this] node every built-in capability, answering with fixed data. */
fun KoraNode.provideEverything(onCall: (String) -> Unit = {}) {
    provideStubbed(MediaLibrary) {
        on(MediaLibrary.ListPlaylists) { page ->
            onCall("list_playlists")
            Page(playlists.drop(page.offset).take(page.limit), playlists.size, page.offset)
        }
        on(MediaLibrary.GetPlaylistTracks) { Page(listOf(trackOne), 1, 0) }
        on(MediaLibrary.SearchTracks) { Page(listOf(trackOne), 1, 0) }
    }
    provideStubbed(MediaPlayback) {
        on(MediaPlayback.Play) { onCall("play") }
        on(MediaPlayback.Pause) { onCall("pause") }
        on(MediaPlayback.Toggle) { onCall("toggle") }
        on(MediaPlayback.Next) { onCall("next") }
        on(MediaPlayback.Previous) { onCall("previous") }
        on(MediaPlayback.SeekTo) { onCall("seek_to") }
        on(MediaPlayback.PlayPlaylist) { onCall("play_playlist") }
        on(MediaPlayback.PlayTracks) { onCall("play_tracks") }
        on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.PLAYING, 1_000, 2, trackOne, shuffle = true) }
    }
    provideStubbed(MediaHistory) {
        on(MediaHistory.ListPlays) { request ->
            onCall("list_plays")
            val inWindow = plays.filter { it.startedAtEpochMs in request.fromEpochMs..request.toEpochMs }
            Page(inWindow.drop(request.page.offset).take(request.page.limit), inWindow.size, request.page.offset)
        }
    }
    provideStubbed(MediaArtwork) {
        on(MediaArtwork.GetArtwork) {
            onCall("get_artwork")
            artworkImage
        }
    }
    provideStubbed(FitnessSession) { on(FitnessSession.GetActiveSession) { activeSession } }
    provideStubbed(LocationCapability) {
        on(LocationCapability.GetLastFix) { lastFix }
        on(LocationCapability.ConfigureStream) { StreamConfig(it.desiredIntervalMs, it.maxLatencyMs) }
    }
}

private val parser = Json

fun parse(text: String?): JsonObject = parser.parseToJsonElement(checkNotNull(text) { "no message" }).jsonObject

fun rpc(method: String, params: JsonObject? = null, id: JsonElement? = JsonPrimitive(1)): String =
    buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        id?.let { put("id", it) }
        put("method", JsonPrimitive(method))
        params?.let { put("params", it) }
    }.toString()

/** `_meta` of a stateless-era request. */
fun modernMeta(version: String = McpProtocol.LATEST_VERSION): JsonObject = parse(
    """{"io.modelcontextprotocol/protocolVersion":"$version","io.modelcontextprotocol/clientInfo":{"name":"t","version":"1"},"io.modelcontextprotocol/clientCapabilities":{}}""",
)

fun withMeta(params: String = "{}", version: String = McpProtocol.LATEST_VERSION): JsonObject =
    JsonObject(parse(params) + ("_meta" to modernMeta(version)))

val JsonObject.errorCode: Int get() = (this["error"] as JsonObject)["code"]!!.toString().toInt()

val JsonObject.resultObject: JsonObject get() = this["result"]!!.jsonObject
