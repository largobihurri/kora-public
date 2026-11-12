package eus.bihurri.kora.contract.notes

import eus.bihurri.kora.contract.IntegerRange
import kotlinx.serialization.Serializable

/*
 * Request types of the notes capability. Every request is flat (scalars, wire enums and lists of scalars), so each
 * field reaches a model as a typed tool parameter. A request checks the wire format only: a blank id, a number out
 * of its range, fields that exclude each other. Such a request fails with `invalid_payload` before it reaches the
 * provider. The rules of the domain (a note the provider would not keep, a folder moved into itself, a change of a
 * note in Recently Deleted) are the provider's.
 *
 * An enum constant this version does not know, sent by a newer caller or misspelled, decodes as `UNKNOWN` and is not
 * rejected here: old Kora reads new Kora (`docs/compatibility.md`). The provider rejects it with `invalid_payload`
 * and never reads it as another constant.
 */

/** The window of a listing: [limit] within 1..[NotesCapability.MAX_LIMIT], [offset] not negative. */
private fun requirePage(limit: Int, offset: Int) {
    require(limit in 1..NotesCapability.MAX_LIMIT) { "limit must be within 1..${NotesCapability.MAX_LIMIT}: $limit" }
    require(offset >= 0) { "offset must not be negative: $offset" }
}

/**
 * Request of [NotesCapability.GetOverview]: [limit] and [offset] window both the folders and the tags of the answer.
 * [limit] is within 1..[NotesCapability.MAX_LIMIT] and defaults to [NotesCapability.DEFAULT_LIMIT]; [offset] is not
 * negative.
 */
@Serializable
public data class NotesOverviewRequest(
    @IntegerRange(min = 1, max = NotesCapability.MAX_LIMIT.toLong())
    public val limit: Int = NotesCapability.DEFAULT_LIMIT,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
) {
    init {
        requirePage(limit, offset)
    }
}

/**
 * Request of [NotesCapability.ListNotes]: one page of notes, chosen by at most one selector.
 *
 * - [folderId]: the notes of that folder.
 * - [tags], when not empty: the notes that carry all of them. A tag is named as it is stored: its exact lower-case
 *   name, without the `#`.
 * - [deleted] `true`: the notes in Recently Deleted.
 * - [query], when it holds more than whitespace: a search of the titles and the text of every note. A query of only
 *   whitespace is no search and does not count as a selector.
 * - With no selector: every note.
 *
 * Two selectors at once are rejected. Items come in the provider's order, for example the pinned notes first, a
 * search by the last edit and Recently Deleted by the deletion. [limit] is within 1..[NotesCapability.MAX_LIMIT] and
 * defaults to [NotesCapability.DEFAULT_LIMIT]; [offset] is not negative. [folderId], when present, must not be
 * blank.
 */
@Serializable
public data class ListNotesRequest(
    public val folderId: String? = null,
    public val tags: List<String> = emptyList(),
    public val deleted: Boolean = false,
    public val query: String = "",
    @IntegerRange(min = 1, max = NotesCapability.MAX_LIMIT.toLong())
    public val limit: Int = NotesCapability.DEFAULT_LIMIT,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
) {
    init {
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank" }
        val selectors = listOf(folderId != null, tags.isNotEmpty(), deleted, query.isNotBlank()).count { it }
        require(selectors <= 1) { "At most one of folderId, tags, deleted and query selects the notes" }
        requirePage(limit, offset)
    }
}

/**
 * Request of [NotesCapability.GetNote]: one part of the content of the note [noteId], which must not be blank.
 *
 * The answer holds at most [maxChars] characters of the content from the character [offset], cut at the end of a
 * line; a line longer than [maxChars] is cut inside. Characters are UTF-16 units, as in a Kotlin or Java string.
 * [offset] is not negative and defaults to the beginning. [maxChars] is within
 * [NotesCapability.MIN_NOTE_CHARS]..[NotesCapability.MAX_NOTE_CHARS] and defaults to
 * [NotesCapability.DEFAULT_NOTE_CHARS], a part that fits the size an assistant shows.
 */
@Serializable
public data class GetNoteRequest(
    public val noteId: String,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
    @IntegerRange(min = NotesCapability.MIN_NOTE_CHARS.toLong(), max = NotesCapability.MAX_NOTE_CHARS.toLong())
    public val maxChars: Int = NotesCapability.DEFAULT_NOTE_CHARS,
) {
    init {
        require(noteId.isNotBlank()) { "noteId must not be blank" }
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(maxChars in NotesCapability.MIN_NOTE_CHARS..NotesCapability.MAX_NOTE_CHARS) {
            "maxChars must be within ${NotesCapability.MIN_NOTE_CHARS}..${NotesCapability.MAX_NOTE_CHARS}: $maxChars"
        }
    }
}

/**
 * Request of [NotesCapability.CreateNote]: a new note whose whole content is [markdown], in the line-based form
 * [NotesCapability] describes; its title is the first line with text. [folderId] absent: the default folder. It
 * must not be blank. [pinned] pins the new note; it is not pinned by default.
 *
 * A [markdown] the provider would not keep (a new note has no attachments, so one with nothing written in it) fails
 * with `invalid_payload`; the provider, which reads the Markdown, decides that.
 */
@Serializable
public data class CreateNoteRequest(
    public val markdown: String,
    public val folderId: String? = null,
    public val pinned: Boolean = false,
) {
    init {
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank" }
    }
}

/**
 * Request of [NotesCapability.UpdateNote]: a patch of the note [noteId].
 *
 * Only [noteId] is required. Every other field is nullable, and null (or absent) means "leave as it is". The change
 * is applied whole or not at all. A note in Recently Deleted is not changed: the request fails with
 * `invalid_payload`, and [NotesCapability.RestoreNote] brings the note back first.
 *
 * - [markdown] replaces the whole content.
 * - [append] is Markdown added after the last line: the common "add this to my note", and the way to write a long
 *   note without resending it. An empty [append] adds nothing. [markdown] and [append] together are rejected.
 * - [folderId] moves the note to that folder. It must not be blank.
 * - [pinned] pins or unpins it.
 *
 * **A change never deletes a note.** A [markdown] the provider would not keep (nothing written in it, on a note
 * without attachments) fails with `invalid_payload`; only [NotesCapability.DeleteNote] deletes.
 */
@Serializable
public data class UpdateNoteRequest(
    public val noteId: String,
    public val markdown: String? = null,
    public val append: String? = null,
    public val folderId: String? = null,
    public val pinned: Boolean? = null,
) {
    init {
        require(noteId.isNotBlank()) { "noteId must not be blank" }
        require(markdown == null || append == null) { "markdown and append exclude each other" }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank" }
    }
}

/**
 * Names one note by its opaque [noteId], which must not be blank. Request of [NotesCapability.DeleteNote], which
 * moves the note to Recently Deleted, of [NotesCapability.DeleteNotePermanently], which deletes for good a note that
 * is there, and of [NotesCapability.RestoreNote], which brings one back from there.
 */
@Serializable
public data class NoteIdRequest(public val noteId: String) {
    init {
        require(noteId.isNotBlank()) { "noteId must not be blank" }
    }
}

/**
 * Request of [NotesCapability.CreateFolder]: a new folder called [name], inside the folder [parentId] or, when it
 * is absent, at the top level. [parentId] must not be blank.
 */
@Serializable
public data class CreateFolderRequest(public val name: String, public val parentId: String? = null) {
    init {
        require(parentId == null || parentId.isNotBlank()) { "parentId must not be blank" }
    }
}

/**
 * Request of [NotesCapability.UpdateFolder]: a patch of the folder [folderId], which must not be blank. [name]
 * renames it and [parentId] moves it; null (or absent) leaves them as they are.
 *
 * An empty [parentId] moves the folder to the top level. Any other [parentId] is the id of a folder, so text that is
 * only whitespace is rejected.
 */
@Serializable
public data class UpdateFolderRequest(
    public val folderId: String,
    public val name: String? = null,
    public val parentId: String? = null,
) {
    init {
        require(folderId.isNotBlank()) { "folderId must not be blank" }
        require(parentId.isNullOrEmpty() || parentId.isNotBlank()) {
            "parentId must be the id of a folder, or empty for the top level"
        }
    }
}

/**
 * Request of [NotesCapability.DeleteFolder]: the folder [folderId], which must not be blank. The folder and every
 * folder nested in it are deleted for good; their notes move to Recently Deleted.
 */
@Serializable
public data class FolderIdRequest(public val folderId: String) {
    init {
        require(folderId.isNotBlank()) { "folderId must not be blank" }
    }
}

/**
 * Request of [NotesCapability.UpdateSettings]: a patch of the settings of the section ([NotesSettings]). [sort],
 * [view] and [moveTickedItems] change when present; null (or absent) leaves them as they are.
 */
@Serializable
public data class UpdateNotesSettingsRequest(
    public val sort: NoteSort? = null,
    public val view: NotesView? = null,
    public val moveTickedItems: Boolean? = null,
)
