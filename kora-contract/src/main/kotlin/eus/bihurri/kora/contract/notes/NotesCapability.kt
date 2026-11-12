package eus.bihurri.kora.contract.notes

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.Page
import kotlinx.serialization.builtins.serializer

/**
 * Notes of the provider app: folders nested to any depth, notes with their whole content and styles, tags, pin,
 * search, Recently Deleted and the settings of the section. The vocabulary is app-neutral; what a note does stays in
 * the app that provides it.
 *
 * - **Scopes and effects.** Reads need [ReadScope] and changes [WriteScope]. The reads are [OperationEffect.READ];
 *   [DeleteNotePermanently] and [DeleteFolder] lose something for good and are [OperationEffect.DESTRUCTIVE]; every
 *   other change, [DeleteNote] included, can be undone and is [OperationEffect.WRITE].
 * - **One listing.** [ListNotes] takes at most one selector (a folder, tags, Recently Deleted or a search) instead
 *   of one operation per kind of listing. Items come in the provider's order. Listings are paged with `limit`
 *   (default [DEFAULT_LIMIT], at most [MAX_LIMIT]) and `offset`.
 * - **Create and change.** [CreateNote], [UpdateNote] and [RestoreNote] answer the resulting [NoteDetail].
 *   [UpdateNote], [UpdateFolder] and [UpdateSettings] are patches: null or absent leaves a field as it is, and a
 *   change is applied whole or not at all. A note in Recently Deleted is not changed: [UpdateNote] of it fails with
 *   `invalid_payload`, and [RestoreNote] brings it back first.
 * - **A change never deletes a note.** A `markdown` the provider would not keep (nothing written in it, on a note
 *   without attachments) fails with `invalid_payload`, in [CreateNote] and in [UpdateNote].
 * - **Deleting is two steps.** [DeleteNote] moves a note, with any sub-notes it has, to Recently Deleted, from where
 *   [RestoreNote] brings it back. [DeleteNotePermanently] deletes for good a note that is already there, with its
 *   sub-notes; it cannot be undone. [DeleteFolder] deletes a folder and every folder nested in it for good, and moves
 *   their notes to Recently Deleted.
 * - **A note is read in parts.** [GetNote] answers at most `maxChars` characters ([MIN_NOTE_CHARS] to
 *   [MAX_NOTE_CHARS], default [DEFAULT_NOTE_CHARS]) of the content from `offset`, cut at the end of a line, or
 *   inside a line longer than `maxChars`; see [NoteDetail].
 * - **Failures.** A malformed request, an id the provider does not have and a change the provider's rules reject
 *   are `invalid_payload`.
 *
 * ## The content is Markdown
 * The content of a note travels as Markdown in one `markdown` field. The provider converts between Markdown and its
 * own model and is the authority on the result. What every provider reads and writes:
 *
 * | Markdown | Meaning |
 * |---|---|
 * | a line | one paragraph: a line break ends it, and an empty line is an empty paragraph |
 * | `# `, `## `, `### ` at the start of a line | the three levels of heading |
 * | the lines between two lines of three backticks | monospaced |
 * | `> ` | block quote |
 * | `- ` or `* ` | list item |
 * | `1. ` | numbered list item |
 * | `- [ ] `, `- [x] ` | checklist item, to do and done |
 * | four spaces or a tab before a list marker | one more level of indentation |
 * | `**bold**`, `*italic*`, `<u>underline</u>`, `~~strikethrough~~`, `` `code` `` | inline styles |
 * | `#tag`, a `#` and a word | a tag of the note: the tags are part of the text |
 * | `\` before a marker | the character itself |
 *
 * Three differences from ordinary Markdown:
 * - **It is line-based.** Ordinary Markdown joins lines into a paragraph and separates paragraphs with a blank
 *   line; here a blank line shows as an empty paragraph. A writer that wants no gaps writes none.
 * - **The title is the first line with text**, whatever its style. A heading mark is a style, not what makes a
 *   title.
 * - **`- ` and `* ` are both list items.** A provider with two bullet styles may tell them apart, and writes back
 *   the marker it read.
 *
 * What a round trip keeps, the guarantee both sides rely on:
 * - **What a provider returns in `markdown`, sent back unchanged, gives the same note.** Nothing is lost by reading
 *   and then writing.
 * - A provider writes anything outside the table (a table, a colour, a highlight, a drawing, a link to another
 *   note) in a form it reads back itself: Markdown where it exists, an HTML tag such as `<mark>`, a reference to an
 *   attachment. A caller that does not understand a piece leaves it as it is.
 * - What a caller writes that the provider does not understand is kept as the literal text, never dropped. So a
 *   caller can write only the styles the provider reads; the table above is the part that is certain.
 *
 * Version history:
 * - 1.0: [GetOverview], [ListNotes], [GetNote], [CreateNote], [UpdateNote], [DeleteNote], [DeleteNotePermanently],
 *   [RestoreNote], [CreateFolder], [UpdateFolder], [DeleteFolder], [UpdateSettings].
 */
public object NotesCapability : Capability(CapabilityId("notes"), CapabilityVersion(1, 0)) {
    /** `limit` of a listing when the caller does not choose: 20 items, which fit the size an assistant shows. */
    public const val DEFAULT_LIMIT: Int = 20

    /** Largest `limit` of a listing. */
    public const val MAX_LIMIT: Int = 50

    /** Smallest [GetNoteRequest.maxChars]: 500 characters, a part that still holds a few paragraphs. */
    public const val MIN_NOTE_CHARS: Int = 500

    /**
     * Largest [GetNoteRequest.maxChars]: 20 000 characters. In scripts that take three bytes a character that is
     * about 59 KiB, and an MCP answer holds the text twice (the `text` block and `structuredContent`), about 118 KiB:
     * a part of the largest size fits the 256 KiB every transport carries in one message, with room for the rest of
     * the answer.
     */
    public const val MAX_NOTE_CHARS: Int = 20_000

    /**
     * [GetNoteRequest.maxChars] when the caller does not choose: 4 000 characters. A [NoteDetail] also lists every
     * attachment of the note, a list without a bound, so a part of this size leaves room for a dozen attachments
     * within the size an assistant shows.
     */
    public const val DEFAULT_NOTE_CHARS: Int = 4_000

    public val ReadScope: Scope = Scope("notes.read")

    public val WriteScope: Scope = Scope("notes.write")

    public val GetOverview: OperationSpec<NotesOverviewRequest, NotesOverview> = operation(
        name = "get_overview",
        input = NotesOverviewRequest.serializer(),
        output = NotesOverview.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get the folders in tree order with their note counts, the tags, the count of all notes and " +
            "of Recently Deleted, and the settings. limit and offset page the folders and the tags.",
    )

    public val ListNotes: OperationSpec<ListNotesRequest, Page<NoteSummary>> = operation(
        name = "list_notes",
        input = ListNotesRequest.serializer(),
        output = Page.serializer(NoteSummary.serializer()),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "List notes, one page at a time, in the provider's order: those of a folder (folderId), " +
            "carrying all the given tags (lower-case names without #), in Recently Deleted (deleted) or matching a " +
            "search of titles and text (query). Use at most one of them; with none it lists every note.",
    )

    public val GetNote: OperationSpec<GetNoteRequest, NoteDetail> = operation(
        name = "get_note",
        input = GetNoteRequest.serializer(),
        output = NoteDetail.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get a note with its content as line-based Markdown. A long note comes in parts of at most " +
            "maxChars characters: read on from offset plus the length of markdown until totalChars is reached.",
    )

    public val CreateNote: OperationSpec<CreateNoteRequest, NoteDetail> = operation(
        name = "create_note",
        input = CreateNoteRequest.serializer(),
        output = NoteDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Create a note from line-based Markdown, in the default folder unless folderId is given, " +
            "pinned when pinned is true. Every line is a paragraph, the first line with text is the title and a " +
            "#word is a tag.",
    )

    public val UpdateNote: OperationSpec<UpdateNoteRequest, NoteDetail> = operation(
        name = "update_note",
        input = UpdateNoteRequest.serializer(),
        output = NoteDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Change a note: markdown replaces the whole content or append adds Markdown after the last " +
            "line (never both), folderId moves it and pinned pins it. Only the fields sent change. A note in " +
            "Recently Deleted cannot be changed: restore it first.",
    )

    public val DeleteNote: OperationSpec<NoteIdRequest, Unit> = operation(
        name = "delete_note",
        input = NoteIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Move a note, with any sub-notes it has, to Recently Deleted, from where restore_note brings " +
            "it back.",
    )

    public val DeleteNotePermanently: OperationSpec<NoteIdRequest, Unit> = operation(
        name = "delete_note_permanently",
        input = NoteIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete for good a note that is in Recently Deleted, with any sub-notes it has. It cannot be " +
            "undone. A note that is not in Recently Deleted is invalid_payload.",
    )

    public val RestoreNote: OperationSpec<NoteIdRequest, NoteDetail> = operation(
        name = "restore_note",
        input = NoteIdRequest.serializer(),
        output = NoteDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Bring a note back from Recently Deleted.",
    )

    public val CreateFolder: OperationSpec<CreateFolderRequest, FolderSummary> = operation(
        name = "create_folder",
        input = CreateFolderRequest.serializer(),
        output = FolderSummary.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Create a folder of notes, inside the folder parentId or at the top level.",
    )

    public val UpdateFolder: OperationSpec<UpdateFolderRequest, FolderSummary> = operation(
        name = "update_folder",
        input = UpdateFolderRequest.serializer(),
        output = FolderSummary.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Rename or move a folder of notes; only the fields sent change. An empty parentId moves it to " +
            "the top level.",
    )

    public val DeleteFolder: OperationSpec<FolderIdRequest, Unit> = operation(
        name = "delete_folder",
        input = FolderIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete for good a folder of notes and every folder nested in it; their notes move to " +
            "Recently Deleted.",
    )

    public val UpdateSettings: OperationSpec<UpdateNotesSettingsRequest, NotesSettings> = operation(
        name = "update_settings",
        input = UpdateNotesSettingsRequest.serializer(),
        output = NotesSettings.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Change the settings of the notes: the order the app sorts notes by, the list or gallery view and " +
            "whether ticked checklist items are moved. Only the fields sent change.",
    )
}
