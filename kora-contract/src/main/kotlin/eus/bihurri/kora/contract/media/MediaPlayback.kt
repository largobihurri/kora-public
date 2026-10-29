package eus.bihurri.kora.contract.media

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import kotlinx.serialization.builtins.serializer

/** Observe and control the player of the provider app. */
public object MediaPlayback : Capability(CapabilityId("media.playback"), CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("media.playback.read")
    public val ControlScope: Scope = Scope("media.playback.control")

    public val Play: OperationSpec<NoInput, Unit> = command("play", "Start or resume playback.")
    public val Pause: OperationSpec<NoInput, Unit> = command("pause", "Pause playback.")
    public val Toggle: OperationSpec<NoInput, Unit> = command("toggle", "Toggle between playing and paused.")
    public val Next: OperationSpec<NoInput, Unit> = command("next", "Skip to the next track.")
    public val Previous: OperationSpec<NoInput, Unit> = command("previous", "Go back to the previous track.")

    public val SeekTo: OperationSpec<SeekRequest, Unit> = operation(
        name = "seek_to",
        input = SeekRequest.serializer(),
        output = Unit.serializer(),
        scope = ControlScope,
        description = "Move the playback position of the current track, in milliseconds.",
    )

    public val PlayPlaylist: OperationSpec<PlayPlaylistRequest, Unit> = operation(
        name = "play_playlist",
        input = PlayPlaylistRequest.serializer(),
        output = Unit.serializer(),
        scope = ControlScope,
        description = "Replace the queue with a playlist and start playing from startIndex, optionally shuffled.",
    )

    public val PlayTracks: OperationSpec<PlayTracksRequest, Unit> = operation(
        name = "play_tracks",
        input = PlayTracksRequest.serializer(),
        output = Unit.serializer(),
        scope = ControlScope,
        description = "Replace the queue with the given tracks and start playing from startIndex.",
    )

    public val GetState: OperationSpec<NoInput, PlaybackState> = operation(
        name = "get_state",
        input = NoInput.serializer(),
        output = PlaybackState.serializer(),
        scope = ReadScope,
        description = "Get the current playback state.",
    )

    public val PlaybackStateChanged: EventSpec<PlaybackState> = event(
        name = "playback_state_changed",
        serializer = PlaybackState.serializer(),
        scope = ReadScope,
        description = "Playback status, position anchor, shuffle or current track changed.",
    )

    public val NowPlayingChanged: EventSpec<NowPlaying> = event(
        name = "now_playing_changed",
        serializer = NowPlaying.serializer(),
        scope = ReadScope,
        description = "A new track became current.",
    )

    private fun command(name: String, description: String): OperationSpec<NoInput, Unit> =
        operation(name, NoInput.serializer(), Unit.serializer(), ControlScope, description)
}
