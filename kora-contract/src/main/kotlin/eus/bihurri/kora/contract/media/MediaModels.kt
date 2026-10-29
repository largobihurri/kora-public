package eus.bihurri.kora.contract.media

import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import kotlinx.serialization.Serializable

/*
 * Shared media models. Every id is an opaque string chosen by the provider: never a file path or URI, so
 * a caller can neither guess nor dereference it outside Kora.
 */

@Serializable
public data class PageRequest(public val limit: Int = 50, public val offset: Int = 0) {
    init {
        require(limit > 0) { "limit must be positive: $limit" }
        require(offset >= 0) { "offset must not be negative: $offset" }
    }
}

/** One page of [items]; [total] is the size of the whole result and [offset] the index of the first item. */
@Serializable
public data class Page<T>(public val items: List<T>, public val total: Int, public val offset: Int = 0)

@Serializable
public data class PlaylistSummary(
    public val id: String,
    public val name: String,
    public val trackCount: Int,
    /** Opaque reference to artwork, never a URI; [MediaArtwork.GetArtwork] resolves it into image bytes. */
    public val artworkId: String? = null,
)

@Serializable
public data class TrackSummary(
    public val id: String,
    public val title: String,
    public val artist: String? = null,
    public val album: String? = null,
    public val durationMs: Long = 0,
    /** Opaque reference to artwork, never a URI; [MediaArtwork.GetArtwork] resolves it into image bytes. */
    public val artworkId: String? = null,
)

@Serializable
public data class PlaylistTracksRequest(public val playlistId: String, public val page: PageRequest = PageRequest())

@Serializable
public data class SearchRequest(public val query: String, public val page: PageRequest = PageRequest())

/** Player status. A newer provider may report a status this version lacks, which reads as [UNKNOWN]. */
@Serializable(with = PlaybackStatus.Serializer::class)
public enum class PlaybackStatus(override val wireName: String) : WireEnum {
    IDLE("idle"),
    BUFFERING("buffering"),
    PLAYING("playing"),
    PAUSED("paused"),
    ENDED("ended"),

    /** A status this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<PlaybackStatus>(
        "eus.bihurri.kora.contract.media.PlaybackStatus",
        entries,
        UNKNOWN,
    )
}

/**
 * Snapshot of the player. [positionMs] was accurate at [updatedAtEpochMs]; while [status] is playing a
 * consumer extrapolates from there instead of polling.
 */
@Serializable
public data class PlaybackState(
    public val status: PlaybackStatus,
    public val positionMs: Long = 0,
    public val updatedAtEpochMs: Long = 0,
    public val track: TrackSummary? = null,
    public val shuffle: Boolean = false,
)

/** The track that just became current. The artwork reference is [TrackSummary.artworkId] of [track]. */
@Serializable
public data class NowPlaying(public val track: TrackSummary, public val startedAtEpochMs: Long)

@Serializable
public data class SeekRequest(public val positionMs: Long)

@Serializable
public data class PlayPlaylistRequest(
    public val playlistId: String,
    public val startIndex: Int = 0,
    public val shuffle: Boolean = false,
)

@Serializable
public data class PlayTracksRequest(public val trackIds: List<String>, public val startIndex: Int = 0)
