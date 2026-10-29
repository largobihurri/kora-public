package eus.bihurri.kora.contract.media

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import kotlinx.serialization.Serializable

/**
 * One play of a track: [track] began at [startedAtEpochMs]. [playedMs] is how long it actually played, or null when
 * the provider does not know (a play that is still running, or one it only saw start).
 *
 * A caller that merges plays from several sources identifies one by [TrackSummary.id] plus [startedAtEpochMs].
 */
@Serializable
public data class PlayRecord(
    public val track: TrackSummary,
    public val startedAtEpochMs: Long,
    public val playedMs: Long? = null,
)

/**
 * Filter of [MediaHistory.ListPlays]: the plays that started within [fromEpochMs]..[toEpochMs], both inclusive.
 * An empty window ([fromEpochMs] equal to [toEpochMs]) is valid; an inverted one is rejected.
 */
@Serializable
public data class PlaysRequest(
    public val fromEpochMs: Long,
    public val toEpochMs: Long,
    public val page: PageRequest = PageRequest(),
) {
    init {
        require(fromEpochMs <= toEpochMs) { "fromEpochMs must not be after toEpochMs: $fromEpochMs > $toEpochMs" }
    }
}

/**
 * Read access to the log of what the provider app played, so another app can recover plays it missed (for example a
 * hiking app on a watch whose link to the phone was down).
 *
 * Kora defines only this vocabulary; each provider keeps its own log.
 * - [ListPlays] returns the plays that **started** within the requested window, ordered by
 *   [PlayRecord.startedAtEpochMs] ascending, then page by page. A play that started before the window but ran into it
 *   is not included.
 * - Retention is the provider's decision: it documents how far back its log goes, and a window older than that simply
 *   returns fewer plays (an empty page is not an error).
 * - Ids are opaque provider strings, as in [MediaLibrary]; a [PlayRecord] never carries a path or URI.
 *
 * There is deliberately no `play_recorded` event: a listener that wants plays as they happen subscribes to
 * [MediaPlayback.NowPlayingChanged], which carries the same track and start time. This capability is for catching up
 * afterwards.
 *
 * Version history:
 * - 1.0: [ListPlays].
 */
public object MediaHistory : Capability(CapabilityId("media.history"), CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("media.history.read")

    public val ListPlays: OperationSpec<PlaysRequest, Page<PlayRecord>> = operation(
        name = "list_plays",
        input = PlaysRequest.serializer(),
        output = Page.serializer(PlayRecord.serializer()),
        scope = ReadScope,
        description = "List the plays that started between two instants (epoch milliseconds, both inclusive), " +
            "oldest first, one page at a time.",
    )
}
