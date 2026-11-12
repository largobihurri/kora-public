# Organizer vocabulary: reminders, calendar, notes, files and recording

**Status: agreed design, revised after both rounds of remarks from the provider app's session, checked against
its three entry classes, and revised again after the provider app's review of the contract (2026-11-12, section 13).
The six capabilities of 1.3.0 and the stream transport are implemented on the branch, with their fixtures;
`calendar.attachments` and `notes.recording` are designed only.** Nothing is released without the owner's go-ahead.

- Base: Kora `1.2.0` (`v1.2.0`). Proposed release: **Kora `1.3.0`** (additive, so a minor; `docs/releasing.md`).
- Every capability below is new and starts at `1.0`; the attachments of events (section 8) and the recorder of
  section 10 are designed here and ship later. Existing capabilities and their wire format are not touched; their
  operations only gain an effect in the code (section 4), which is not on the wire.
- Feature document: `odd/tasks/kora-organizer.md`.

## 1. Purpose

An assistant app (KOKO) has to operate a reminders, calendar and notes app (Oharrak) completely through Kora:
read, create, change, complete, move, delete, restore and search everything its screens can; attach files it holds;
and command a voice recording and learn its result (in 1.3.0 a voice note is the assistant's own recording,
attached to a note; section 10). Kora supplies the vocabulary and the transport only. What a reminder, an event or
a note *does* stays in the app that provides them.

The vocabulary is app-neutral: any app can provide `reminders`, `calendar` or `notes`, as any app can provide
`media.library`.

## 2. What shaped it

Kora's rules (`docs/compatibility.md`, `Specs.kt`):

- A capability is atomic: a provider implements every operation and event of it. Optional features are separate
  capabilities, never optional members.
- A minor version only adds. A caller built against `x@1.1` is refused by a provider of `x@1.0`, so anything the
  provider app will ship later than the rest must be its own capability, not a later minor of a capability it already
  provides (see section 12, "Lockstep").
- Ids are opaque strings, never file paths or URIs. Contract types are `@Serializable`, without polymorphism (the
  schema generator turns a polymorphic type into the open schema `{}`).
- One message is at most 256 KiB on every transport. A binary field has a byte cap in the contract.

How the assistant turns Kora into tools (read in KOKO, `tools/kora/`):

- One tool per operation of `Capabilities.registry`, named `<capability>__<operation>`; events are never tools.
- The input schema is flattened: scalars, enums and arrays of scalars reach the model with their type; a nested
  object of scalars is lifted into top-level parameters (`page` becomes `limit` and `offset`); any other nested
  object, and an array of objects, reaches the model as an untyped `object` or `array` (`KoraSchema.kt`).
- A scope that ends in `.read` is a read that runs without asking; any other scope is a change the user confirms
  (`KoraRisk.kt`). The confirmation shows the operation name. From 1.3.0 the assistant also reads the effect each
  operation declares (`OperationSpec.effect`, section 4), so a change that cannot be undone can be confirmed
  differently from one that can.
- A call has 8 seconds. A result is cut at 8 000 characters. Of a failure the model learns only the error code, never
  the message (`KoraErrors.kt`).

Consequences, applied everywhere below:

1. **Every request is flat**: scalars, wire enums and arrays of scalars. No nested request objects.
2. **Reads and changes have different scopes**, `<capability>.read` and `<capability>.write` (`.control` for the
   recorder), so the assistant's risk rule works without a table of exceptions.
3. **Create and change are separate operations**, so the confirmation the user reads says which one it is.
4. **Answers are small**: with default arguments an answer fits 8 000 characters; lists are paged and carry
   summaries, and the full item is one `get_*` away.
5. **One operation is one kind of consequence**, since the confirmation shows the operation and not its fields.
6. **Every operation declares its effect**, read, write or destructive, so a caller knows what a call does to the
   user's data without a table of its own.

## 3. The capabilities

| Capability | Scopes | Operations | Events | Needs |
|---|---|---|---|---|
| `reminders@1.0` | `reminders.read`, `reminders.write` | 14 | | |
| `calendar@1.0` | `calendar.read`, `calendar.write` | 10 | | |
| `notes@1.0` | `notes.read`, `notes.write` | 12 | | |
| `reminders.attachments@1.0` | `reminders.attachments.read`, `.write` | 3 | | `reminders`, `files` |
| `notes.attachments@1.0` | `notes.attachments.read`, `.write` | 3 | | `notes`, `files` |
| `files@1.0` | `files.read`, `files.write` | 3 | | a transport that carries streams |

45 operations and no events. Two more capabilities are designed and not part of this release:
`calendar.attachments` (section 8), which waits for a provider with a store for the attachments of events, and
`notes.recording` (section 10). "Needs" is a documented dependency between capabilities of the same provider, as
`media.artwork` needs the ids of `media.library`.

Why six capabilities and not three: a capability is atomic, and a tool is on offer only when its capability is
provided. The attachments capabilities have one shape and stay separate from their sections, so that no section has
to wait for its attachments to be finished in order to be provided at all. Their shape is the one the reminders
domain already has (attach from a name, a type and bytes; list; open; remove). The owner asked for attachments in
the three sections; the calendar's are designed with the same shape and arrive in a later release, because the
phone's calendar has no place to keep them (owner's decision, 2026-11-12).

## 4. Conventions shared by every capability

- **Ids** are opaque strings the provider chooses; a request rejects a blank id.
- **Local dates and times** are ISO 8601 strings without a zone: a date `2026-11-10`, a time of day `17:30`, a date
  and time `2026-11-10T17:30`. They mean the wall clock where the user is. A reminder's due date has no zone by
  nature, so epoch milliseconds cannot carry it; a model also fills these far more reliably than a 13-digit number.
  A field has one format whatever the other fields say.
- **A wall time inside a clock change**: one that does not exist (the hour skipped in spring) means the same time
  after the change, later by the length of the gap; one that happens twice (autumn) means the first of the two.
  This is what `java.time` does when a local time is placed in a zone.
- **Instants** (created, edited, completed, deleted) stay epoch milliseconds in `...EpochMs` fields, the existing
  Kora convention.
- **Create**: an absent optional field is the provider's to fill (an end time, a default list).
- **Change (`update_*`) is a patch**: only the id is required; every other field is nullable and `null` or absent
  means "leave as it is". To empty a field send an empty string (texts, dates, times, repetition), an empty list
  (`tags`, `alertMinutes`) or `0` (amounts). Enums and booleans always take a value. The end of an event is the one
  field that cannot be emptied (section 6).
- **A change is applied whole or not at all.** A request that is rejected leaves the item as it was, whichever of
  its fields was at fault.
- **A change never deletes and never completes.** Deleting, completing, reopening and snoozing are operations of
  their own, so the confirmation the user reads names what will happen (section 11).
- **Every create and change answers the resulting item**, so the caller sees what was stored. Deletions answer
  nothing.
- **Answers fit 8 000 characters with default arguments**, the size at which the assistant cuts a result:
  - every listing takes `limit` (default 20, at most 50) and `offset` as flat fields and answers a `Page` with the
    `total`; the existing `PageRequest`, whose default is 50, is not used here;
  - an overview pages the collections it carries with the same `limit` and `offset` and gives their totals;
  - an item's answer carries counts instead of unbounded lists (the subtasks of a reminder are a listing);
  - the content of a note is read in parts (section 7).
  What is left: a single text field of one item (the notes of a reminder or an event) is answered whole.
- **The contract checks the wire format only**: a blank id, a date or a time that does not parse, a `limit`, an
  `offset`, a day of the month or a month out of range, two fields that exclude each other, more than one selector
  in a listing, and fields of one request that need each other (a time without a date in a new reminder, a
  `repeat*` field beside `repeatUnit` = `none`, a kind of day without its ordinal, a list and a parent in a new
  reminder, an empty `end` or an `rrule` with `span` = `this` in a change of an event). Such a request is
  `invalid_payload` before it reaches the provider. The rules of the domain (a title that is blank, an end before
  a start, the last list) are the provider's, and so are the defaults. Answer types carry no checks, so a newer
  provider never breaks an older reader.
- **Every operation declares its effect** (`OperationSpec.effect`, the enum `OperationEffect`): `READ` changes
  nothing the user keeps, `WRITE` changes something in a way that can be undone or edited back (creating, editing,
  moving, completing, moving an item to a trash it can be restored from), `DESTRUCTIVE` loses something for good.
  Every operation whose scope ends in `.read` is `READ`, and no other is. The effect is a plain enum, never
  serialized, like `StreamDirection`: no descriptor, envelope or fixture carries it, and each side reads it from its
  own definition of the operation. Every built-in operation declares it, the earlier capabilities' too; the older
  `Capability.operation` overloads without an effect stay for binary compatibility and give `DESTRUCTIVE`, the most
  careful reading of an operation nothing is known about, as MCP does for a tool without annotations. MCP publishes
  the effect as the standard tool annotations `readOnlyHint` and `destructiveHint`; the assistant reads it from
  `OperationSpec`. The destructive operations of 1.3.0 are `reminders.delete_reminder`, `delete_list`,
  `delete_tag`, `calendar.delete_event`, `delete_calendar`, `notes.delete_note_permanently`, `delete_folder` and
  the `remove` of both attachments capabilities.
- **Enums** are wire enums in snake_case with the `unknown` fallback. A request is no exception: a constant this
  version does not know (sent by a newer caller, or misspelled) decodes as `unknown` and the contract lets it through,
  because old Kora reads new Kora (`docs/compatibility.md`). Where the field means something, the provider rejects
  it with `invalid_payload` and never reads it as another constant: on a repeating event an `unknown` span changes
  and deletes nothing.
- **Failures**, with the existing codes only:
  - `invalid_payload`: a malformed request, an id the provider does not have (the convention of `media.*`), or a
    change the provider's rules reject. The message says which.
  - `provider_unavailable`: the provider cannot serve this until the user acts in the app, such as calendar access
    not granted. The assistant already tells the user to open the app for this code.
  - `payload_too_large`: as today, and a file over the provider's size limit.
- **Growing without breaking.** A later minor adds optional request fields, answer fields, enum constants and
  operations; nothing here is repurposed. Section 12 names, for everything known to be missing, which of those it
  will be.

## 5. `reminders@1.0`

Lists, reminders, one level of subtasks, tags, repetition, the early alert, snoozing, smart lists and search.

Enums: `ListColor` (`red`, `orange`, `yellow`, `green`, `blue`, `purple`, `brown`); `ReminderPriority` (`none`,
`low`, `medium`, `high`); `SmartList` (`today`, `scheduled`, `all`, `flagged`, `completed`); `EarlyAlertUnit`
(`minutes`, `hours`, `days`, `weeks`, `months`); `Snooze` (`in_one_hour`, `tomorrow`); `RepeatUnit` (`none`,
`hour`, `day`, `week`, `month`, `year`); `Weekday` (`monday` to `sunday`); `RepeatOrdinal` (`none`, `first`,
`second`, `third`, `fourth`, `fifth`, `last`); `RepeatDayKind` (the seven weekdays, `day`, `weekday`,
`weekend_day`).

| Operation | Scope | Input | Output |
|---|---|---|---|
| `get_overview` | read | `limit`, `offset` | `RemindersOverview` |
| `list_reminders` | read | `ListRemindersRequest` | `Page<ReminderSummary>` |
| `get_reminder` | read | `reminderId` | `ReminderDetail` |
| `create_reminder` | write | `CreateReminderRequest` | `ReminderDetail` |
| `update_reminder` | write | `UpdateReminderRequest` | `ReminderDetail` |
| `complete_reminder` | write | `reminderId`, `dueDate` = `""`, `dueTime` = `""` | `ReminderDetail` |
| `reopen_reminder` | write | `reminderId` | `ReminderDetail` |
| `snooze_reminder` | write | `reminderId`, `snooze`, `dueDate`, `dueTime` = `""` | `ReminderDetail` |
| `delete_reminder` | write | `reminderId` | none |
| `create_list` | write | `name`, `color` | `ReminderListSummary` |
| `update_list` | write | `listId`, `name?`, `color?` | `ReminderListSummary` |
| `delete_list` | write | `listId` | none |
| `rename_tag` | write | `tag`, `newName` | `TagName` (`name`) |
| `delete_tag` | write | `tag` | none |

The effect of each operation: the three reads are `READ`; `delete_reminder`, `delete_list` and `delete_tag` are
`DESTRUCTIVE`; the other changes are `WRITE`.

Payloads:

- `RemindersOverview`: `smartCounts` (`today`, `scheduled`, `all`, `flagged`, `completed`: integers), `lists:
  List<ReminderListSummary>` and `listCount`, `tags: List<String>` and `tagCount`, `defaultListId?`. `limit` and
  `offset` window both `lists` and `tags`.
- `ReminderListSummary`: `id`, `name`, `color`, `openCount`.
- `ListRemindersRequest`: one selector at most, `listId?`, `smartList?`, `tags` = `[]` (reminders that carry all of
  them) or `parentId?` (the subtasks of that reminder); then `includeCompleted` = `false`, `limit`, `offset`. With
  no selector it lists every open reminder. One operation replaces a listing per smart list, per list, per tag and
  the subtasks.
  - A listing of a list holds its top-level reminders, each with its `subtaskCount`; the subtasks of a reminder are
    the listing by `parentId`. A smart list, tags and no selector list every reminder that matches, a subtask
    included, which carries its `parentId`.
  - `includeCompleted` is honoured by a list (`listId`) only. A smart list ignores it and shows what it always
    shows (`completed` is the smart list of completed reminders); with the other selectors the provider may ignore
    it.
- `ReminderSummary`: `id`, `listId`, `parentId?`, `title`, `dueDate?`, `dueTime?`, `repeats`, `flagged`, `priority`,
  `completed`, `completedAtEpochMs?`, `tags`, `subtaskCount`, `attachmentCount`. Items come in the provider's
  display order (for a list grouped in sections, the order the app shows it in, section by section, not a flat
  sort); a subtask carries `parentId`.
- `ReminderDetail`: the fields of `ReminderSummary`, plus `notes`, the nine `repeat*` fields, `seriesStartDate?`, `url`,
  `earlyAlertAmount`, `earlyAlertUnit`, `snoozedUntilEpochMs?`, `createdAtEpochMs` and `attachments:
  List<AttachmentInfo>` (empty unless the provider also offers `reminders.attachments`).
- `CreateReminderRequest`: `title`; `listId?` (absent: the default list); `parentId?` (makes it a subtask of that
  reminder, in its list, so `listId` and `parentId` together are `invalid_payload`); `notes` = `""`; `dueDate` =
  `""`; `dueTime` = `""` (a `dueTime` without a `dueDate` is `invalid_payload`); the `repeat*` fields; `flagged` =
  `false`; `priority` = `none`; `url` = `""`; `tags` = `[]`; `earlyAlertAmount` = `0` (no early alert);
  `earlyAlertUnit` = `minutes`. A provider may read a `#word` in the title as a tag; the answer shows the title and
  the tags it stored.
- `UpdateReminderRequest`: `reminderId`, then every field of the create request but `parentId`, as a patch, plus
  `listId?` (moves it, with its subtasks; a subtask moved alone leaves its parent; a `listId` equal to the current
  list changes nothing, so the reminder keeps its parent and its place). Emptying `dueDate` also empties `dueTime`,
  the repetition and the early alert, which mean nothing without a date.

**Completing names the occurrence.** `complete_reminder` carries the `dueDate` (and `dueTime`) the caller saw. If
the reminder no longer stands at that occurrence (it was completed in the app, or by this same call a moment ago),
nothing changes and the answer shows the reminder as it is: a repeat after a timeout cannot skip an occurrence.
Without a `dueDate` it completes the reminder as it stands, which is right for one without a date. A repeating
reminder moves to its next occurrence and stays open, which the answer shows. `snooze_reminder` works the same way
on the alert of that occurrence and moves only the alert, never the due date.

**Repetition is nine flat fields**, the shape of the rule the provider app's reminders have, so nothing is
converted and nothing is left to a dialect:

| Field | Type, default | Meaning |
|---|---|---|
| `repeatUnit` | `RepeatUnit`, `none` | does not repeat, or by the hour, day, week, month or year |
| `repeatInterval` | integer, `1` | every N units |
| `repeatWeekdays` | list of `Weekday`, `[]` | by the week: on these days |
| `repeatMonthDays` | list of integers 1 to 31, `[]` | by the month: on these days |
| `repeatOrdinal` | `RepeatOrdinal`, `none` | by the month: on the first to fifth or last day of the kind `repeatDayKind` names |
| `repeatDayKind` | `RepeatDayKind`, `day` | the kind `repeatOrdinal` counts: a day of the week (Monday to Sunday), any day, a working day or a weekend day |
| `repeatMonths` | list of integers 1 to 12, `[]` | by the year: in these months |
| `repeatUntil` | date, `""` | the last day it may fall on; empty is never |
| `repeatFromCompletion` | boolean, `false` | the next occurrence counts from the day the reminder is completed, not from its due date |

- In a change the nine fields are one group: with `repeatUnit` present the rule is replaced by the `repeat*`
  fields of the request, the absent ones taking their defaults; without it the rule is left alone and any other
  `repeat*` field is rejected. `repeatUnit` = `none` stops the repetition.
- The contract checks two things between these fields, in a create and in a change: with `repeatUnit` = `none` no
  other `repeat*` field is set (in a create, every other field keeps its default), and a `repeatDayKind` needs a
  `repeatOrdinal` other than `none` (in a create, a kind other than `day`).
- Which combinations make sense, and the day a rule lands on when a month lacks it, are the provider's rules. The
  calendar's `rrule` is a different thing, an exact RFC 5545 rule: the two are deliberately not one field.
- The series counts from `seriesStartDate`. Changing `dueDate` or the repetition starts a new series at the new due
  date; changing only `dueTime` does not.

A first version of this note carried the rule as one `RRULE`-like string. It was dropped: the provider would have
needed a converter and the string would not have meant what RFC 5545 says. Enumerated fields are also checked by
the assistant before the call, which a free string is not.

## 6. `calendar@1.0`

Calendars of every account on the phone, events, repetition, alerts, and the settings of the section.

One occurrence of a repeating event is named by an **`occurrenceId`**, an opaque string the provider makes (for
example from the event and the instant the occurrence begins). Listings give it, and reading, changing and deleting
take it, so the caller never handles an instant. A change or deletion says how far it reaches with `span`: `this`,
`following` or `all`. On an event that does not repeat it is ignored, except that an `rrule` there, which makes
the event repeat, needs `all`.

- **`EventDetail.spans` lists the spans this occurrence accepts**; it is empty for an event that does not repeat. A
  rule kept as another calendar wrote it accepts only `this` and `all`. On a repeating event a span outside the
  list is `invalid_payload`; on one that does not repeat the list is not checked.
- **`span` defaults to `this`.** The provider app's own default is the whole series; the contract differs on
  purpose, because a caller that forgets the field then changes one occurrence and not every one.
- **A change with `this` or `following` can make a new event, and ids change.** Any change of an event's calendar
  or start can also give it new ids. The answer carries the `occurrenceId` and `eventId` to use from then on;
  earlier ones may no longer resolve.
- **A repetition is made, changed or stopped for the series or the following occurrences, never for one.** An
  `rrule` in `update_event` needs `span` = `all` or `following` (`all` on an event that does not repeat); with
  `this` it is `invalid_payload`.

Enums: `EventAvailability` (`busy`, `free`); `EventSpan` (`this`, `following`, `all`).

| Operation | Scope | Input | Output |
|---|---|---|---|
| `get_overview` | read | `limit`, `offset` | `CalendarOverview` |
| `list_events` | read | `ListEventsRequest` | `Page<EventOccurrence>` |
| `get_event` | read | `occurrenceId` | `EventDetail` |
| `create_event` | write | `CreateEventRequest` | `EventDetail` |
| `update_event` | write | `UpdateEventRequest` | `EventDetail` |
| `delete_event` | write | `occurrenceId`, `span` = `this` | none |
| `create_calendar` | write | `name`, `color` | `CalendarSummary` |
| `update_calendar` | write | `calendarId`, `name?`, `color?`, `visible?` | `CalendarSummary` |
| `delete_calendar` | write | `calendarId` | none |
| `update_settings` | write | `defaultCalendarId?`, `showReminders?` | `CalendarOverview` |

The effect of each operation: the three reads are `READ`; `delete_event` and `delete_calendar` (which takes all its
events with it) are `DESTRUCTIVE`; the other changes are `WRITE`.

Payloads:

- `CalendarOverview`: `accessGranted` (false when the app may not read the phone's calendars: then `calendars` is
  empty and the other operations answer `provider_unavailable`), `calendars: List<CalendarSummary>` and
  `calendarCount`, `defaultCalendarId?`, `showReminders`.
- `CalendarSummary`: `id`, `name`, `accountName`, `accountType`, `color`, `visible`, `writable`, `local` (made in
  the app: only these are renamed, recoloured and deleted). **A calendar colour is the string `#RRGGBB`**, in
  answers and in requests: account calendars come in any colour, so it is not an enum.
- `ListEventsRequest`: `from`, `to` (dates, both included), `calendarIds` = `[]` (narrows the visible calendars to
  these), `limit`, `offset`. It answers the occurrences that touch those days, in the order they start; one that
  lasts several days appears once.
- `EventOccurrence`: `occurrenceId`, `eventId`, `calendarId`, `title`, `location`, `allDay`, `start`, `end`,
  `repeats`.
- `EventDetail`: the fields of `EventOccurrence`, plus `timeZone` (the IANA id of the zone the event's rule is read
  in, such as `Europe/Madrid`), `rrule`, `spans: List<EventSpan>`, `alertMinutes: List<Int>`, `availability`,
  `url`, `notes` and `editable`. It carries no attachments in 1.3.0 (section 8).
- `CreateEventRequest`: `title`; `start`; `end` = `""` (absent: the provider chooses the length); `allDay` =
  `false`; `calendarId?` (absent: the default calendar); `location` = `""`; `rrule` = `""`; `alertMinutes?`
  (minutes before the start; negative is after it, so "on the day at 09:00" of an all-day event is `-540`; absent
  or `null` gives the calendar's default alerts, an empty list none); `availability` = `busy`; `url` = `""`;
  `notes` = `""`.
- `UpdateEventRequest`: `occurrenceId`, `span` = `this`, then every field of the create request as a patch. An
  event always has an end, so `end` = `""` is `invalid_payload`; a new `start` without an `end` keeps the event's
  length.
- **`url`**: a provider whose calendar has no field for it may keep it as the last line of `notes`, as the phone's
  calendar does.
- **`start` and `end` are always a date and a time**, `2026-11-10T17:30`. For an all-day event only their days
  count, both included, and the time is ignored; so a patch that flips `allDay` needs no other field.
- `rrule` is an iCalendar `RRULE` value (RFC 5545) without the `RRULE:` prefix, for example
  `FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE`; empty means "does not repeat". It is exact, but the provider may rewrite it
  in a canonical form, and the answer carries the rule as stored: a calendar keeps rules other calendars wrote, and
  an end by date and by count are both valid. A `UNTIL` in it is read as in RFC 5545: ending in `Z` it is UTC,
  otherwise a time in the event's `timeZone`. A new event takes the phone's time zone.

The reminders a calendar screen shows are reminders: a caller reads them with `reminders.list_reminders`
(`smartList` = `scheduled`) and completes them with `reminders.complete_reminder`. Here only the switch that shows them is
exposed (`showReminders`).

## 7. `notes@1.0`

Folders nested to any depth, notes with their whole content and styles, tags, pin, search, Recently Deleted, and
the settings of the section.

**The content of a note travels as Markdown**, in one `markdown` field. The provider converts between Markdown and
its own model and is the authority on the result. Reasons: every real consumer is a language model, which reads and
writes Markdown natively; it is several times smaller than a list of styled paragraphs, which matters under an
8 000 character limit; and a new kind of block in the provider needs no new Kora version. The alternative, a typed
list of paragraphs with style and inline spans, reaches a small model as an untyped array and needs a Kora minor for
every new style.

What every provider reads and writes:

| Markdown | Meaning |
|---|---|
| a line | one paragraph: a line break ends it, and an empty line is an empty paragraph |
| `# `, `## `, `### ` at the start of a line | the three levels of heading |
| the lines between two lines of three backticks | monospaced |
| `> ` | block quote |
| `- ` or `* ` | list item |
| `1. ` | numbered list item |
| `- [ ] `, `- [x] ` | checklist item, to do and done |
| four spaces or a tab before a list marker | one more level of indentation |
| `**bold**`, `*italic*`, `<u>underline</u>`, `~~strikethrough~~`, `` `code` `` | inline styles |
| `#tag`, a `#` and a word | a tag of the note: the tags are part of the text |
| `\` before a marker | the character itself |

Three differences from ordinary Markdown, stated because a writer will meet them:

- **It is line-based.** Ordinary Markdown joins lines into a paragraph and separates paragraphs with a blank
  line; here a blank line shows as an empty paragraph. A writer that wants no gaps writes none.
- **The title is the first line with text**, whatever its style. A heading mark is a style, not what makes a title.
- **`- ` and `* ` are both list items.** A provider with two bullet styles may tell them apart, and writes back the
  marker it read.

What a round trip keeps, the guarantee both sides rely on:

- **What a provider returns in `markdown`, sent back unchanged, gives the same note.** Nothing is lost by reading
  and then writing.
- A provider writes anything outside the table (a table, a colour, a highlight, a drawing, a link to another note)
  in a form it reads back itself: Markdown where it exists, an HTML tag such as `<mark>`, a reference to an
  attachment. A caller that does not understand a piece leaves it as it is.
- What a caller writes that the provider does not understand is kept as the literal text, never dropped. So a
  caller can write only the styles the provider reads; the table above is the part that is certain.

Enums: `NoteSort` (`date_edited`, `date_created`, `title`); `NotesView` (`list`, `gallery`).

| Operation | Scope | Input | Output |
|---|---|---|---|
| `get_overview` | read | `limit`, `offset` | `NotesOverview` |
| `list_notes` | read | `ListNotesRequest` | `Page<NoteSummary>` |
| `get_note` | read | `noteId`, `offset` = `0`, `maxChars` = `4000` | `NoteDetail` |
| `create_note` | write | `markdown`, `folderId?`, `pinned` = `false` | `NoteDetail` |
| `update_note` | write | `UpdateNoteRequest` | `NoteDetail` |
| `delete_note` | write | `noteId` | none |
| `delete_note_permanently` | write | `noteId` | none |
| `restore_note` | write | `noteId` | `NoteDetail` |
| `create_folder` | write | `name`, `parentId?` | `FolderSummary` |
| `update_folder` | write | `folderId`, `name?`, `parentId?` | `FolderSummary` |
| `delete_folder` | write | `folderId` | none |
| `update_settings` | write | `sort?`, `view?`, `moveTickedItems?` | `NotesSettings` |

The effect of each operation: the three reads are `READ`; `delete_note_permanently` and `delete_folder` are
`DESTRUCTIVE`; the other changes, `delete_note` included, are `WRITE`, since what they do can be undone.

Payloads:

- `NotesOverview`: `allNotesCount`, `folders: List<FolderSummary>` (in tree order) and `folderCount`, `tags:
  List<TagSummary>` and `tagCount`, `recentlyDeletedCount`, `settings: NotesSettings`. `limit` and `offset` window
  both `folders` and `tags`.
- `FolderSummary`: `id`, `parentId?`, `name`, `depth`, `noteCount`, `isDefault`. `TagSummary`: `name`, `noteCount`.
- `NotesSettings`: `sort`, `view`, `moveTickedItems`.
- `ListNotesRequest`: one selector at most, `folderId?`, `tags` = `[]` (the notes that carry all of them, each named
  by its exact lower-case name without `#`), `deleted` = `false` (`true` lists Recently Deleted) or `query` = `""`
  (searches the titles and the text of every note; a query of only whitespace is no search and no selector); then
  `limit`, `offset`. With no selector it lists every note. Items come in the provider's order, for example the
  pinned notes first, a search by the last edit and Recently Deleted by the deletion.
- `NoteSummary`: `id`, `folderId?`, `title`, `preview` (the beginning of the text, or the part that matched
  `query`), `pinned`, `tags`, `createdAtEpochMs`, `editedAtEpochMs`, `deletedAtEpochMs?`, `daysLeft?` (in Recently
  Deleted: days until it is deleted for good), `attachmentCount`.
- `NoteDetail`: the fields of `NoteSummary` without `preview`, plus `markdown`, `offset`, `totalChars` and
  `attachments: List<AttachmentInfo>` (empty unless the provider also offers `notes.attachments`).
- **A note is read in parts.** `get_note` answers at most `maxChars` characters of the content from `offset`, cut
  at the end of a line, or inside a line longer than `maxChars` (`maxChars` is 500 to 20 000, so the largest part
  fits one message on every transport). Characters are UTF-16 units. `offset` plus the length of `markdown` is
  where the next part starts, and `totalChars` says when the note is whole. A short note comes in one answer. The
  answers of the write operations carry the first part. The default part is 4 000 characters because `NoteDetail`
  also lists every attachment, a list without a bound: a dozen attachments and a part of the default size still fit
  the 8 000 characters an assistant shows.
- `UpdateNoteRequest`: `noteId`, `markdown?` (replaces the whole content), `append?` (Markdown added after the last
  line: the common "add this to my note", and the way to write a long note without resending it; an empty
  `append` adds nothing), `folderId?` (moves it), `pinned?`. `markdown` and `append` together are rejected. A note
  in Recently Deleted is not changed: `update_note` of it is `invalid_payload`, and `restore_note` brings it back
  first.
- **A change never deletes a note.** A `markdown` the provider would not keep (nothing written in it, on a note
  without attachments) is `invalid_payload`, in `create_note` and in `update_note`; a provider whose own rule is "a
  note without content is not kept" does not apply it to `update_note`. Only the deletions delete.
- **Deleting is two steps.** `delete_note` moves a note, with any sub-notes it has, to Recently Deleted, from where
  `restore_note` brings it back. `delete_note_permanently` deletes for good a note that is already there, with its
  sub-notes; it cannot be undone. They are two operations because one is recoverable and the other is not
  (section 2, rule 5). `delete_folder` deletes the folder and every folder nested in it for good and moves their
  notes to Recently Deleted. `update_folder` with `parentId` = `""` moves the folder to the top level.

## 8. Attachments: `reminders.attachments@1.0`, `notes.attachments@1.0` (and `calendar.attachments`, designed)

Capabilities with the same three operations and the same payloads (one definition in the contract,
`AttachmentsCapability`). In 1.3.0 there are two: `itemId` is a `reminderId` or a `noteId`.

| Operation | Scope | Input | Output |
|---|---|---|---|
| `add` | write | `itemId`, `fileId`, `name` = `""` | `AttachmentInfo` |
| `remove` | write | `itemId`, `attachmentId` | none |
| `open` | read | `itemId`, `attachmentId` | `FileHandle` |

The effect of each operation: `open` is `READ`, `add` is `WRITE` and `remove`, which loses the file for good, is
`DESTRUCTIVE`.

- `AttachmentInfo`: `id`, `name`, `mimeType`, `sizeBytes`. The attachments of an item are listed in its
  `get_reminder` or `get_note` answer.
- `add` takes a file the caller staged with `files.upload` (section 9) and attaches it; the staged file is used up.
  `name` replaces the name it was uploaded with. No request carries bytes, so `add` and `remove` are ordinary
  tools for a model.
- **An `add` can take time**, because the provider may copy the file. An `add` that fails leaves the staged file as
  it was, usable by another `add`. After a `timeout` the caller cannot tell whether the file was attached: it reads
  the item again (`get_reminder`, `get_note`) to see whether the attachment is there before it tries again.
- `open` answers a `FileHandle` to read the bytes with `files.download`.

**`calendar.attachments` is designed but not in 1.3.0** (owner's decision, 2026-11-12). The phone's calendar has no
place to keep a file with an event, so a provider needs its own store keyed by event first. It arrives later as an
additive capability, `calendar.attachments@1.0`, with the same definition, `itemId` an `occurrenceId`, and the
attachments of an event in `EventDetail`, an optional field added to `calendar` in the same release. The design it
starts from: **an event's attachments follow its occurrences.** An occurrence shows the attachments of its event.
When a change with `this` or `following` makes a new event out of an occurrence, the new event starts with the
attachments the occurrence showed; from then on each has its own. Naming the item by `occurrenceId` means the caller
never has to know which event keeps them.

## 9. `files@1.0`: handing a file from one app to another

### What Kora can do today

| Path | Limit of one message | A file in one message |
|---|---|---|
| Binder between apps (CBOR) | 256 KiB (`PayloadLimits`) | up to about 255 KiB |
| Phone to watch (relay frames) | 256 KiB (`WearProtocol`) | the same |
| MCP: adb gate, tailnet HTTP (JSON) | 256 KiB (`McpGate`) | 90 KiB, because an answer holds the Base64 twice (`MediaArtwork`) |

Binary fields exist (`@ByteString` with `BytesSerializer`), each with a cap in the contract. So a file under about
90 KiB crosses every transport today in one call, which is how `media.artwork` works. A photo, a PDF or a voice
recording does not fit.

### Three ways to carry a larger file

| | Vocabulary in chunks | Content URI with a permission grant | Binder stream (chosen) |
|---|---|---|---|
| Transport change | none | none in Kora, a `FileProvider` and grants in every app | new Binder methods and protocol version, a stream API in `kora-core` |
| Works over | every transport, every caller | Android apps on one phone only | Android apps on one phone only, until an equivalent is written for the relay and MCP |
| Kora rules | kept | breaks "never a file path or URI"; the receiver opens an address the sender names (confused deputy) | kept: the owner of a file opens it and hands over a descriptor, nobody names an address |
| Cost of a 5 MB photo | 57 calls of 90 KiB | one call | one call |
| Size of the work | a capability | small in Kora, new work and a security review in every app | large: contract, core and Android transport |

**Decided by the owner (2026-11-10): the Binder stream, now.** A file crosses in one call as a stream beside the
call's envelope. The consequences accepted with it:

- Files cross only between Android apps on the same phone. An agent over MCP (adb or the tailnet) and the watch
  cannot hand a file over or read one until an equivalent is written for those paths. Everything else in this
  vocabulary, including `add`, `remove` and `open`, works on every transport.
- It is a change to a published transport, so it is the high-risk part of the release (section 14).

### What is added at the transport level

1. **Contract (`kora-contract`).** An operation can declare that a byte stream travels beside its envelope:
   `OperationSpec.stream`, a `StreamDirection` of `none` (default, every existing operation), `upload` (caller to
   provider) or `download` (provider to caller). The input and output types stay ordinary serializable types
   without bytes, so the wire fixtures of a stream operation are fixtures like any other.
2. **Core (`kora-core`).** The caller sends or receives the stream with the call
   (`node.callSending(spec, input, source)`, `node.callReceiving(spec, input)`), and the provider's handler takes
   the incoming stream or offers the outgoing one. A local call passes the stream directly. A transport says
   whether it carries streams (`StreamingPeerTransport`); a stream operation sent over one that does not fails at
   the caller with `provider_unavailable`, before anything is sent. Policy, version check and audit run on the
   envelope exactly as for any call, before a byte is read.
3. **Android (`kora-android`).** `IKoraNode` gains two appended methods, one per direction, that carry the same CBOR
   request and response plus a `ParcelFileDescriptor`: the sender passes the read end of a pipe or its own file
   opened read-only, and the receiver copies from it. `KoraAndroid.PROTOCOL_VERSION` becomes 2.
   - **Found while reading the transport:** `BinderConnector` today refuses a peer whose protocol version is lower
     than its own. Released as is with version 2, an app on Kora 1.3 would stop seeing every app still on 1.2. The
     check becomes a minimum (version 1 stays accepted), and the stream methods are used only with a peer that
     reports 2 or more; with an older peer a stream operation is `provider_unavailable`. An app on Kora 1.2 already
     accepts a peer on version 2.
   - A stream call is not bound by the 5 s call timeout: it fails when no byte moves for a set time instead.
   - The receiver reads at most the declared size and rejects a stream that ends short or runs over; both sides
     close the descriptor on every path.
4. **MCP (`kora-mcp`), JSON gate, relay.** No stream. A stream operation is not offered as an MCP tool; through the
   JSON gate or the relay it fails with `provider_unavailable`.

### Implementation decisions (T3a)

Taken while implementing, within the design above; `odd/tasks/kora-organizer.md` L22 records why.

- **Contract.** `StreamDirection` (`NONE`, `UPLOAD`, `DOWNLOAD`) is a plain enum, never serialized: each side
  reads the direction from its own registry, and no descriptor, envelope or fixture changes. `OperationSpec.stream`
  defaults to `NONE`; `Capability.operation` gains an overload with `stream`, and the five-argument form stays for
  binary compatibility.
- **Bytes in core.** One type both ways, `ByteStream(sizeBytes: Long, input: InputStream)`, over `java.io` (no new
  dependency). The declared size travels with the stream, not in the typed input, so core stays generic; the upload
  request's `sizeBytes` is the provider's to cross-check. A receiver sees an input that reads exactly `sizeBytes`
  bytes and fails when the stream ends short or runs over.
- **Calls.** `node.callSending(spec, input, bytes, target)` answers `O`; `node.callReceiving(spec, input, target)`
  answers `WithBytes<O>(output, bytes)`, which the caller closes. A provider registers `onUpload(spec) { input,
  bytes -> output }` and `onDownload(spec) { input -> WithBytes(output, bytes) }`; `on` refuses a stream operation
  and the new two refuse any other. A direction that does not match the spec is a programming error.
- **Envelope-only paths.** A request for a stream operation that arrives without a stream (the JSON gate, the binary
  gate, the relay, a version-1 Binder call) is refused with `provider_unavailable` inside the node, after
  `authorize`, so a refused caller still learns nothing and every attempt is audited. A node that forwards to a
  peer refuses the same way before sending.
- **Transports.** `StreamingPeerTransport` extends `PeerTransport` without changing it. Its two calls throw
  `provider_unavailable` before writing when the peer cannot take a stream (a Binder peer below version 2). The
  routing transport streams only through a route whose transport streams. `Target.Any` does not skip a peer that
  cannot stream: the call fails. The in-memory network streams, and can be built without streams for tests.
- **Time.** A stream call is not bound by `callTimeout`. A streaming transport fails it when for
  `streamIdleTimeout` (default 10 s) neither a byte moves nor the answer arrives. A local stream call has no timer.
- **Audit.** The entry is written when the handler returns, as for any call: the outcome of the envelope. Bytes
  that flow after a download handler returns are not audited, and no size reaches the audit.
- **Binder.** Two appended methods, `oneway void callSending(in byte[] request, in ParcelFileDescriptor bytes, long
  sizeBytes, IKoraCallback callback)` and `oneway void callReceiving(in byte[] request, IKoraStreamCallback
  callback)`, and a new `IKoraStreamCallback.onResponse(in byte[] response, in ParcelFileDescriptor bytes, long
  sizeBytes)` with no descriptor when the answer is an error. `PROTOCOL_VERSION` is 2, the oldest accepted peer is
  1, and the first with streams is 2. The connector keeps the peer's version for each binding. Both sides close
  every descriptor they hold on every path; pumping runs off Binder threads.
- **`files`.** `upload` takes `UploadFileRequest(name, mimeType, sizeBytes)` and `download` and `discard` take
  `FileIdRequest(fileId)`. The contract checks the wire format only: a blank `fileId`, a negative `sizeBytes`. A
  blank name or MIME type, and the size limit, are the provider's. `files__discard` stays an MCP tool: only
  stream operations are left out.
- **Settled by the implementation** (L23, L24, and T3a4):
  - `call` refuses a stream operation with `IllegalArgumentException`: a programming error, not a failed call.
  - `handleSending` and `handleReceiving` answer `invalid_payload` for an operation of the other kind.
  - A streamed answer carries bytes exactly when it succeeds; a failure carries none.
  - A streaming transport reports its idle timeout as `timeout`, never as a cancellation.
  - The service's download pump allows a stalled provider twice `streamIdleTimeout`, so that the caller's read times
    out first and fails with `timeout` rather than with a stream that ends short.
  - An upload's answer must arrive within `streamIdleTimeout` of its last byte, because the caller's timer keeps
    counting until the answer. So the provider only stages the file in `upload` and does slow work in `add`; a caller
    that retries after a `timeout` stages a new file, and the abandoned one expires unused.
  - A size violation (`ByteStreamSizeException`, an `IOException`) of an upload's own bytes that escapes the handler
    answers `invalid_payload`, not `internal`, and is not reported to `onInternalError`.

Not verified, and it cannot be on the JVM: that a descriptor of a file in one app's private storage, passed over
Binder, is readable by the other app on the target Android versions (it is how content providers share files), that
closing a descriptor unblocks a read or write blocked on it (the JVM tests use in-memory pipes that behave so), and
the behaviour of the pipe when either process dies. They need a device or an emulator; the phone attached to this
machine is not to be used.

### The capability

| Operation | Scope | Stream | Input | Output |
|---|---|---|---|---|
| `upload` | write | upload | `name`, `mimeType`, `sizeBytes` | `FileHandle` |
| `download` | read | download | `fileId` | `FileHandle` |
| `discard` | write | none | `fileId` | none |

The effect of each operation: `download` is `READ`; `upload` and `discard` are `WRITE` (discarding drops a handle
and a staged copy of a file the caller holds, never an attachment).

- `FileHandle`: `fileId`, `name`, `mimeType`, `sizeBytes`.
- **Upload.** The request declares the name, the MIME type and the exact size, and the bytes follow as the stream.
  A size over the provider's own limit is `payload_too_large` before a byte is read. The answer is the handle of a
  **staged** file: it waits to be used once, by an `add` of an attachments capability of the same provider.
- **Download.** `download` reads the file of any handle the provider gave the caller, today the one `open` of an
  attachments capability answers: it answers the handle's description and the bytes as the stream. A handle can be
  downloaded any number of times. A later operation that answers a handle (an export of a note, for example) is
  read the same way, so adding one is additive.
- **Handles.** Three guarantees a caller relies on, and no more: a staged file is used by one `add`, and an `add`
  that fails leaves it usable; a handle stays valid at least 10 minutes after its last use while the file exists
  (an attachment removed, or an item deleted for good, takes its handles with it); a handle belongs to the caller
  it was given to, and from anyone else it is an unknown id (`invalid_payload`). `discard` drops one at once. How
  the provider keeps and cleans staged files is its own business.
- A provider that offers an attachments capability also offers `files`.

For a model these three operations are not tools: it cannot hold bytes. The assistant's own code uploads the file
the user gave it, with Kora's typed client rather than the JSON gate, and hands the model a `fileId`, as it already
keeps `media.artwork` away from the model.

## 10. `notes.recording`: commanding and observing a voice recording (designed, not in 1.3.0)

**This capability is not part of Kora 1.3.0.** The provider app does record, but its recorder does not fit the
design below, and a `1.0` frozen before the design fits it could only be corrected with a new major. Commanding the
provider's recorder is a later, additive capability, and its design must be redone to fit the provider app's
recorder, which the provider app's review of the contract (2026-11-12) describes:

- a recording starts in a note that already exists and is not deleted, so `start` takes an existing `noteId` and
  never makes a note;
- the state is the note, the instant it started and whether it is paused (`noteId`, `startedAt`, `paused`);
- there is a state the design lacks: stopped but not yet attached to the note;
- a microphone failure is a rule error of the provider, so `invalid_payload`, not `provider_unavailable`.

**A voice note from the assistant in 1.3.0** (owner's decision, 2026-11-12): the assistant records with its own
microphone, stages the audio with `files.upload` and attaches it with `notes.attachments.add`. The provider app
files an `audio/*` attachment as audio. Nothing in the contract changes for it.

What follows is the earlier design, kept as the starting point of that later capability; the points above override
it where they differ.

The provider records; the caller commands. A recording becomes an audio attachment of a note, so its result is a
`noteId` and an `attachmentId`, which `notes.attachments.open` and `files.download` turn into bytes.

Enum `RecordingPhase`: `idle`, `recording`, `paused`, `saved`, `cancelled`, `failed`.

| Operation | Scope | Input | Output |
|---|---|---|---|
| `start` | control | `noteId?`, `folderId?`, `title` = `""` | `RecordingState` |
| `set_paused` | control | `paused` | `RecordingState` |
| `stop` | control | none | `RecordingState` |
| `cancel` | control | none | `RecordingState` |
| `get_state` | read | none | `RecordingState` |

Event `state_changed` (scope read): the `RecordingState` after every change of phase.

- `RecordingState`: `phase`, `recordingId?`, `noteId?`, `attachmentId?`, `title`, `startedAtEpochMs?`, `elapsedMs`,
  `updatedAtEpochMs`, `sizeBytes`. `elapsedMs` was exact at `updatedAtEpochMs`; while the phase is `recording` a
  consumer counts on from there, as with `PlaybackState`.
- `start` records into the note `noteId`, or into a new note (titled `title`, in `folderId` or the default folder).
- `stop` saves: **the result** is the state with phase `saved`, with `noteId`, `attachmentId`, the final `elapsedMs`
  and `sizeBytes`. `cancel` ends the recording and keeps nothing: phase `cancelled`.
- `get_state` answers the recording in progress, or the last one that ended, or `idle`.
- **A model learns the state by asking.** Events are never tools, so the assistant's model polls `get_state`; the
  event is for code, such as the assistant's own screen.
- What the contract fixes is the meaning of the phases and of each operation. Which operation is possible in
  which phase, and how many recordings can run, is the provider's: an operation it cannot apply now is
  `invalid_payload`, and the caller reads `get_state`.
- The microphone not being available (no permission, held by a call, refused by the system) is
  `provider_unavailable`. A recording that breaks off goes to `failed`, with whatever was saved in `noteId` and
  `attachmentId`.
- The control scope does not end in `.read`, so the assistant asks the user before it turns the microphone on.

To verify on the provider side, not a Kora matter: Android restricts starting microphone use while an app is in the
background, so `start` may have to answer `provider_unavailable` when the app is not on screen.

## 11. How the number of operations stays small, and where it does not

The provider app's three entry classes have about 60 functions today, before attachments in two sections,
recording and the later features. Mirroring them would give well over 60 tools, growing with every field. Here the
three sections have 36, and the number does not grow when a field is added:

- **One patch operation per kind of item** (`update_reminder`, `update_event`, `update_note`, and the same for lists,
  calendars and folders) reaches every field and also moves and pins. A new field is a new optional parameter in a
  minor version, never a new operation.
- **One listing per section**, whose filters combine, replaces a listing per smart list, list, tag, folder, the
  subtasks, the search and Recently Deleted.
- **One overview per section** gives the containers, their counts, the tags and the settings.
- **Note content is one field**, Markdown, instead of a structure.
- **Files are uploaded once, by code, as a stream**, so attaching is a call with two ids.

**Operations are split by what the user is asked to confirm, not by field.** The assistant's confirmation shows the
operation, so one operation is one kind of consequence:

| The user confirms | Operations | Effect |
|---|---|---|
| an edit, which can be edited back | `update_*`, `rename_tag` | `WRITE` |
| something new | `create_*`, `add` | `WRITE` |
| a completion, a reopening, a postponed alert | `complete_reminder`, `reopen_reminder`, `snooze_reminder` | `WRITE` |
| a move to Recently Deleted, and a restore | `delete_note`, `restore_note` | `WRITE` |
| a loss | the other `delete_*`, `delete_note_permanently`, `remove` | `DESTRUCTIVE` |

That is why completing left `update_reminder`, why `rename_tag` and `delete_tag` stay two operations, and why
deleting a note for good left `delete_note`. The effect column is what each operation declares (section 4).

Of the 45 operations a model needs 40: the three of `files` and the two `open` are for the assistant's code.

## 12. Not in 1.0, and how each arrives

The provider app has not designed these yet, and a field a provider cannot honour would be silently ignored. Each
arrives in a minor version, additively, in the form named here.

| Missing | Arrives as |
|---|---|
| Searching reminders, a range of due dates, several selectors at once | optional `query`, `dueFrom`, `dueTo` in `list_reminders`, and the "one selector" check relaxed |
| Searching events, with or without a range of dates | an optional `query` in `list_events`, with `from` and `to` then optional |
| Listing notes in an order other than the setting | an optional `sort` in `list_notes` |
| Commanding the provider's recorder | the capability `notes.recording`, redesigned to fit the provider app's recorder (section 10); a voice note from the assistant is an attachment in 1.3.0 |
| Attachments of events | the capability `calendar.attachments` (section 8), once the provider has a store for them |
| Reordering lists | an optional `position` in `update_list` |
| Pinned lists, glyphs, groups of lists, sections | optional fields of the list and the reminder; `create_group` and its siblings if groups are items |
| Turning a reminder into a subtask of another | an optional `parentId` in `update_reminder` |
| Clearing completed reminders older than an age | a new operation `clear_completed` |
| The settings of reminders (default list, daily notification, alert time of date-only reminders) | a new operation `update_settings`, and `settings` in the overview, as in the other two sections |
| Completed per day and week | a filter `completedFrom`, `completedTo` in `list_reminders`; `completedAtEpochMs` is already in the summary |
| Custom smart lists, templates, location, durations, comments, activity | optional fields, or operations of their own where they are items |
| Several reminders changed at once | several calls today; an operation taking a list of ids if it proves needed |
| Attendees, invitations, the colour and the zone of one event, templates, birthdays, subscriptions, `.ics` | optional fields of the event; operations of their own for invitations and subscriptions |
| Free-slot search | a new read operation |
| The other calendar settings | optional fields of `update_settings` |
| Sub-notes and backlinks | an optional `parentNoteId` in the note, `linkedFrom` in `NoteDetail` |
| A reminder set on a note | an optional `reminderId` in `NoteDetail`, an id of the provider's `reminders`; ids are the only link between capabilities |
| Locking, smart folders, templates, history, a colour and an icon | optional fields; operations of their own for history |
| Databases | a capability of its own, `notes.databases`. A property value is one flat record, `name`, `type` (an enum) and one optional field per type (`text`, `number`, `date`, `checked`, `choices`), so no polymorphic type is needed |
| The meeting mode | a capability of its own when it is designed. A transcript and a summary are note content; questions about earlier meetings are `list_notes` with a query |
| New kinds of block in a note | nothing in Kora: they are Markdown the provider reads back (section 7) |
| Change events for the three sections | new events; nothing consumes them today |
| A patch of part of a note | not planned: `append` and reading in parts cover the cases met so far, and a model addressing lines by number is fragile |

**Lockstep.** The assistant always calls at the newest minor its Kora knows, and a provider on an older minor is
refused (`docs/compatibility.md`, rule 1). When a capability gains a minor, the provider app ships it before, or
together with, the assistant pinning that Kora release; otherwise that capability's tools disappear until it does.

## 13. Decisions and open points

Decided:

- **D1. How files cross.** The owner chose the Binder stream, now (2026-11-10, "Flujo Binder"), over chunks through
  the vocabulary. Section 9 describes it and what it costs.

First round of remarks from the provider app's session (2026-11-10), and what became of each:

| Remark | Outcome |
|---|---|
| Completing must name the occurrence and survive a retry | `complete_reminder` with `dueDate` and `dueTime`, idempotent (section 5) |
| Snoozing was unreachable | `snooze_reminder` |
| An update that empties a note deleted it for good | rejected: a change never deletes (sections 4 and 7) |
| Results between 8 000 characters and 256 KiB were cut | answers fit 8 000 characters with default arguments (section 4); a note is read in parts |
| Spans a rule does not accept; attachments after `span=this` | `EventDetail.spans`; attachments named by `occurrenceId` and carried to the new event (sections 6 and 8) |
| Attachments in the three sections from the first release | three capabilities of one shape, all in 1.0 |
| The reminder's repetition is not RFC-exact; the series start was hidden | eight flat fields in the shape of the domain's rule instead of a string (second round); `seriesStartDate` is exposed (section 5) |
| Markdown: blank lines, two bullets, the title, inline code, what a round trip loses | section 7: line-based and said so, one list item, the title is the first line with text, `` `code` ``, the round-trip guarantee |
| `start` changed format with `allDay`; clock changes; emptying the date of a repeating reminder | one format; the rule of section 4; emptying the date empties what depends on it |
| `span` default; the type of a calendar colour | `this`, on purpose; `#RRGGBB` |
| Missing and not deferred | section 12 names how each arrives |
| No cancel for a recording; the model never sees the event | `cancel`; the model polls `get_state` (section 10, for the later release) |
| Fold tag and recording operations; `update_reminder` did too much | split by what the user confirms (section 11) |
| Logic that belongs to the app | the contract checks the wire format only and names no defaults; the recorder's phases keep their meaning but not their rules (sections 4 and 10) |
| The cost of chunks | no longer applies: the owner chose the stream |

Not taken from that round, with the reason:

- **`rename_tag` and `delete_tag` stay two operations.** Folding a deletion into a rename is the hidden loss the
  same round objects to for notes.
- **File handles keep three guarantees in the contract** (used by one `add`, valid at least 10 minutes after their
  last use, bound to their caller). A caller cannot work without relying on them, and the third is a security
  property. Everything else about staging is the provider's.

Second round (2026-11-10): the note checked against the three entry classes at `461d834` of the provider app.

Changed in the vocabulary, because the domain has no way to do it and the provider must not re-implement a rule:

- `list_reminders` lost `query`, `dueFrom`, `dueTo` and the combination of selectors; `list_events` lost `query`;
  `list_notes` takes one selector and lost `sort`; `EventDetail` lost `timeZone`; `create_note` lost `pinned`.
  Section 12 says how each comes back; `timeZone` and `pinned` came back in the third round, where the provider app
  asked for them.
- The colour of a new list and of a new calendar is required: the domain asks for one and the contract names no
  default.
- The reminder's repetition is eight flat fields, the domain's own rule (section 5); nine since the third round.
- `notes.recording` is not in this release (section 10). `notes.attachments` and `calendar.attachments` were kept
  in it then, with the shape the reminders domain already has; the third round took `calendar.attachments` out.

What does not fit yet, for the provider app to settle in its entry classes, not in the provider module:

| Vocabulary | The entry class today | Needed |
|---|---|---|
| A change is whole or nothing | one function per field: `renameList` and `recolorList`; `edit` and `move`; `renameCalendar`, `recolorCalendar`, `setCalendarVisible`; `save`, `moveNote`, `setPinned`; `renameFolder`, `moveFolder`; the settings | one function per item that takes the whole change, or a way to run several as one |
| `create_reminder` without `listId` | `add` requires a list, and nothing says which one is the default | a function that names the default list |
| `create_event` answers the event with its `occurrenceId` | `add` returns an `EventId`; the instant an occurrence begins is a rule (`instantOf`) | `add` returning an `OccurrenceRef`, as `edit` does |
| `create_event` without `end` | the length is inside `newEvent(day)` | usable as it is: the length of the draft `newEvent` gives |
| `markdown` | paragraphs and spans; `toShareText` is plain text | a Markdown reader and writer in the notes domain, with the round-trip guarantee of section 7 |
| `get_reminder` with `subtaskCount`, and `parentId` as a selector | subtasks come with the items of a user list | usable as it is, by reading the list of the reminder |
| `notes.attachments` | no attachments in the notes domain | the three operations the reminders domain has (`calendar.attachments` left 1.3.0 in the third round) |

Not a matter for the vocabulary: a repetition on a day the month lacks falls on the last day in reminders and is
skipped in the calendar. The contract no longer says anything about it, since the reminder's rule is not an
`RRULE`; which behaviour the reminders should have is the provider app's question for the owner.

Not exposed on purpose: `ensureDefaultList`, `ensureCalendar`, `ensureDefaultFolder`, `refreshAccess` and
`purgeExpired` are what the app does for itself when it starts, not something a screen does.

For the assistant's session (information, nothing to decide here):

- The three `files` operations and the three `open` are not tools; they belong in its excluded set, with its own
  "attach this file" flow on top, which calls `files.upload` with Kora's typed client (the JSON gate carries no
  stream).
- New parameter names need entries in its help table (the `repeat*` fields, `rrule`, `markdown`, `dueDate`,
  `span`, `occurrenceId`...), and the description of `markdown` should say that it is line-based.
- The model sees only the error code, so "no such item" and "rule broken" look alike. A separate `not_found` code
  would be an additive change to the envelope; it is not part of this design.

Third round (2026-11-12): the provider app reviewed the contract at `60b5112` against its domain at `57782d8`; the
review is stored verbatim in `odd/reviews/oharrak-contract-review-2026-11-12.md`. The owner decided three points
the same day, and every item the review marked "now" was applied:

| Point | Outcome |
|---|---|
| The assistant's proposal of an explicit effect per operation | owner: added. `OperationSpec.effect`, declared by every built-in operation, published to MCP as annotations (section 4) |
| `calendar.attachments` cannot be served: the phone's calendar has no attachment store | owner: out of 1.3.0, designed for a later additive release (section 8); `EventDetail` lost `attachments` |
| `delete_note` with `permanent` put a recoverable and an irreversible consequence in one operation | owner: split. `delete_note` moves to Recently Deleted, `delete_note_permanently` deletes for good (section 7) |
| Reminders: a `listId` equal to the current list; `includeCompleted` in smart lists; subtasks and order in listings; emptying `dueDate`; `#word` tags in the title | said in section 5; emptying `dueDate` already cleared what depends on it |
| Reminders: repeating from the completion was hidden and lost on a change | `repeatFromCompletion` in the repetition group (section 5) |
| Reminders: four wire checks | a time without a date in a create, a `repeat*` field beside `repeatUnit` = `none`, a list and a parent in a create, a kind of day without its ordinal (sections 4 and 5) |
| Calendar: default alerts; an end in a change; the rule as stored; ids that change; `rrule` with `span` = `this`; the zone of a rule; `delete_calendar` and `url` in the descriptions | `alertMinutes` nullable; `end` = `""` rejected and a new start keeps the length; said in section 6; rejected; `EventDetail.timeZone`; said in the descriptions |
| Notes: indentation; a change in Recently Deleted; blank Markdown; `append` = `""`; the order of listings; tags and blank queries; a line longer than a part; an unbounded attachment list; `#tag` and `pinned` at creation | four spaces or a tab; rejected; "that the provider would not keep"; adds nothing; the provider's order; all tags, exact lower-case names, `isNotBlank`; cut inside, in UTF-16 units; default part 4 000; a row in the table and `pinned` in `create_note` (section 7) |
| Files: a failed or timed-out `add`; handles of a file that is gone; `download` worded for `open` only | a failed `add` leaves the staged file usable, after a timeout the caller reads the item; valid "while the file exists"; any handle the provider gave (sections 8 and 9) |
| Recording exists in the provider app, unlike what section 10 said | section 10 corrected; a voice note from the assistant in 1.3.0 is its own recording, attached (owner, 2026-11-12) |

The rest of the review needs no contract change, or is missing functionality that arrives additively later
(section 12).

## 14. Implementation (step 2), after the remarks

1. Stream transport, the high-risk unit (a published transport, caller identity and descriptors): `StreamDirection`
   in the contract; the stream API and `StreamingPeerTransport` in `kora-core`, tested with the in-memory
   transport; the two Binder methods, protocol version 2 and the minimum-version check in `kora-android`, tested on
   the JVM with fakes; stream operations left out of the MCP tools. It gets an independent verification, and its
   on-device check is listed as pending until a device or emulator is available.
2. Contract: packages `reminders`, `calendar`, `notes`, `files` in `kora-contract`; the six capabilities in
   `Capabilities.registry`; request validation with tests.
3. Samples in `WireSamples` for every input, output and event; fixtures recorded with
   `-Pkora.recordFixtures=true`; the forward-tolerance and coverage tests pass.
4. Docs: `docs/compatibility.md` (streams, the Binder protocol version), `kora-android/README.md`,
   `docs/app-handoff.md` (the provider app and the assistant), `kora-mcp/README.md`.
5. Version `1.3.0` in `build.gradle.kts`. No tag, no push, no publication without the owner's go-ahead.
6. The third round (section 13): the effect of every operation and its MCP annotations, `calendar.attachments`
   out, `delete_note_permanently`, the review's "now" items, the fixtures of the changed types recorded again (the
   organizer is not released, so its fixtures may still change), the docs and the hand-offs in step.
