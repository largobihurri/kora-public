package eus.bihurri.kora.contract.notes

import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import eus.bihurri.kora.contract.files.AttachmentInfo
import kotlinx.serialization.Serializable

/*
 * Enums and answer types of the notes capability. Every id is an opaque string chosen by the provider. Instants are
 * epoch milliseconds in `...EpochMs` fields. Answer types carry no checks, so a newer provider never breaks an older
 * reader.
 */

/**
 * The order the provider app sorts notes by, one of its settings. A listing comes in the provider's order, which may
 * also depend on the listing ([NotesCapability.ListNotes]). A newer provider may use an order this version lacks,
 * which reads as [UNKNOWN].
 */
@Serializable(with = NoteSort.Serializer::class)
public enum class NoteSort(override val wireName: String) : WireEnum {
    DATE_EDITED("date_edited"),
    DATE_CREATED("date_created"),
    TITLE("title"),

    /** An order this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<NoteSort>(
        "eus.bihurri.kora.contract.notes.NoteSort",
        entries,
        UNKNOWN,
    )
}

/** How the provider app shows a listing of notes. A view this version lacks reads as [UNKNOWN]. */
@Serializable(with = NotesView.Serializer::class)
public enum class NotesView(override val wireName: String) : WireEnum {
    LIST("list"),
    GALLERY("gallery"),

    /** A view this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<NotesView>(
        "eus.bihurri.kora.contract.notes.NotesView",
        entries,
        UNKNOWN,
    )
}

/**
 * The settings of the notes section: [sort], the order the app sorts notes by; [view], how the app shows them; and
 * [moveTickedItems], the provider app's switch for moving the ticked items of a checklist.
 */
@Serializable
public data class NotesSettings(
    public val sort: NoteSort,
    public val view: NotesView,
    public val moveTickedItems: Boolean,
)

/**
 * A folder of notes. Folders nest to any depth: [parentId] is the folder it is in, null at the top level, and
 * [depth] is how deep it is (0 at the top level). [noteCount] is the notes in it and [isDefault] marks the folder a
 * note created without a `folderId` goes to.
 */
@Serializable
public data class FolderSummary(
    public val id: String,
    public val parentId: String? = null,
    public val name: String,
    public val depth: Int,
    public val noteCount: Int,
    public val isDefault: Boolean,
)

/** A tag of the notes: its [name] and [noteCount], the notes that carry it. */
@Serializable
public data class TagSummary(public val name: String, public val noteCount: Int)

/**
 * Answer of [NotesCapability.GetOverview]: the containers of the section, their counts and the settings.
 *
 * [folders] (in tree order) and [tags] are one window each of the whole collections, cut with the request's `limit`
 * and `offset`; [folderCount] and [tagCount] are the sizes of the whole collections. [allNotesCount] counts every
 * note that is not deleted and [recentlyDeletedCount] the notes in Recently Deleted.
 */
@Serializable
public data class NotesOverview(
    public val allNotesCount: Int,
    public val folders: List<FolderSummary>,
    public val folderCount: Int,
    public val tags: List<TagSummary>,
    public val tagCount: Int,
    public val recentlyDeletedCount: Int,
    public val settings: NotesSettings,
)

/**
 * One note in a listing ([NotesCapability.ListNotes]).
 *
 * - [title] is the first line of the note with text, whatever its style.
 * - [preview] is the beginning of the text, or the part that matched the `query` of the listing.
 * - [deletedAtEpochMs] and [daysLeft] are set in Recently Deleted: when it was deleted, and the days until it is
 *   deleted for good.
 * - [attachmentCount] is a count, not a list: the attachments come with [NoteDetail].
 */
@Serializable
public data class NoteSummary(
    public val id: String,
    public val folderId: String? = null,
    public val title: String,
    public val preview: String,
    public val pinned: Boolean,
    public val tags: List<String>,
    public val createdAtEpochMs: Long,
    public val editedAtEpochMs: Long,
    public val deletedAtEpochMs: Long? = null,
    public val daysLeft: Int? = null,
    public val attachmentCount: Int,
)

/**
 * One note with its content: the fields of [NoteSummary] without the preview, plus one part of the content as
 * [markdown] and its [attachments]. Every create and change of a note answers it, so the caller sees what was
 * stored.
 *
 * **A note is read in parts.** [markdown] holds at most `maxChars` characters of the content from [offset], cut at
 * the end of a line, or inside a line longer than `maxChars`. Characters are UTF-16 units. [offset] plus the length
 * of [markdown] is where the next part starts, and [totalChars] is the length of the whole content, which says when
 * the note is whole. A short note comes in one answer; the answers of
 * the write operations carry the first part. The Markdown is the line-based form [NotesCapability] describes.
 *
 * [attachments] is empty unless the provider also offers `notes.attachments` ([NoteAttachments]).
 */
@Serializable
public data class NoteDetail(
    public val id: String,
    public val folderId: String? = null,
    public val title: String,
    public val pinned: Boolean,
    public val tags: List<String>,
    public val createdAtEpochMs: Long,
    public val editedAtEpochMs: Long,
    public val deletedAtEpochMs: Long? = null,
    public val daysLeft: Int? = null,
    public val attachmentCount: Int,
    public val markdown: String,
    public val offset: Int,
    public val totalChars: Int,
    public val attachments: List<AttachmentInfo> = emptyList(),
)
