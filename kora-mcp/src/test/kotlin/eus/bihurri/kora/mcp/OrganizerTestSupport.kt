package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.calendar.CalendarCapability
import eus.bihurri.kora.contract.calendar.CalendarOverview
import eus.bihurri.kora.contract.calendar.CalendarSummary
import eus.bihurri.kora.contract.calendar.EventAvailability
import eus.bihurri.kora.contract.calendar.EventDetail
import eus.bihurri.kora.contract.calendar.EventOccurrence
import eus.bihurri.kora.contract.calendar.EventSpan
import eus.bihurri.kora.contract.files.AttachmentInfo
import eus.bihurri.kora.contract.files.AttachmentsCapability
import eus.bihurri.kora.contract.files.FileHandle
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.notes.FolderSummary
import eus.bihurri.kora.contract.notes.NoteAttachments
import eus.bihurri.kora.contract.notes.NoteDetail
import eus.bihurri.kora.contract.notes.NoteSort
import eus.bihurri.kora.contract.notes.NoteSummary
import eus.bihurri.kora.contract.notes.NotesCapability
import eus.bihurri.kora.contract.notes.NotesOverview
import eus.bihurri.kora.contract.notes.NotesSettings
import eus.bihurri.kora.contract.notes.NotesView
import eus.bihurri.kora.contract.notes.TagSummary
import eus.bihurri.kora.contract.reminders.EarlyAlertUnit
import eus.bihurri.kora.contract.reminders.ListColor
import eus.bihurri.kora.contract.reminders.ReminderAttachments
import eus.bihurri.kora.contract.reminders.ReminderDetail
import eus.bihurri.kora.contract.reminders.ReminderListSummary
import eus.bihurri.kora.contract.reminders.ReminderPriority
import eus.bihurri.kora.contract.reminders.ReminderSummary
import eus.bihurri.kora.contract.reminders.RemindersCapability
import eus.bihurri.kora.contract.reminders.RemindersOverview
import eus.bihurri.kora.contract.reminders.RepeatDayKind
import eus.bihurri.kora.contract.reminders.RepeatOrdinal
import eus.bihurri.kora.contract.reminders.RepeatUnit
import eus.bihurri.kora.contract.reminders.SmartListCounts
import eus.bihurri.kora.contract.reminders.TagName
import eus.bihurri.kora.contract.reminders.Weekday
import eus.bihurri.kora.core.KoraNode

/*
 * Fixed answers and valid arguments for the reminders, calendar and notes capabilities and their attachments, so
 * the tests that walk every built-in tool (`provideEverything`) reach them too.
 */

private val attachmentInfo = AttachmentInfo("att-1", "receipt.pdf", "application/pdf", 48_213)

private val reminderList = ReminderListSummary("list-1", "Home", ListColor.BLUE, openCount = 1)

private val reminderSummary = ReminderSummary(
    id = "rem-1", listId = "list-1", title = "Pay the rent", dueDate = "2026-11-10", dueTime = "17:30", repeats = true,
    flagged = true, priority = ReminderPriority.HIGH, completed = false, tags = listOf("home"), subtaskCount = 0,
    attachmentCount = 1,
)

private val reminderDetail = ReminderDetail(
    id = "rem-1", listId = "list-1", title = "Pay the rent", dueDate = "2026-11-10", dueTime = "17:30", repeats = true,
    flagged = true, priority = ReminderPriority.HIGH, completed = false, tags = listOf("home"), subtaskCount = 0,
    attachmentCount = 1, notes = "", repeatUnit = RepeatUnit.WEEK, repeatInterval = 1,
    repeatWeekdays = listOf(Weekday.MONDAY), repeatMonthDays = emptyList(), repeatOrdinal = RepeatOrdinal.NONE,
    repeatDayKind = RepeatDayKind.DAY, repeatMonths = emptyList(), repeatUntil = "", repeatFromCompletion = false,
    seriesStartDate = "2026-11-10",
    url = "", earlyAlertAmount = 0, earlyAlertUnit = EarlyAlertUnit.MINUTES, createdAtEpochMs = 1,
    attachments = listOf(attachmentInfo),
)

private val calendarSummary = CalendarSummary(
    id = "cal-1", name = "Personal", accountName = "Local", accountType = "local", color = "#1A73E8", visible = true,
    writable = true, local = true,
)

private val calendarOverview = CalendarOverview(
    accessGranted = true, calendars = listOf(calendarSummary), calendarCount = 1, defaultCalendarId = "cal-1",
    showReminders = true,
)

private val eventOccurrence = EventOccurrence(
    occurrenceId = "occ-1", eventId = "evt-1", calendarId = "cal-1", title = "Dentist", location = "", allDay = false,
    start = "2026-11-10T17:30", end = "2026-11-10T18:15", repeats = true,
)

private val eventDetail = EventDetail(
    occurrenceId = "occ-1", eventId = "evt-1", calendarId = "cal-1", title = "Dentist", location = "", allDay = false,
    start = "2026-11-10T17:30", end = "2026-11-10T18:15", timeZone = "Europe/Madrid", repeats = true,
    rrule = "FREQ=WEEKLY", spans = listOf(EventSpan.THIS, EventSpan.ALL), alertMinutes = listOf(15),
    availability = EventAvailability.BUSY, url = "", notes = "", editable = true,
)

private val notesSettings = NotesSettings(NoteSort.DATE_EDITED, NotesView.LIST, moveTickedItems = true)

private val folderSummary = FolderSummary(id = "fold-1", name = "Notes", depth = 0, noteCount = 1, isDefault = true)

private val noteSummary = NoteSummary(
    id = "note-1", folderId = "fold-1", title = "Shopping", preview = "eggs", pinned = false, tags = listOf("home"),
    createdAtEpochMs = 1, editedAtEpochMs = 2, attachmentCount = 1,
)

private val noteDetail = NoteDetail(
    id = "note-1", folderId = "fold-1", title = "Shopping", pinned = false, tags = listOf("home"), createdAtEpochMs = 1,
    editedAtEpochMs = 2, attachmentCount = 1, markdown = "Shopping\n- [ ] eggs", offset = 0, totalChars = 19,
    attachments = listOf(attachmentInfo),
)

/**
 * Gives [this] node the reminders, calendar and notes capabilities and the attachments of reminders and notes, with
 * fixed answers.
 */
fun KoraNode.provideOrganizer() {
    provide(RemindersCapability) {
        on(RemindersCapability.GetOverview) {
            RemindersOverview(SmartListCounts(1, 1, 1, 1, 0), listOf(reminderList), 1, listOf("home"), 1, "list-1")
        }
        on(RemindersCapability.ListReminders) { Page(listOf(reminderSummary), 1, it.offset) }
        on(RemindersCapability.GetReminder) { reminderDetail }
        on(RemindersCapability.CreateReminder) { reminderDetail.copy(title = it.title) }
        on(RemindersCapability.UpdateReminder) { reminderDetail }
        on(RemindersCapability.CompleteReminder) { reminderDetail }
        on(RemindersCapability.ReopenReminder) { reminderDetail }
        on(RemindersCapability.SnoozeReminder) { reminderDetail.copy(snoozedUntilEpochMs = 3) }
        on(RemindersCapability.DeleteReminder) { }
        on(RemindersCapability.CreateList) { ReminderListSummary("list-2", it.name, it.color, openCount = 0) }
        on(RemindersCapability.UpdateList) { reminderList }
        on(RemindersCapability.DeleteList) { }
        on(RemindersCapability.RenameTag) { TagName(it.newName) }
        on(RemindersCapability.DeleteTag) { }
    }
    provide(CalendarCapability) {
        on(CalendarCapability.GetOverview) { calendarOverview }
        on(CalendarCapability.ListEvents) { Page(listOf(eventOccurrence), 1, it.offset) }
        on(CalendarCapability.GetEvent) { eventDetail }
        on(CalendarCapability.CreateEvent) { eventDetail.copy(title = it.title) }
        on(CalendarCapability.UpdateEvent) { eventDetail }
        on(CalendarCapability.DeleteEvent) { }
        on(CalendarCapability.CreateCalendar) { calendarSummary.copy(name = it.name, color = it.color) }
        on(CalendarCapability.UpdateCalendar) { calendarSummary }
        on(CalendarCapability.DeleteCalendar) { }
        on(CalendarCapability.UpdateSettings) { calendarOverview }
    }
    provide(NotesCapability) {
        on(NotesCapability.GetOverview) {
            NotesOverview(1, listOf(folderSummary), 1, listOf(TagSummary("home", 1)), 1, 0, notesSettings)
        }
        on(NotesCapability.ListNotes) { Page(listOf(noteSummary), 1, it.offset) }
        on(NotesCapability.GetNote) { noteDetail }
        on(NotesCapability.CreateNote) { noteDetail }
        on(NotesCapability.UpdateNote) { noteDetail }
        on(NotesCapability.DeleteNote) { }
        on(NotesCapability.DeleteNotePermanently) { }
        on(NotesCapability.RestoreNote) { noteDetail }
        on(NotesCapability.CreateFolder) { folderSummary.copy(id = "fold-2", name = it.name) }
        on(NotesCapability.UpdateFolder) { folderSummary }
        on(NotesCapability.DeleteFolder) { }
        on(NotesCapability.UpdateSettings) { notesSettings }
    }
    for (attachments in listOf<AttachmentsCapability>(ReminderAttachments, NoteAttachments)) {
        provide(attachments) {
            on(attachments.Add) { attachmentInfo }
            on(attachments.Remove) { }
            on(attachments.Open) { FileHandle("file-9", attachmentInfo.name, attachmentInfo.mimeType, attachmentInfo.sizeBytes) }
        }
    }
}

/** Valid `arguments` for every tool of the capabilities [provideOrganizer] provides, by tool name. */
val organizerArguments: Map<String, String> = mapOf(
    "reminders__get_overview" to "{}",
    "reminders__list_reminders" to """{"smartList":"today","limit":5}""",
    "reminders__get_reminder" to """{"reminderId":"rem-1"}""",
    "reminders__create_reminder" to
        """{"title":"Pay the rent","dueDate":"2026-11-10","dueTime":"17:30","repeatUnit":"week","repeatWeekdays":["monday"],"priority":"high","tags":["home"]}""",
    "reminders__update_reminder" to """{"reminderId":"rem-1","title":"Pay","dueTime":"","repeatUnit":"none","tags":[],"flagged":false}""",
    "reminders__complete_reminder" to """{"reminderId":"rem-1","dueDate":"2026-11-10","dueTime":"17:30"}""",
    "reminders__reopen_reminder" to """{"reminderId":"rem-1"}""",
    "reminders__snooze_reminder" to """{"reminderId":"rem-1","snooze":"in_one_hour","dueDate":"2026-11-10"}""",
    "reminders__delete_reminder" to """{"reminderId":"rem-1"}""",
    "reminders__create_list" to """{"name":"Work","color":"orange"}""",
    "reminders__update_list" to """{"listId":"list-1","color":"green"}""",
    "reminders__delete_list" to """{"listId":"list-1"}""",
    "reminders__rename_tag" to """{"tag":"home","newName":"house"}""",
    "reminders__delete_tag" to """{"tag":"home"}""",
    "calendar__get_overview" to """{"limit":10,"offset":0}""",
    "calendar__list_events" to """{"from":"2026-11-10","to":"2026-11-16","calendarIds":["cal-1"]}""",
    "calendar__get_event" to """{"occurrenceId":"occ-1"}""",
    "calendar__create_event" to """{"title":"Dentist","start":"2026-11-10T17:30","alertMinutes":[15,-540],"availability":"free"}""",
    "calendar__update_event" to """{"occurrenceId":"occ-1","span":"all","allDay":true,"rrule":"","alertMinutes":[]}""",
    "calendar__delete_event" to """{"occurrenceId":"occ-1"}""",
    "calendar__create_calendar" to """{"name":"Work","color":"#0B8043"}""",
    "calendar__update_calendar" to """{"calendarId":"cal-1","visible":false}""",
    "calendar__delete_calendar" to """{"calendarId":"cal-1"}""",
    "calendar__update_settings" to """{"showReminders":false}""",
    "notes__get_overview" to "{}",
    "notes__list_notes" to """{"query":"eggs"}""",
    "notes__get_note" to """{"noteId":"note-1","maxChars":1000}""",
    "notes__create_note" to """{"markdown":"Shopping\n- [ ] eggs"}""",
    "notes__update_note" to """{"noteId":"note-1","append":"- [ ] milk","pinned":true}""",
    "notes__delete_note" to """{"noteId":"note-1"}""",
    "notes__delete_note_permanently" to """{"noteId":"note-1"}""",
    "notes__restore_note" to """{"noteId":"note-1"}""",
    "notes__create_folder" to """{"name":"Recipes","parentId":"fold-1"}""",
    "notes__update_folder" to """{"folderId":"fold-1","parentId":""}""",
    "notes__delete_folder" to """{"folderId":"fold-1"}""",
    "notes__update_settings" to """{"sort":"title","view":"gallery"}""",
    "reminders_attachments__add" to """{"itemId":"rem-1","fileId":"file-1","name":"receipt.pdf"}""",
    "reminders_attachments__remove" to """{"itemId":"rem-1","attachmentId":"att-1"}""",
    "reminders_attachments__open" to """{"itemId":"rem-1","attachmentId":"att-1"}""",
    "notes_attachments__add" to """{"itemId":"note-1","fileId":"file-1"}""",
    "notes_attachments__remove" to """{"itemId":"note-1","attachmentId":"att-1"}""",
    "notes_attachments__open" to """{"itemId":"note-1","attachmentId":"att-1"}""",
)
