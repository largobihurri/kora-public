package eus.bihurri.kora.contract.media

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope

/** Read access to the user's playlists and tracks. */
public object MediaLibrary : Capability(CapabilityId("media.library"), CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("media.library.read")

    public val ListPlaylists: OperationSpec<PageRequest, Page<PlaylistSummary>> = operation(
        name = "list_playlists",
        input = PageRequest.serializer(),
        output = Page.serializer(PlaylistSummary.serializer()),
        scope = ReadScope,
        description = "List the user's playlists, one page at a time.",
    )

    public val GetPlaylistTracks: OperationSpec<PlaylistTracksRequest, Page<TrackSummary>> = operation(
        name = "get_playlist_tracks",
        input = PlaylistTracksRequest.serializer(),
        output = Page.serializer(TrackSummary.serializer()),
        scope = ReadScope,
        description = "List the tracks of one playlist, one page at a time.",
    )

    public val SearchTracks: OperationSpec<SearchRequest, Page<TrackSummary>> = operation(
        name = "search_tracks",
        input = SearchRequest.serializer(),
        output = Page.serializer(TrackSummary.serializer()),
        scope = ReadScope,
        description = "Search tracks by free text (title, artist, album).",
    )
}
