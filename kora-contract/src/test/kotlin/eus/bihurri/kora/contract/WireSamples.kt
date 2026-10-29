package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fitness.ActiveSession
import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.fitness.SessionPhase
import eus.bihurri.kora.contract.fixtures.FixtureCase
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.LocationFixBatch
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.location.StreamRequest
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.contract.media.TrackSummary
import kotlinx.serialization.KSerializer

/**
 * The deterministic sample values behind the golden wire fixtures (`src/test/resources/wire`), defined in one place.
 *
 * Every operation input, operation output and event of every built-in capability has at least one sample, named
 * `<member>.input`, `<member>.output` or `<member>.event` (plus `.<variant>` for extra shapes such as an absent
 * value). The samples describe the **current** version of each capability; the stored fixtures of earlier versions
 * are the history. Changing a sample value changes what a recording writes for a new version, never an old fixture.
 */
object WireSamples {
    /**
     * Version label of the envelope types ([KoraRequest], [KoraResponse], [KoraEvent], [SubscribeRequest],
     * [KoraError], [NodeDescriptor]). Bump the minor when one of them gains a field, then record fixtures.
     */
    const val ENVELOPE_VERSION = "1.0"
    const val ENVELOPE_GROUP = "envelope"

    private const val T0 = 1_700_000_000_000L

    private val track = TrackSummary("track-1", "Title", "Artist", "Album", 215_000, "art-1")
    private val minimalTrack = TrackSummary("track-2", "Only title")
    private val playlist = PlaylistSummary("playlist-1", "Morning run", 12, "art-2")
    private val minimalPlaylist = PlaylistSummary("playlist-2", "Bare", 0)
    private val playing = PlaybackState(PlaybackStatus.PLAYING, 42_000, T0, track, shuffle = true)
    private val session = ActiveSession("session-1", "hiking", SessionPhase.RECORDING, T0, 61_000, 1234.5)
    private val fix = LocationFix(43.26, -2.93, 12.0, 4.5, T0)
    private val minimalFix = LocationFix(0.0, 0.0, timeEpochMs = 1)
    private val play = PlayRecord(track, T0, 61_000)
    private val minimalPlay = PlayRecord(minimalTrack, T0 + 1_000)

    private class Builder(private val capability: Capability) {
        val cases = mutableListOf<FixtureCase<*>>()

        private fun <T> add(name: String, variant: String?, serializer: KSerializer<T>, value: T) {
            val full = if (variant == null) name else "$name.$variant"
            cases += FixtureCase(capability.id.value, capability.version.toString(), full, serializer, value)
        }

        fun <I, O> input(spec: OperationSpec<I, O>, value: I, variant: String? = null) =
            add("${spec.name}.input", variant, spec.inputSerializer, value)

        fun <I, O> output(spec: OperationSpec<I, O>, value: O, variant: String? = null) =
            add("${spec.name}.output", variant, spec.outputSerializer, value)

        fun <E> event(spec: EventSpec<E>, value: E, variant: String? = null) =
            add("${spec.name}.event", variant, spec.serializer, value)
    }

    private fun capability(capability: Capability, define: Builder.() -> Unit): List<FixtureCase<*>> =
        Builder(capability).apply(define).cases

    private val library = capability(MediaLibrary) {
        input(MediaLibrary.ListPlaylists, PageRequest(20, 40))
        output(MediaLibrary.ListPlaylists, Page(listOf(playlist, minimalPlaylist), total = 52, offset = 40))
        output(MediaLibrary.ListPlaylists, Page(emptyList<PlaylistSummary>(), 0, 0), "empty")
        input(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("playlist-1", PageRequest(5, 10)))
        output(MediaLibrary.GetPlaylistTracks, Page(listOf(track, minimalTrack), total = 12, offset = 10))
        input(MediaLibrary.SearchTracks, SearchRequest("rock", PageRequest(10, 0)))
        output(MediaLibrary.SearchTracks, Page(listOf(track), total = 1, offset = 0))
    }

    private val playback = capability(MediaPlayback) {
        for (command in listOf(
            MediaPlayback.Play, MediaPlayback.Pause, MediaPlayback.Toggle, MediaPlayback.Next, MediaPlayback.Previous,
        )) {
            input(command, NoInput)
            output(command, Unit)
        }
        input(MediaPlayback.SeekTo, SeekRequest(90_000))
        output(MediaPlayback.SeekTo, Unit)
        input(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("playlist-1", 3, true))
        output(MediaPlayback.PlayPlaylist, Unit)
        input(MediaPlayback.PlayTracks, PlayTracksRequest(listOf("track-1", "track-2"), 1))
        output(MediaPlayback.PlayTracks, Unit)
        input(MediaPlayback.GetState, NoInput)
        output(MediaPlayback.GetState, playing)
        output(MediaPlayback.GetState, PlaybackState(PlaybackStatus.IDLE), "idle")
        event(MediaPlayback.PlaybackStateChanged, playing)
        event(MediaPlayback.NowPlayingChanged, NowPlaying(track, T0))
    }

    private val history = capability(MediaHistory) {
        input(MediaHistory.ListPlays, PlaysRequest(T0, T0 + 3_600_000, PageRequest(10, 20)))
        output(MediaHistory.ListPlays, Page(listOf(play, minimalPlay), total = 32, offset = 20))
        output(MediaHistory.ListPlays, Page(emptyList<PlayRecord>(), 0, 0), "empty")
    }

    /** The 8-byte PNG signature: small, recognisable bytes that include values above 0x7f. */
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private val artwork = capability(MediaArtwork) {
        input(MediaArtwork.GetArtwork, ArtworkRequest("art-1", 256))
        input(MediaArtwork.GetArtwork, ArtworkRequest("art-2"), "defaults")
        output(MediaArtwork.GetArtwork, ArtworkImage(pngSignature, ArtworkMimeType.PNG, 256, 192))
    }

    private val fitness = capability(FitnessSession) {
        input(FitnessSession.GetActiveSession, NoInput)
        output(FitnessSession.GetActiveSession, session)
        output(FitnessSession.GetActiveSession, null, "none")
        event(FitnessSession.SessionStarted, session)
        event(FitnessSession.SessionEnded, session.copy(phase = SessionPhase.ENDED))
        event(FitnessSession.SessionStateChanged, session.copy(phase = SessionPhase.PAUSED))
    }

    private val location = capability(LocationCapability) {
        input(LocationCapability.GetLastFix, NoInput)
        output(LocationCapability.GetLastFix, fix)
        output(LocationCapability.GetLastFix, null, "none")
        event(LocationCapability.FixChanged, fix)
        event(LocationCapability.FixChanged, minimalFix, "minimal")
        input(LocationCapability.ConfigureStream, StreamRequest(1_000, 5_000))
        input(LocationCapability.ConfigureStream, StreamRequest(), "defaults")
        output(LocationCapability.ConfigureStream, StreamConfig(2_000, 4_000))
        event(LocationCapability.FixesBatched, LocationFixBatch(listOf(fix, minimalFix.copy(timeEpochMs = T0 + 1_000))))
    }

    private fun <T> envelopeCase(name: String, serializer: KSerializer<T>, value: T) =
        FixtureCase(ENVELOPE_GROUP, ENVELOPE_VERSION, name, serializer, value)

    private val envelope: List<FixtureCase<*>> = listOf(
        envelopeCase(
            "kora_request",
            KoraRequest.serializer(),
            KoraRequest("kardio-req-1", "media.playback", CapabilityVersion(1, 0), "seek_to", MediaPlayback.SeekTo.encodeInput(CborCodec, SeekRequest(90_000))),
        ),
        envelopeCase(
            "kora_response.success",
            KoraResponse.serializer(),
            KoraResponse.Success("kardio-req-1", MediaPlayback.GetState.encodeOutput(CborCodec, playing)),
        ),
        envelopeCase(
            "kora_response.failure",
            KoraResponse.serializer(),
            KoraResponse.Failure("kardio-req-1", KoraError(ErrorCode.INCOMPATIBLE_VERSION, "'media.playback' is at 1.0, which cannot serve 1.2")),
        ),
        envelopeCase("kora_error", KoraError.serializer(), KoraError(ErrorCode.UNAUTHORIZED, "Scope 'media.playback.control' is not granted")),
        envelopeCase(
            "subscribe_request",
            SubscribeRequest.serializer(),
            SubscribeRequest("kardio-sub-1", "location", CapabilityVersion(1, 1), "fixes_batched"),
        ),
        envelopeCase(
            "kora_event",
            KoraEvent.serializer(),
            KoraEvent("kardio-sub-1", "location", "fix_changed", LocationCapability.FixChanged.encode(CborCodec, fix)),
        ),
        envelopeCase(
            "node_descriptor",
            NodeDescriptor.serializer(),
            NodeDescriptor(
                "eus.bihurri.sonora",
                "Sonora",
                listOf(CapabilityDescriptor("media.library", CapabilityVersion(1, 0)), CapabilityDescriptor("media.playback", CapabilityVersion(1, 0))),
                koraVersion = "1.0.0",
            ),
        ),
    )

    /** All samples of the current code. */
    val cases: List<FixtureCase<*>> = library + playback + history + artwork + fitness + location + envelope

    /** Operation inputs, outputs and events of a built-in capability that a sample must cover. */
    fun requiredNames(capability: Capability): Set<String> =
        capability.operations.flatMap { listOf("${it.name}.input", "${it.name}.output") }.toSet() +
            capability.events.map { "${it.name}.event" }
}
