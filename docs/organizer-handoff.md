# Organizer vocabulary: hand-off for the provider module

For the session that writes the provider module in the app that provides these capabilities (Oharrak). Written
2026-11-10 after the second round of remarks, and brought up to date on 2026-11-12 after your review of the contract.
The contract is `docs/organizer-vocabulary.md` (sections 4 to 9 are the payloads; section 13 has the three rounds).
**Nothing is published yet**: the six capabilities and the stream transport are implemented on the branch (see the
last section and `odd/tasks/kora-organizer.md`). Proposed release: Kora `1.3.0`, every capability `1.0`.

## Third round: what changed after your review (2026-11-12)

Every item your review marked "now" is applied, with three decisions of the owner. Please re-read these parts only;
the full list, with where each lives, is section 13 of the design, "Third round".

- **Effects.** Every operation declares `OperationSpec.effect`: `READ`, `WRITE` or `DESTRUCTIVE`. Not on the wire;
  nothing for your provider to do. The destructive ones: `delete_reminder`, `delete_list`, `delete_tag`,
  `delete_event`, `delete_calendar`, `delete_note_permanently`, `delete_folder`, and `remove` of both attachments
  capabilities.
- **`calendar.attachments` is not in 1.3.0** (owner). Six capabilities, not seven. It is designed and arrives later as
  an additive capability, once you have a store for the attachments of events; `EventDetail` has no `attachments`.
- **`delete_note` is split** (owner): `delete_note` moves a note to Recently Deleted; `delete_note_permanently`
  (`noteId`) deletes for good a note that is there. Both take `NoteIdRequest`; `DeleteNoteRequest` is gone.
- **Recording** (owner): `notes.recording` stays out of 1.3.0 with no contract change. A voice note from KOKO is
  KOKO's own recording, uploaded with `files.upload` and attached with `notes.attachments.add`; your `audio/*` kind
  files it as audio. Section 10 now says that you record, and that commanding your recorder is a later, additive
  capability whose design is redone to fit it (start in an existing live note; state `noteId`, `startedAt`,
  `paused`; a stopped-but-unattached state; a microphone failure is a rule error).
- **Reminders.** `repeatFromCompletion` joins the repetition group (create `false`, a patch field of the group, in
  `ReminderDetail`). Kora now rejects, before your handler: a `dueTime` without a `dueDate` in a create; any other
  `repeat*` field set beside `repeatUnit` = `none`; `listId` and `parentId` together in a create; a `repeatDayKind`
  without its `repeatOrdinal`. Said in the contract: a `listId` equal to the current list changes nothing;
  `includeCompleted` counts for a list only; a list shows its top-level reminders while smart lists, tags and no
  selector include subtasks; the order of a sectioned list is the board order; a `#word` in a title may become a tag.
- **Calendar.** `create_event.alertMinutes` is nullable (null: your default alerts; empty: none). In `update_event`,
  `end` = `""` is rejected, a new `start` without `end` keeps the length, and an `rrule` with `span` = `this` is
  rejected by Kora. `EventDetail.timeZone` is the IANA id the rule is read in, and an `UNTIL` ending in `Z` is UTC (RFC 5545), otherwise a time in that zone; a new event takes the phone's
  time zone. `update_reminder` rejects `dueDate` = `""` together with a non-empty `dueTime`.
  The answer carries the rule as stored, and callers use the ids of the answer. `delete_calendar` says it takes the
  events; `url` may be kept as the last line of `notes`.
- **Notes.** Indentation is four spaces or a tab per level; `#tag` is a row of the Markdown table; `pinned` in
  `create_note`; default `maxChars` 4 000; a query of only whitespace is no selector; `tags` means all of them, exact
  lower-case names without `#`; listings come in your order; a line longer than `maxChars` is cut inside, in UTF-16
  units; `update_note` of a note in Recently Deleted is `invalid_payload`; blank Markdown is what you would not keep;
  `append` = `""` adds nothing.
- **Files.** A failed `add` leaves the staged file usable, and after a timeout the caller reads the item; a handle is
  valid at least 10 minutes after its last use while the file exists; `download` reads any handle you gave.

## Second round: what changed and why

I checked the note against `Reminders`, `Calendars` and `Notes` at `461d834`.

- Removed from 1.0 because the domain has no function for them and the provider would re-implement a rule: text
  search and the due-date range in `list_reminders`; text search in `list_events`; combining selectors; `sort` in
  `list_notes`; `timeZone` of an event; `pinned` in `create_note`. Section 12 says how each returns (optional fields).
- Colours are required in `create_list` and `create_calendar`.
- The reminder's repetition is seven flat fields in the shape of `Recurrence` (no string, no converter). The
  calendar keeps `rrule`, exact, which `Repetition.fromRRule` and `toRRule` already handle.
- A change is applied whole or not at all.
- **`notes.recording` is not in 1.3.0.** Section 10 keeps the design; it ships with the feature, as a capability of
  its own, which is purely additive. `notes.attachments` and `calendar.attachments` are in 1.3.0: their shape is
  the one your reminders domain already has. (Superseded in the third round: `calendar.attachments` left 1.3.0, and
  section 10 is to be redesigned to fit your recorder.)
- The day a repetition lands on when a month lacks it is no longer in the contract. It is yours to put to the owner.

## What Oharrak's entry classes need before the provider module (not in the provider itself)

| Vocabulary | Today | Needed |
|---|---|---|
| A change is whole or nothing | one function per field (`renameList`/`recolorList`; `edit`/`move`; `renameCalendar`/`recolorCalendar`/`setCalendarVisible`; `save`/`moveNote`/`setPinned`; `renameFolder`/`moveFolder`; the settings) | one function per item taking the whole change, or a way to run several as one |
| `create_reminder` without `listId` | `add` requires a list | a function that names the default list |
| `create_event` answers its `occurrenceId` | `add` returns `EventId` | `add` returning an `OccurrenceRef`, as `edit` does |
| `markdown` | paragraphs and spans | a Markdown reader and writer in `:core:notes`; what it writes, read back, gives the same paragraphs |
| `notes.attachments` | none | attach, list, open, remove, as in reminders |

## Operation by operation

Scopes: `<capability>.read` for reads, `<capability>.write` for the rest. `P` marks a patch (null leaves the field,
empty string / empty list / 0 empties it). Every create and change answers the resulting item. Every read is
`READ`; the destructive operations are named in the third round above; the other changes are `WRITE`.

### `reminders` (14)

| Operation | Input | Output | Entry class |
|---|---|---|---|
| `get_overview` | `limit`=20, `offset`=0 | `RemindersOverview` (smartCounts, lists + listCount, tags + tagCount, defaultListId?) | `observeOverview().first()`, windowed |
| `list_reminders` | one of `listId?`, `smartList?`, `tags`, `parentId?`; `includeCompleted`; `limit`, `offset` | `Page<ReminderSummary>` | `observe(Selection, includeCompleted)`; a list shows its top-level reminders only (subtasks through `parentId`); smart lists and tags give every matching reminder, a subtask as its own item, in the smart list's own order; no selector is the All smart list |
| `get_reminder` | `reminderId` | `ReminderDetail` | `observeReminder(id).first()` |
| `create_reminder` | `title`, `listId?` or `parentId?`, `notes`, `dueDate`, `dueTime` (needs `dueDate`), `repeat*`, `flagged`, `priority`, `url`, `tags`, `earlyAlertAmount`, `earlyAlertUnit` | `ReminderDetail` | `add` or `addSubtask` |
| `update_reminder` | `reminderId`; P: the same fields but `parentId`, plus `listId?` (the current list changes nothing) | `ReminderDetail` | `edit` (draft from `toDraft()`), `move` |
| `complete_reminder` | `reminderId`, `dueDate`=`""`, `dueTime`=`""` | `ReminderDetail` | `completeOccurrence` when a date is given, else `complete`; a `false` answers the reminder as it is |
| `reopen_reminder` | `reminderId` | `ReminderDetail` | `reopen` |
| `snooze_reminder` | `reminderId`, `snooze` (in_one_hour, tomorrow), `dueDate`, `dueTime`=`""` | `ReminderDetail` | `remindLater` |
| `delete_reminder` | `reminderId` | none | `delete` |
| `create_list` | `name`, `color` | `ReminderListSummary` (id, name, color, openCount) | `createList` |
| `update_list` | `listId`; P: `name?`, `color?` | `ReminderListSummary` | `renameList`, `recolorList` |
| `delete_list` | `listId` | none | `deleteList` |
| `rename_tag` | `tag`, `newName` | `TagName` (name) | `renameTag` |
| `delete_tag` | `tag` | none | `deleteTag` |

- `ReminderSummary`: id, listId, parentId?, title, dueDate?, dueTime?, repeats, flagged, priority, completed,
  completedAtEpochMs?, tags, subtaskCount, attachmentCount.
- `ReminderDetail`: the summary plus notes, the `repeat*` fields, seriesStartDate?, url, earlyAlertAmount,
  earlyAlertUnit, snoozedUntilEpochMs?, createdAtEpochMs, attachments.
- `repeat*`: `repeatUnit` (none, hour, day, week, month, year), `repeatInterval`, `repeatWeekdays`,
  `repeatMonthDays`, `repeatOrdinal` (none, first..fifth, last) with `repeatDayKind` (the weekdays, day, weekday,
  weekend_day), `repeatMonths`, `repeatUntil`, `repeatFromCompletion` (your `Recurrence.fromCompletion`). In a
  patch they are one group, replaced when `repeatUnit` is present; `repeatUnit` = `none` comes alone.
- Emptying `dueDate` in a patch also empties the time, the repetition and the early alert.

### `calendar` (10)

| Operation | Input | Output | Entry class |
|---|---|---|---|
| `get_overview` | `limit`, `offset` | `CalendarOverview` (accessGranted, calendars + calendarCount, defaultCalendarId?, showReminders) | `refreshAccess`, `observeAccess`, `observeOverview` |
| `list_events` | `from`, `to`, `calendarIds`=[], `limit`, `offset` | `Page<EventOccurrence>` | `observeAgenda(from, to)`, event entries, each occurrence once |
| `get_event` | `occurrenceId` | `EventDetail` | `open(ref)` |
| `create_event` | `title`, `start`, `end`=`""`, `allDay`, `calendarId?`, `location`, `rrule`, `alertMinutes?` (null: your defaults), `availability`, `url`, `notes` | `EventDetail` | `add`; length from `newEvent` when `end` is empty |
| `update_event` | `occurrenceId`, `span`=this; P: the create fields (`end` never `""`; `rrule` only with `span` all or following) | `EventDetail` | `edit(ref, draft, scope)`, draft from `open(ref).draft`; a new start without end keeps the length |
| `delete_event` | `occurrenceId`, `span`=this | none | `delete(ref, scope)` |
| `create_calendar` | `name`, `color` (`#RRGGBB`) | `CalendarSummary` | `createCalendar` |
| `update_calendar` | `calendarId`; P: `name?`, `color?`, `visible?` | `CalendarSummary` | `renameCalendar`, `recolorCalendar`, `setCalendarVisible` |
| `delete_calendar` | `calendarId` | none | `deleteCalendar` (its events go with it) |
| `update_settings` | P: `defaultCalendarId?`, `showReminders?` | `CalendarOverview` | `setDefaultCalendar`, `setShowReminders` |

- `occurrenceId` is yours to make from an `OccurrenceRef` (event id and the instant it begins); opaque to callers.
- `EventOccurrence`: occurrenceId, eventId, calendarId, title, location, allDay, start, end, repeats.
- `EventDetail`: the occurrence plus timeZone (the IANA id `fromRRule` needs), rrule (as stored), spans (from
  `OpenEvent.scopes`), alertMinutes, availability, url, notes, editable. No attachments in 1.3.0.
- `start` and `end` are always `YYYY-MM-DDTHH:mm`; for an all-day event only the days count.
- `span` defaults to `this` in the contract; your `Scope.ALL` default does not apply to calls from Kora.
- Without calendar access the operations answer `provider_unavailable`.

### `notes` (12)

| Operation | Input | Output | Entry class |
|---|---|---|---|
| `get_overview` | `limit`, `offset` | `NotesOverview` (allNotesCount, folders + folderCount, tags + tagCount, recentlyDeletedCount, settings) | `observeOverview`, `observeSettings` |
| `list_notes` | one of `folderId?`, `tags`, `deleted`, `query`; `limit`, `offset` | `Page<NoteSummary>` | `observeNotes(scope)` flattened (pinned first), or `search(query)` |
| `get_note` | `noteId`, `offset`=0, `maxChars`=4000 | `NoteDetail` | `note(id)`, Markdown cut at a line end, or inside a longer line |
| `create_note` | `markdown`, `folderId?`, `pinned`=false | `NoteDetail` | `save(null, folderId, paragraphs)` |
| `update_note` | `noteId`; P: `markdown?` or `append?`, `folderId?`, `pinned?` | `NoteDetail` | `save`, `moveNote`, `setPinned`; a note in Recently Deleted is `invalid_payload` |
| `delete_note` | `noteId` | none | `deleteNote` (to Recently Deleted, with its sub-notes) |
| `delete_note_permanently` | `noteId` | none | `deleteNoteForGood`, for a note in Recently Deleted |
| `restore_note` | `noteId` | `NoteDetail` | `restoreNote` |
| `create_folder` | `name`, `parentId?` | `FolderSummary` | `createFolder` |
| `update_folder` | `folderId`; P: `name?`, `parentId?` (`""` is the top level) | `FolderSummary` | `renameFolder`, `moveFolder` |
| `delete_folder` | `folderId` | none | `deleteFolder` (nested folders go for good, notes to Recently Deleted) |
| `update_settings` | P: `sort?`, `view?`, `moveTickedItems?` | `NotesSettings` | `setSort`, `setView`, `setMoveTickedItems` |

- `NoteSummary`: id, folderId?, title, preview, pinned, tags, createdAtEpochMs, editedAtEpochMs, deletedAtEpochMs?,
  daysLeft?, attachmentCount. `NoteDetail`: the same without preview, plus markdown, offset, totalChars, attachments.
- A `markdown` you would not keep is `invalid_payload` in create and in update: a blank note without attachments.
  Check before `save`, so an update never deletes; blank text on a note with attachments is kept.

### `reminders.attachments`, `notes.attachments` (3 each)

| Operation | Input | Output | Entry class (reminders) |
|---|---|---|---|
| `add` | `itemId`, `fileId`, `name`=`""` | `AttachmentInfo` (id, name, mimeType, sizeBytes) | `attach(id, AttachmentSource(name, mimeType) { staged stream })` |
| `remove` | `itemId`, `attachmentId` | none | `removeAttachment` |
| `open` | `itemId`, `attachmentId` | `FileHandle` (fileId, name, mimeType, sizeBytes) | registers a download handle for `openAttachment` |

`itemId` is a reminder id or a note id. A failed `add` leaves the staged file usable; after a timeout the caller reads
the item to see whether the attachment is there.

### `files` (3), the bytes as a Binder stream beside the call

| Operation | Stream | Input | Output | Provider |
|---|---|---|---|---|
| `upload` | caller to provider | `name`, `mimeType`, `sizeBytes` | `FileHandle` of a staged file | copy the incoming `InputStream` to private storage |
| `download` | provider to caller | `fileId` | `FileHandle` | offer the `InputStream` of the handle |
| `discard` | none | `fileId` | none | drop the staged file or the handle |

A staged file is used by one `add`, and an `add` that fails leaves it usable; a handle lasts at least 10 minutes
after its last use while the file exists, and belongs to the caller it was given to. `download` reads any handle you
gave the caller. Everything else about staging is yours.

What the provider module does for `files` (`FilesCapability`):

- Register `upload` with `onUpload(FilesCapability.Upload) { input, bytes -> ... }`, `download` with
  `onDownload(FilesCapability.Download) { input -> WithBytes(handle, ByteStream(size, stream)) }` and `discard` with
  `on`; `on` refuses a stream operation.
- Read and open files on `Dispatchers.IO` (`withContext(Dispatchers.IO)`): reads block, and the handler runs in the
  calling coroutine, on whatever dispatcher it uses.
- Before reading, check `UploadFileRequest.sizeBytes` against your own limit (`payload_too_large`) and against
  `bytes.sizeBytes` (`invalid_payload` when they differ).
- Read `bytes.input` to its end (until it answers -1, as `copyTo` or `readAllBytes` do): a stream that runs over is
  found only then. A stream of the wrong size throws `ByteStreamSizeException` (an `IOException`): delete the partly
  written file and let the exception escape, and Kora answers `invalid_payload`.
- Answer an upload within `streamIdleTimeout` (10 s by default) of its last byte: only stage the file, and leave slow
  work to the `add` that uses it. A caller that retries after a `timeout` stages a new file; the one it abandoned
  expires unused under the handle rules.
- For `download`, hand back the stream of the file and its exact size; from then on Kora and the receiver close it.
- Keep the three handle guarantees above; an id that is unknown, expired or another caller's is `invalid_payload`.
- The `files` operations are not tools for a model: MCP does not list `upload` and `download`, and the assistant's
  own code uploads with Kora's typed client and hands the model a `fileId`.

## Failures

`invalid_payload`: malformed request (checked by Kora before your handler), an id you do not have, or a rule you
reject (`ReminderRuleException`, `CalendarRuleException`, `NoteRuleException`). `provider_unavailable`: calendar
access missing. `payload_too_large`: a file over your limit.

## State of the Kora work

- The contract types, samples and fixtures of the five capabilities that need no stream (`reminders`, `calendar`,
  `notes`, the two attachments) are implemented on the branch, commits `c58d026` to `7a1d378` (the split of the
  former `7174b48`) and `768ecb9`, and changed by your review in the third round: `./gradlew build` passes. Kotlin
  objects: `RemindersCapability`, `CalendarCapability`, `NotesCapability`, `ReminderAttachments`, `NoteAttachments`,
  in packages `eus.bihurri.kora.contract.reminders`, `.calendar`, `.notes`; the shared attachment types and
  `FileHandle` are in `.files`. Unpublished: do not depend on it yet.
- The stream transport and `files@1.0` are implemented on the branch: the core stream API (`ByteStream`,
  `callSending`, `callReceiving`, `onUpload`, `onDownload`) in `8ff98da` and `797558f`; `FilesCapability`, with the
  stream operations left out of MCP, in `2c40007`; the Binder methods and protocol version 2 in `02e719b`, reviewed
  and approved. Still unpublished. Pending on a device: that a descriptor passed over Binder is readable by the other
  app, that closing a descriptor unblocks a read or write blocked on it, and what a pipe does when either process
  dies.
- Corrections to this file from the implementation: the repetition is eight fields (`repeatOrdinal` and
  `repeatDayKind` are two; nine with `repeatFromCompletion` since the third round); `get_note.maxChars` is 500 to 20 000; the contract cannot tell that Markdown has
  nothing written, so your provider rejects it; an enum constant your build does not know (a newer caller's, or a
  misspelling) reaches your handler as `UNKNOWN`, which Kora lets through on purpose, so your provider rejects it
  with `invalid_payload` wherever the field means something and never treats it as another constant (an `UNKNOWN`
  span on a repeating event changes and deletes nothing).
- No tag, push, publish or fetch.
