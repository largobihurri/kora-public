package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.ai.AiCapability
import eus.bihurri.kora.contract.ai.AiEngine
import eus.bihurri.kora.contract.ai.AiJob
import eus.bihurri.kora.contract.ai.AiTask
import eus.bihurri.kora.contract.ai.AskDocument
import eus.bihurri.kora.contract.ai.AskRequest
import eus.bihurri.kora.contract.ai.EngineKind
import eus.bihurri.kora.contract.ai.EngineList
import eus.bihurri.kora.contract.ai.FailureReason
import eus.bihurri.kora.contract.ai.JobFailure
import eus.bihurri.kora.contract.ai.JobIdRequest
import eus.bihurri.kora.contract.ai.JobState
import eus.bihurri.kora.contract.ai.MeetingSummary
import eus.bihurri.kora.contract.ai.SummarizeMeetingRequest
import eus.bihurri.kora.contract.ai.TextInput
import eus.bihurri.kora.contract.ai.TranscribeRequest
import eus.bihurri.kora.contract.ai.TranscriptInfo
import eus.bihurri.kora.contract.ai.TranscriptPage
import eus.bihurri.kora.contract.ai.TranscriptPassage
import eus.bihurri.kora.contract.ai.TranscriptPassagesRequest
import eus.bihurri.kora.contract.calendar.CalendarCapability
import eus.bihurri.kora.contract.calendar.CalendarIdRequest
import eus.bihurri.kora.contract.calendar.CalendarOverview
import eus.bihurri.kora.contract.calendar.CalendarOverviewRequest
import eus.bihurri.kora.contract.calendar.CalendarSummary
import eus.bihurri.kora.contract.calendar.CreateCalendarRequest
import eus.bihurri.kora.contract.calendar.CreateEventRequest
import eus.bihurri.kora.contract.calendar.DeleteEventRequest
import eus.bihurri.kora.contract.calendar.EventAvailability
import eus.bihurri.kora.contract.calendar.EventDetail
import eus.bihurri.kora.contract.calendar.EventOccurrence
import eus.bihurri.kora.contract.calendar.EventSpan
import eus.bihurri.kora.contract.calendar.ListEventsRequest
import eus.bihurri.kora.contract.calendar.OccurrenceIdRequest
import eus.bihurri.kora.contract.calendar.UpdateCalendarRequest
import eus.bihurri.kora.contract.calendar.UpdateCalendarSettingsRequest
import eus.bihurri.kora.contract.calendar.UpdateEventRequest
import eus.bihurri.kora.contract.files.AddAttachmentRequest
import eus.bihurri.kora.contract.files.AttachmentInfo
import eus.bihurri.kora.contract.files.AttachmentRequest
import eus.bihurri.kora.contract.files.AttachmentsCapability
import eus.bihurri.kora.contract.files.FileHandle
import eus.bihurri.kora.contract.files.FileIdRequest
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.files.UploadFileRequest
import eus.bihurri.kora.contract.fitness.ActiveSession
import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.fitness.SessionPhase
import eus.bihurri.kora.contract.fixtures.FixtureCase
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.location.LocationFixBatch
import eus.bihurri.kora.contract.location.StreamConfig
import eus.bihurri.kora.contract.location.StreamRequest
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.contract.media.SetVolumeRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.contract.media.VolumeState
import eus.bihurri.kora.contract.media.WriteOrder
import eus.bihurri.kora.contract.notes.CreateFolderRequest
import eus.bihurri.kora.contract.notes.CreateNoteRequest
import eus.bihurri.kora.contract.notes.FolderIdRequest
import eus.bihurri.kora.contract.notes.FolderSummary
import eus.bihurri.kora.contract.notes.GetNoteRequest
import eus.bihurri.kora.contract.notes.ListNotesRequest
import eus.bihurri.kora.contract.notes.NoteAttachments
import eus.bihurri.kora.contract.notes.NoteDetail
import eus.bihurri.kora.contract.notes.NoteIdRequest
import eus.bihurri.kora.contract.notes.NoteSort
import eus.bihurri.kora.contract.notes.NoteSummary
import eus.bihurri.kora.contract.notes.NotesCapability
import eus.bihurri.kora.contract.notes.NotesOverview
import eus.bihurri.kora.contract.notes.NotesOverviewRequest
import eus.bihurri.kora.contract.notes.NotesSettings
import eus.bihurri.kora.contract.notes.NotesView
import eus.bihurri.kora.contract.notes.TagSummary
import eus.bihurri.kora.contract.notes.UpdateFolderRequest
import eus.bihurri.kora.contract.notes.UpdateNoteRequest
import eus.bihurri.kora.contract.notes.UpdateNotesSettingsRequest
import eus.bihurri.kora.contract.reminders.CompleteReminderRequest
import eus.bihurri.kora.contract.reminders.CreateListRequest
import eus.bihurri.kora.contract.reminders.CreateReminderRequest
import eus.bihurri.kora.contract.reminders.EarlyAlertUnit
import eus.bihurri.kora.contract.reminders.ListColor
import eus.bihurri.kora.contract.reminders.ListRemindersRequest
import eus.bihurri.kora.contract.reminders.ReminderAttachments
import eus.bihurri.kora.contract.reminders.ReminderDetail
import eus.bihurri.kora.contract.reminders.ReminderIdRequest
import eus.bihurri.kora.contract.reminders.ReminderListIdRequest
import eus.bihurri.kora.contract.reminders.ReminderListSummary
import eus.bihurri.kora.contract.reminders.ReminderPriority
import eus.bihurri.kora.contract.reminders.ReminderSummary
import eus.bihurri.kora.contract.reminders.RemindersCapability
import eus.bihurri.kora.contract.reminders.RemindersOverview
import eus.bihurri.kora.contract.reminders.RemindersOverviewRequest
import eus.bihurri.kora.contract.reminders.RenameTagRequest
import eus.bihurri.kora.contract.reminders.RepeatDayKind
import eus.bihurri.kora.contract.reminders.RepeatOrdinal
import eus.bihurri.kora.contract.reminders.RepeatUnit
import eus.bihurri.kora.contract.reminders.SmartList
import eus.bihurri.kora.contract.reminders.SmartListCounts
import eus.bihurri.kora.contract.reminders.Snooze
import eus.bihurri.kora.contract.reminders.SnoozeReminderRequest
import eus.bihurri.kora.contract.reminders.TagName
import eus.bihurri.kora.contract.reminders.TagRequest
import eus.bihurri.kora.contract.reminders.UpdateListRequest
import eus.bihurri.kora.contract.reminders.UpdateReminderRequest
import eus.bihurri.kora.contract.reminders.Weekday
import kotlinx.serialization.KSerializer

/**
 * The deterministic sample values behind the golden wire fixtures (`src/test/resources/wire`), defined in one place.
 *
 * Every operation input, operation output and event of every built-in capability has at least one sample, named
 * `<member>.input`, `<member>.output` or `<member>.event` (plus `.<variant>` for extra shapes such as an absent
 * value). The samples describe the **current** version of each capability; the stored fixtures of earlier versions
 * are the history. Changing a sample value changes what a recording writes for a new version, never an old fixture.
 */
object WireSamples {
    /**
     * Version label of the envelope types ([KoraRequest], [KoraResponse], [KoraEvent], [SubscribeRequest],
     * [KoraError], [NodeDescriptor]). Bump the minor when one of them gains a field, then record fixtures.
     */
    const val ENVELOPE_VERSION = "1.0"
    const val ENVELOPE_GROUP = "envelope"

    private const val T0 = 1_700_000_000_000L

    private val track = TrackSummary("track-1", "Title", "Artist", "Album", 215_000, "art-1")
    private val minimalTrack = TrackSummary("track-2", "Only title")
    private val playlist = PlaylistSummary("playlist-1", "Morning run", 12, "art-2")
    private val minimalPlaylist = PlaylistSummary("playlist-2", "Bare", 0)
    private val playing = PlaybackState(PlaybackStatus.PLAYING, 42_000, T0, track, shuffle = true)
    private val session = ActiveSession("session-1", "hiking", SessionPhase.RECORDING, T0, 61_000, 1234.5)
    private val fix = LocationFix(43.26, -2.93, 12.0, 4.5, T0)
    private val minimalFix = LocationFix(0.0, 0.0, timeEpochMs = 1)
    private val play = PlayRecord(track, T0, 61_000)
    private val minimalPlay = PlayRecord(minimalTrack, T0 + 1_000)

    private class Builder(private val capability: Capability) {
        val cases = mutableListOf<FixtureCase<*>>()

        private fun <T> add(name: String, variant: String?, serializer: KSerializer<T>, value: T) {
            val full = if (variant == null) name else "$name.$variant"
            cases += FixtureCase(capability.id.value, capability.version.toString(), full, serializer, value)
        }

        fun <I, O> input(spec: OperationSpec<I, O>, value: I, variant: String? = null) =
            add("${spec.name}.input", variant, spec.inputSerializer, value)

        fun <I, O> output(spec: OperationSpec<I, O>, value: O, variant: String? = null) =
            add("${spec.name}.output", variant, spec.outputSerializer, value)

        fun <E> event(spec: EventSpec<E>, value: E, variant: String? = null) =
            add("${spec.name}.event", variant, spec.serializer, value)
    }

    private fun capability(capability: Capability, define: Builder.() -> Unit): List<FixtureCase<*>> =
        Builder(capability).apply(define).cases

    private val library = capability(MediaLibrary) {
        input(MediaLibrary.ListPlaylists, PageRequest(20, 40))
        output(MediaLibrary.ListPlaylists, Page(listOf(playlist, minimalPlaylist), total = 52, offset = 40))
        output(MediaLibrary.ListPlaylists, Page(emptyList<PlaylistSummary>(), 0, 0), "empty")
        input(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("playlist-1", PageRequest(5, 10)))
        output(MediaLibrary.GetPlaylistTracks, Page(listOf(track, minimalTrack), total = 12, offset = 10))
        input(MediaLibrary.SearchTracks, SearchRequest("rock", PageRequest(10, 0)))
        output(MediaLibrary.SearchTracks, Page(listOf(track), total = 1, offset = 0))
    }

    private val playback = capability(MediaPlayback) {
        for (command in listOf(
            MediaPlayback.Play, MediaPlayback.Pause, MediaPlayback.Toggle, MediaPlayback.Next, MediaPlayback.Previous,
        )) {
            input(command, NoInput)
            output(command, Unit)
        }
        input(MediaPlayback.SeekTo, SeekRequest(90_000))
        output(MediaPlayback.SeekTo, Unit)
        input(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("playlist-1", 3, true))
        output(MediaPlayback.PlayPlaylist, Unit)
        input(MediaPlayback.PlayTracks, PlayTracksRequest(listOf("track-1", "track-2"), 1))
        output(MediaPlayback.PlayTracks, Unit)
        input(MediaPlayback.GetState, NoInput)
        output(MediaPlayback.GetState, playing)
        output(MediaPlayback.GetState, PlaybackState(PlaybackStatus.IDLE), "idle")
        event(MediaPlayback.PlaybackStateChanged, playing)
        event(MediaPlayback.NowPlayingChanged, NowPlaying(track, T0))
    }

    private val history = capability(MediaHistory) {
        input(MediaHistory.ListPlays, PlaysRequest(T0, T0 + 3_600_000, PageRequest(10, 20)))
        output(MediaHistory.ListPlays, Page(listOf(play, minimalPlay), total = 32, offset = 20))
        output(MediaHistory.ListPlays, Page(emptyList<PlayRecord>(), 0, 0), "empty")
    }

    /** The 8-byte PNG signature: small, recognisable bytes that include values above 0x7f. */
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private val artwork = capability(MediaArtwork) {
        input(MediaArtwork.GetArtwork, ArtworkRequest("art-1", 256))
        input(MediaArtwork.GetArtwork, ArtworkRequest("art-2"), "defaults")
        output(MediaArtwork.GetArtwork, ArtworkImage(pngSignature, ArtworkMimeType.PNG, 256, 192))
    }

    private val volume = capability(MediaVolume) {
        input(MediaVolume.GetVolume, NoInput)
        output(MediaVolume.GetVolume, VolumeState(7, 15))
        input(MediaVolume.SetVolume, SetVolumeRequest(level = 9, muted = false, order = WriteOrder("watch-5f0c", 42)))
        input(MediaVolume.SetVolume, SetVolumeRequest(level = 20), "level_only")
        output(MediaVolume.SetVolume, VolumeState(15, 15, muted = true))
        event(MediaVolume.VolumeChanged, VolumeState(9, 15))
    }

    private val fitness = capability(FitnessSession) {
        input(FitnessSession.GetActiveSession, NoInput)
        output(FitnessSession.GetActiveSession, session)
        output(FitnessSession.GetActiveSession, null, "none")
        event(FitnessSession.SessionStarted, session)
        event(FitnessSession.SessionEnded, session.copy(phase = SessionPhase.ENDED))
        event(FitnessSession.SessionStateChanged, session.copy(phase = SessionPhase.PAUSED))
    }

    private val location = capability(LocationCapability) {
        input(LocationCapability.GetLastFix, NoInput)
        output(LocationCapability.GetLastFix, fix)
        output(LocationCapability.GetLastFix, null, "none")
        event(LocationCapability.FixChanged, fix)
        event(LocationCapability.FixChanged, minimalFix, "minimal")
        input(LocationCapability.ConfigureStream, StreamRequest(1_000, 5_000))
        input(LocationCapability.ConfigureStream, StreamRequest(), "defaults")
        output(LocationCapability.ConfigureStream, StreamConfig(2_000, 4_000))
        event(LocationCapability.FixesBatched, LocationFixBatch(listOf(fix, minimalFix.copy(timeEpochMs = T0 + 1_000))))
    }

    // ---- Organizer: reminders, calendar, notes, the attachments of reminders and notes, files -------------------

    private val attachment = AttachmentInfo("att-1", "receipt.pdf", "application/pdf", 48_213)
    private val photo = AttachmentInfo("att-2", "photo.jpg", "image/jpeg", 5_000_000_000)

    private val reminderList = ReminderListSummary("list-1", "Home", ListColor.BLUE, 4)

    /** Every field of a reminder set: a subtask that repeats, was snoozed and has an attachment. */
    private val fullReminder = ReminderDetail(
        id = "rem-1",
        listId = "list-1",
        parentId = "rem-0",
        title = "Pay the rent",
        dueDate = "2026-11-10",
        dueTime = "17:30",
        repeats = true,
        flagged = true,
        priority = ReminderPriority.HIGH,
        completed = true,
        completedAtEpochMs = T0 + 60_000,
        tags = listOf("home", "money"),
        subtaskCount = 2,
        attachmentCount = 1,
        notes = "Transfer from the shared account.\nAsk for the receipt.",
        repeatUnit = RepeatUnit.MONTH,
        repeatInterval = 2,
        repeatWeekdays = listOf(Weekday.MONDAY, Weekday.FRIDAY),
        repeatMonthDays = listOf(1, 31),
        repeatOrdinal = RepeatOrdinal.LAST,
        repeatDayKind = RepeatDayKind.WEEKDAY,
        repeatMonths = listOf(1, 12),
        repeatUntil = "2027-12-31",
        repeatFromCompletion = true,
        seriesStartDate = "2026-01-10",
        url = "https://example.org/rent",
        earlyAlertAmount = 2,
        earlyAlertUnit = EarlyAlertUnit.DAYS,
        snoozedUntilEpochMs = T0 + 3_600_000,
        createdAtEpochMs = T0,
        attachments = listOf(attachment),
    )

    /** A reminder with nothing but a title: no date, no repetition, no attachments. */
    private val minimalReminder = ReminderDetail(
        id = "rem-2",
        listId = "list-1",
        title = "Buy milk",
        repeats = false,
        flagged = false,
        priority = ReminderPriority.NONE,
        completed = false,
        tags = emptyList(),
        subtaskCount = 0,
        attachmentCount = 0,
        notes = "",
        repeatUnit = RepeatUnit.NONE,
        repeatInterval = 1,
        repeatWeekdays = emptyList(),
        repeatMonthDays = emptyList(),
        repeatOrdinal = RepeatOrdinal.NONE,
        repeatDayKind = RepeatDayKind.DAY,
        repeatMonths = emptyList(),
        repeatUntil = "",
        repeatFromCompletion = false,
        url = "",
        earlyAlertAmount = 0,
        earlyAlertUnit = EarlyAlertUnit.MINUTES,
        createdAtEpochMs = T0,
    )

    private val fullReminderSummary = ReminderSummary(
        id = "rem-1",
        listId = "list-1",
        parentId = "rem-0",
        title = "Pay the rent",
        dueDate = "2026-11-10",
        dueTime = "17:30",
        repeats = true,
        flagged = true,
        priority = ReminderPriority.HIGH,
        completed = true,
        completedAtEpochMs = T0 + 60_000,
        tags = listOf("home", "money"),
        subtaskCount = 2,
        attachmentCount = 1,
    )

    private val minimalReminderSummary = ReminderSummary(
        id = "rem-2",
        listId = "list-1",
        title = "Buy milk",
        repeats = false,
        flagged = false,
        priority = ReminderPriority.NONE,
        completed = false,
        tags = emptyList(),
        subtaskCount = 0,
        attachmentCount = 0,
    )

    private val reminders = capability(RemindersCapability) {
        input(RemindersCapability.GetOverview, RemindersOverviewRequest(10, 20))
        input(RemindersCapability.GetOverview, RemindersOverviewRequest(), "defaults")
        output(
            RemindersCapability.GetOverview,
            RemindersOverview(
                smartCounts = SmartListCounts(today = 3, scheduled = 12, all = 40, flagged = 2, completed = 118),
                lists = listOf(reminderList, ReminderListSummary("list-2", "Work", ListColor.ORANGE, 0)),
                listCount = 22,
                tags = listOf("home", "money"),
                tagCount = 22,
                defaultListId = "list-1",
            ),
        )
        output(
            RemindersCapability.GetOverview,
            RemindersOverview(SmartListCounts(0, 0, 0, 0, 0), emptyList(), 0, emptyList(), 0),
            "empty",
        )

        input(RemindersCapability.ListReminders, ListRemindersRequest(listId = "list-1", includeCompleted = true, limit = 5, offset = 10))
        input(RemindersCapability.ListReminders, ListRemindersRequest(), "defaults")
        input(RemindersCapability.ListReminders, ListRemindersRequest(smartList = SmartList.TODAY), "smart_list")
        input(RemindersCapability.ListReminders, ListRemindersRequest(tags = listOf("home", "money")), "tags")
        input(RemindersCapability.ListReminders, ListRemindersRequest(parentId = "rem-0"), "subtasks")
        output(RemindersCapability.ListReminders, Page(listOf(fullReminderSummary, minimalReminderSummary), total = 12, offset = 10))
        output(RemindersCapability.ListReminders, Page(emptyList<ReminderSummary>(), 0, 0), "empty")

        input(RemindersCapability.GetReminder, ReminderIdRequest("rem-1"))
        output(RemindersCapability.GetReminder, fullReminder)
        output(RemindersCapability.GetReminder, minimalReminder, "minimal")

        input(
            RemindersCapability.CreateReminder,
            CreateReminderRequest(
                title = "Pay the rent",
                listId = "list-1",
                notes = "Transfer from the shared account.",
                dueDate = "2026-11-10",
                dueTime = "17:30",
                repeatUnit = RepeatUnit.MONTH,
                repeatInterval = 2,
                repeatWeekdays = listOf(Weekday.MONDAY, Weekday.FRIDAY),
                repeatMonthDays = listOf(1, 31),
                repeatOrdinal = RepeatOrdinal.LAST,
                repeatDayKind = RepeatDayKind.WEEKDAY,
                repeatMonths = listOf(1, 12),
                repeatUntil = "2027-12-31",
                repeatFromCompletion = true,
                flagged = true,
                priority = ReminderPriority.HIGH,
                url = "https://example.org/rent",
                tags = listOf("home", "money"),
                earlyAlertAmount = 2,
                earlyAlertUnit = EarlyAlertUnit.DAYS,
            ),
        )
        input(RemindersCapability.CreateReminder, CreateReminderRequest("Buy milk"), "defaults")
        input(RemindersCapability.CreateReminder, CreateReminderRequest("Buy oat milk", parentId = "rem-2"), "subtask")
        output(RemindersCapability.CreateReminder, minimalReminder)

        input(
            RemindersCapability.UpdateReminder,
            UpdateReminderRequest(
                reminderId = "rem-1",
                title = "Pay the rent and the garage",
                listId = "list-2",
                notes = "Two transfers.",
                dueDate = "2026-12-10",
                dueTime = "09:00",
                repeatUnit = RepeatUnit.WEEK,
                repeatInterval = 3,
                repeatWeekdays = listOf(Weekday.SATURDAY, Weekday.SUNDAY),
                repeatMonthDays = listOf(15),
                repeatOrdinal = RepeatOrdinal.SECOND,
                repeatDayKind = RepeatDayKind.WEEKEND_DAY,
                repeatMonths = listOf(6),
                repeatUntil = "2028-01-01",
                repeatFromCompletion = false,
                flagged = false,
                priority = ReminderPriority.LOW,
                url = "https://example.org/garage",
                tags = listOf("money"),
                earlyAlertAmount = 1,
                earlyAlertUnit = EarlyAlertUnit.WEEKS,
            ),
        )
        input(RemindersCapability.UpdateReminder, UpdateReminderRequest("rem-1"), "untouched")
        input(
            RemindersCapability.UpdateReminder,
            UpdateReminderRequest(
                "rem-1", notes = "", dueDate = "", dueTime = "", repeatUnit = RepeatUnit.NONE, url = "", tags = emptyList(), earlyAlertAmount = 0,
            ),
            "emptied",
        )
        output(RemindersCapability.UpdateReminder, fullReminder)

        input(RemindersCapability.CompleteReminder, CompleteReminderRequest("rem-1", "2026-11-10", "17:30"))
        input(RemindersCapability.CompleteReminder, CompleteReminderRequest("rem-2"), "defaults")
        output(RemindersCapability.CompleteReminder, fullReminder)
        input(RemindersCapability.ReopenReminder, ReminderIdRequest("rem-1"))
        output(RemindersCapability.ReopenReminder, minimalReminder)
        input(RemindersCapability.SnoozeReminder, SnoozeReminderRequest("rem-1", Snooze.IN_ONE_HOUR, "2026-11-10", "17:30"))
        input(RemindersCapability.SnoozeReminder, SnoozeReminderRequest("rem-1", Snooze.TOMORROW, "2026-11-10"), "no_time")
        output(RemindersCapability.SnoozeReminder, fullReminder)
        input(RemindersCapability.DeleteReminder, ReminderIdRequest("rem-1"))
        output(RemindersCapability.DeleteReminder, Unit)

        input(RemindersCapability.CreateList, CreateListRequest("Home", ListColor.BLUE))
        output(RemindersCapability.CreateList, reminderList)
        input(RemindersCapability.UpdateList, UpdateListRequest("list-1", "House", ListColor.PURPLE))
        input(RemindersCapability.UpdateList, UpdateListRequest("list-1"), "untouched")
        output(RemindersCapability.UpdateList, reminderList.copy(name = "House", color = ListColor.PURPLE))
        input(RemindersCapability.DeleteList, ReminderListIdRequest("list-1"))
        output(RemindersCapability.DeleteList, Unit)
        input(RemindersCapability.RenameTag, RenameTagRequest("home", "house"))
        output(RemindersCapability.RenameTag, TagName("house"))
        input(RemindersCapability.DeleteTag, TagRequest("home"))
        output(RemindersCapability.DeleteTag, Unit)
    }

    private val localCalendar = CalendarSummary(
        id = "cal-1", name = "Personal", accountName = "Oharrak", accountType = "local", color = "#1A73E8",
        visible = true, writable = true, local = true,
    )
    private val accountCalendar = CalendarSummary(
        id = "cal-2", name = "Holidays", accountName = "user@example.org", accountType = "com.example", color = "#0b8043",
        visible = false, writable = false, local = false,
    )
    private val calendarOverview = CalendarOverview(
        accessGranted = true,
        calendars = listOf(localCalendar, accountCalendar),
        calendarCount = 22,
        defaultCalendarId = "cal-1",
        showReminders = true,
    )

    private val occurrence = EventOccurrence(
        occurrenceId = "occ-1", eventId = "evt-1", calendarId = "cal-1", title = "Dentist", location = "Main Street 1",
        allDay = false, start = "2026-11-10T17:30", end = "2026-11-10T18:15", repeats = true,
    )
    private val allDayOccurrence = EventOccurrence(
        occurrenceId = "occ-2", eventId = "evt-2", calendarId = "cal-2", title = "Holiday", location = "",
        allDay = true, start = "2026-11-12T00:00", end = "2026-11-13T00:00", repeats = false,
    )

    /** Every field of an event set: an occurrence of a repeating event with alerts. */
    private val fullEvent = EventDetail(
        occurrenceId = "occ-1",
        eventId = "evt-1",
        calendarId = "cal-1",
        title = "Dentist",
        location = "Main Street 1",
        allDay = false,
        start = "2026-11-10T17:30",
        end = "2026-11-10T18:15",
        timeZone = "Europe/Madrid",
        repeats = true,
        rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE",
        spans = listOf(EventSpan.THIS, EventSpan.FOLLOWING, EventSpan.ALL),
        alertMinutes = listOf(15, 1440),
        availability = EventAvailability.BUSY,
        url = "https://example.org/dentist",
        notes = "Bring the X-rays.",
        editable = true,
    )

    /** An all-day event that does not repeat, in a calendar the app cannot write. */
    private val minimalEvent = EventDetail(
        occurrenceId = "occ-2",
        eventId = "evt-2",
        calendarId = "cal-2",
        title = "Holiday",
        location = "",
        allDay = true,
        start = "2026-11-12T00:00",
        end = "2026-11-13T00:00",
        timeZone = "America/New_York",
        repeats = false,
        rrule = "",
        spans = emptyList(),
        alertMinutes = emptyList(),
        availability = EventAvailability.FREE,
        url = "",
        notes = "",
        editable = false,
    )

    private val calendar = capability(CalendarCapability) {
        input(CalendarCapability.GetOverview, CalendarOverviewRequest(10, 20))
        input(CalendarCapability.GetOverview, CalendarOverviewRequest(), "defaults")
        output(CalendarCapability.GetOverview, calendarOverview)
        output(
            CalendarCapability.GetOverview,
            CalendarOverview(accessGranted = false, calendars = emptyList(), calendarCount = 0, showReminders = false),
            "no_access",
        )

        input(CalendarCapability.ListEvents, ListEventsRequest("2026-11-10", "2026-11-16", listOf("cal-1", "cal-2"), limit = 5, offset = 10))
        input(CalendarCapability.ListEvents, ListEventsRequest("2026-11-10", "2026-11-10"), "defaults")
        output(CalendarCapability.ListEvents, Page(listOf(occurrence, allDayOccurrence), total = 12, offset = 10))
        output(CalendarCapability.ListEvents, Page(emptyList<EventOccurrence>(), 0, 0), "empty")

        input(CalendarCapability.GetEvent, OccurrenceIdRequest("occ-1"))
        output(CalendarCapability.GetEvent, fullEvent)
        output(CalendarCapability.GetEvent, minimalEvent, "minimal")

        input(
            CalendarCapability.CreateEvent,
            CreateEventRequest(
                title = "Dentist",
                start = "2026-11-10T17:30",
                end = "2026-11-10T18:15",
                allDay = false,
                calendarId = "cal-1",
                location = "Main Street 1",
                rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE",
                alertMinutes = listOf(15, 1440),
                availability = EventAvailability.FREE,
                url = "https://example.org/dentist",
                notes = "Bring the X-rays.",
            ),
        )
        input(CalendarCapability.CreateEvent, CreateEventRequest("Holiday", "2026-11-12T00:00"), "defaults")
        input(CalendarCapability.CreateEvent, CreateEventRequest("Holiday", "2026-11-12T00:00", alertMinutes = emptyList()), "no_alerts")
        input(
            CalendarCapability.CreateEvent,
            CreateEventRequest("Holiday", "2026-11-12T00:00", "2026-11-13T00:00", allDay = true, alertMinutes = listOf(-540)),
            "all_day",
        )
        output(CalendarCapability.CreateEvent, minimalEvent)

        input(
            CalendarCapability.UpdateEvent,
            UpdateEventRequest(
                occurrenceId = "occ-1",
                span = EventSpan.FOLLOWING,
                title = "Dentist, second visit",
                start = "2026-11-24T17:00",
                end = "2026-11-24T17:45",
                allDay = false,
                calendarId = "cal-1",
                location = "Main Street 2",
                rrule = "FREQ=MONTHLY;COUNT=3",
                alertMinutes = listOf(30),
                availability = EventAvailability.BUSY,
                url = "https://example.org/dentist/2",
                notes = "No X-rays this time.",
            ),
        )
        input(CalendarCapability.UpdateEvent, UpdateEventRequest("occ-1"), "untouched")
        input(
            CalendarCapability.UpdateEvent,
            UpdateEventRequest("occ-1", EventSpan.ALL, location = "", rrule = "", alertMinutes = emptyList(), url = "", notes = ""),
            "emptied",
        )
        output(CalendarCapability.UpdateEvent, fullEvent.copy(occurrenceId = "occ-9", eventId = "evt-9"))

        input(CalendarCapability.DeleteEvent, DeleteEventRequest("occ-1", EventSpan.ALL))
        input(CalendarCapability.DeleteEvent, DeleteEventRequest("occ-1"), "defaults")
        output(CalendarCapability.DeleteEvent, Unit)

        input(CalendarCapability.CreateCalendar, CreateCalendarRequest("Personal", "#1A73E8"))
        output(CalendarCapability.CreateCalendar, localCalendar)
        input(CalendarCapability.UpdateCalendar, UpdateCalendarRequest("cal-1", "Private", "#d50000", visible = false))
        input(CalendarCapability.UpdateCalendar, UpdateCalendarRequest("cal-1"), "untouched")
        output(CalendarCapability.UpdateCalendar, accountCalendar)
        input(CalendarCapability.DeleteCalendar, CalendarIdRequest("cal-1"))
        output(CalendarCapability.DeleteCalendar, Unit)
        input(CalendarCapability.UpdateSettings, UpdateCalendarSettingsRequest("cal-2", showReminders = false))
        input(CalendarCapability.UpdateSettings, UpdateCalendarSettingsRequest(), "untouched")
        output(CalendarCapability.UpdateSettings, calendarOverview.copy(defaultCalendarId = "cal-2", showReminders = false))
    }

    private val notesSettings = NotesSettings(NoteSort.DATE_EDITED, NotesView.LIST, moveTickedItems = true)
    private val folder = FolderSummary(id = "fold-1", name = "Notes", depth = 0, noteCount = 12, isDefault = true)
    private val nestedFolder = FolderSummary(id = "fold-2", parentId = "fold-1", name = "Recipes", depth = 1, noteCount = 3, isDefault = false)

    private val noteMarkdown = "Shopping\n# For the weekend\n- [ ] eggs\n- [x] **milk**\n\n> ask *Ane* about the `cake`"

    private val noteSummary = NoteSummary(
        id = "note-1", folderId = "fold-1", title = "Shopping", preview = "For the weekend", pinned = true, tags = listOf("home"),
        createdAtEpochMs = T0, editedAtEpochMs = T0 + 60_000, attachmentCount = 1,
    )
    private val deletedNoteSummary = NoteSummary(
        id = "note-2", title = "Old idea", preview = "", pinned = false, tags = emptyList(),
        createdAtEpochMs = T0, editedAtEpochMs = T0, deletedAtEpochMs = T0 + 120_000, daysLeft = 29, attachmentCount = 0,
    )

    /** Every field of a note set: one in Recently Deleted, read from the middle, with an attachment. */
    private val fullNote = NoteDetail(
        id = "note-1",
        folderId = "fold-1",
        title = "Shopping",
        pinned = true,
        tags = listOf("home"),
        createdAtEpochMs = T0,
        editedAtEpochMs = T0 + 60_000,
        deletedAtEpochMs = T0 + 120_000,
        daysLeft = 29,
        attachmentCount = 1,
        markdown = noteMarkdown,
        offset = 6_000,
        totalChars = 6_000 + noteMarkdown.length,
        attachments = listOf(photo),
    )

    /** A short note that comes whole in one answer. */
    private val minimalNote = NoteDetail(
        id = "note-3",
        title = "Idea",
        pinned = false,
        tags = emptyList(),
        createdAtEpochMs = T0,
        editedAtEpochMs = T0,
        attachmentCount = 0,
        markdown = "Idea",
        offset = 0,
        totalChars = 4,
    )

    private val notes = capability(NotesCapability) {
        input(NotesCapability.GetOverview, NotesOverviewRequest(10, 20))
        input(NotesCapability.GetOverview, NotesOverviewRequest(), "defaults")
        output(
            NotesCapability.GetOverview,
            NotesOverview(
                allNotesCount = 40,
                folders = listOf(folder, nestedFolder),
                folderCount = 22,
                tags = listOf(TagSummary("home", 5), TagSummary("work", 0)),
                tagCount = 22,
                recentlyDeletedCount = 2,
                settings = notesSettings,
            ),
        )
        output(
            NotesCapability.GetOverview,
            NotesOverview(0, emptyList(), 0, emptyList(), 0, 0, NotesSettings(NoteSort.TITLE, NotesView.GALLERY, moveTickedItems = false)),
            "empty",
        )

        input(NotesCapability.ListNotes, ListNotesRequest(folderId = "fold-1", limit = 5, offset = 10))
        input(NotesCapability.ListNotes, ListNotesRequest(), "defaults")
        input(NotesCapability.ListNotes, ListNotesRequest(tags = listOf("home", "work")), "tags")
        input(NotesCapability.ListNotes, ListNotesRequest(deleted = true), "deleted")
        input(NotesCapability.ListNotes, ListNotesRequest(query = "cake"), "query")
        output(NotesCapability.ListNotes, Page(listOf(noteSummary, deletedNoteSummary), total = 12, offset = 10))
        output(NotesCapability.ListNotes, Page(emptyList<NoteSummary>(), 0, 0), "empty")

        input(NotesCapability.GetNote, GetNoteRequest("note-1", offset = 6_000, maxChars = 1_000))
        input(NotesCapability.GetNote, GetNoteRequest("note-1"), "defaults")
        output(NotesCapability.GetNote, fullNote)
        output(NotesCapability.GetNote, minimalNote, "minimal")

        input(NotesCapability.CreateNote, CreateNoteRequest(noteMarkdown, "fold-1", pinned = true))
        input(NotesCapability.CreateNote, CreateNoteRequest("Idea"), "defaults")
        output(NotesCapability.CreateNote, minimalNote)

        input(NotesCapability.UpdateNote, UpdateNoteRequest("note-1", markdown = noteMarkdown, folderId = "fold-2", pinned = true))
        input(NotesCapability.UpdateNote, UpdateNoteRequest("note-1", append = "- [ ] bread"), "append")
        input(NotesCapability.UpdateNote, UpdateNoteRequest("note-1"), "untouched")
        output(NotesCapability.UpdateNote, fullNote.copy(deletedAtEpochMs = null, daysLeft = null, offset = 0, totalChars = noteMarkdown.length))

        input(NotesCapability.DeleteNote, NoteIdRequest("note-1"))
        output(NotesCapability.DeleteNote, Unit)
        input(NotesCapability.DeleteNotePermanently, NoteIdRequest("note-2"))
        output(NotesCapability.DeleteNotePermanently, Unit)
        input(NotesCapability.RestoreNote, NoteIdRequest("note-2"))
        output(NotesCapability.RestoreNote, minimalNote)

        input(NotesCapability.CreateFolder, CreateFolderRequest("Recipes", "fold-1"))
        input(NotesCapability.CreateFolder, CreateFolderRequest("Work"), "defaults")
        output(NotesCapability.CreateFolder, nestedFolder)
        input(NotesCapability.UpdateFolder, UpdateFolderRequest("fold-2", "Cooking", "fold-3"))
        input(NotesCapability.UpdateFolder, UpdateFolderRequest("fold-2", parentId = ""), "top_level")
        input(NotesCapability.UpdateFolder, UpdateFolderRequest("fold-2"), "untouched")
        output(NotesCapability.UpdateFolder, folder)
        input(NotesCapability.DeleteFolder, FolderIdRequest("fold-2"))
        output(NotesCapability.DeleteFolder, Unit)

        input(NotesCapability.UpdateSettings, UpdateNotesSettingsRequest(NoteSort.DATE_CREATED, NotesView.GALLERY, moveTickedItems = false))
        input(NotesCapability.UpdateSettings, UpdateNotesSettingsRequest(), "untouched")
        output(NotesCapability.UpdateSettings, notesSettings)
    }

    /** The attachments capabilities share one shape, so they share one set of samples but for the item id. */
    private fun attachments(definition: AttachmentsCapability, itemId: String) = capability(definition) {
        input(definition.Add, AddAttachmentRequest(itemId, "file-1", "receipt.pdf"))
        input(definition.Add, AddAttachmentRequest(itemId, "file-1"), "defaults")
        output(definition.Add, attachment)
        input(definition.Remove, AttachmentRequest(itemId, "att-1"))
        output(definition.Remove, Unit)
        input(definition.Open, AttachmentRequest(itemId, "att-2"))
        output(definition.Open, FileHandle("file-9", "photo.jpg", "image/jpeg", 5_000_000_000))
    }

    private val reminderAttachments = attachments(ReminderAttachments, "rem-1")
    private val noteAttachments = attachments(NoteAttachments, "note-1")

    /** The bytes of upload and download travel beside the envelope, so the samples carry none. */
    private val files = capability(FilesCapability) {
        input(FilesCapability.Upload, UploadFileRequest("photo.jpg", "image/jpeg", 5_000_000_000))
        input(FilesCapability.Upload, UploadFileRequest("empty.txt", "text/plain", 0), "empty")
        output(FilesCapability.Upload, FileHandle("file-1", "photo.jpg", "image/jpeg", 5_000_000_000))
        input(FilesCapability.Download, FileIdRequest("file-9"))
        output(FilesCapability.Download, FileHandle("file-9", "receipt.pdf", "application/pdf", 48_213))
        input(FilesCapability.Discard, FileIdRequest("file-1"))
        output(FilesCapability.Discard, Unit)
    }

    private val queuedSummary = AiJob(
        jobId = "job-1",
        task = AiTask.SUMMARIZE_MEETING,
        state = JobState.QUEUED,
        engineId = "gemma-2b",
        createdAtEpochMs = T0,
        updatedAtEpochMs = T0,
        requestKey = "meeting-7#1",
    )

    private val ai = capability(AiCapability) {
        input(AiCapability.ListEngines, NoInput)
        output(
            AiCapability.ListEngines,
            EngineList(
                listOf(
                    AiEngine("gemma-2b", "Gemma 2B", EngineKind.ON_DEVICE, listOf(AiTask.SUMMARIZE_MEETING, AiTask.ASK), isDefault = true),
                    AiEngine("claude", "Claude", EngineKind.API, listOf(AiTask.SUMMARIZE_MEETING, AiTask.ASK), available = false),
                    AiEngine("whisper-base", "Whisper base", EngineKind.ON_DEVICE, listOf(AiTask.TRANSCRIBE), isDefault = true),
                ),
            ),
        )
        output(AiCapability.ListEngines, EngineList(emptyList()), "empty")
        input(
            AiCapability.Transcribe,
            TranscribeRequest("file-8", engineId = "whisper-base", language = "es-ES", fromMs = 600_000, requestKey = "meeting-7#t1"),
        )
        input(AiCapability.Transcribe, TranscribeRequest("file-9"), "defaults")
        output(
            AiCapability.Transcribe,
            AiJob("job-3", AiTask.TRANSCRIBE, JobState.QUEUED, "whisper-base", createdAtEpochMs = T0, updatedAtEpochMs = T0, requestKey = "meeting-7#t1"),
        )
        input(
            AiCapability.SummarizeMeeting,
            SummarizeMeetingRequest(
                transcript = TextInput(text = "Ane: we ship on Friday.\nJon: I will write the notes."),
                engineId = "gemma-2b",
                title = "Weekly sync",
                startedAtEpochMs = T0,
                durationMs = 1_800_000,
                language = "eu",
                requestKey = "meeting-7#1",
            ),
        )
        input(AiCapability.SummarizeMeeting, SummarizeMeetingRequest(TextInput(fileId = "file-3")), "staged_default_engine")
        output(AiCapability.SummarizeMeeting, queuedSummary)
        input(
            AiCapability.Ask,
            AskRequest(
                question = "When do we ship?",
                documents = listOf(
                    AskDocument(TextInput(text = "Ane: we ship on Friday."), "Weekly sync", T0),
                    AskDocument(TextInput(fileId = "file-4")),
                ),
                engineId = "claude",
                language = "es",
                requestKey = "ask-12",
            ),
        )
        input(AiCapability.Ask, AskRequest("Any risks?", listOf(AskDocument(TextInput(text = "None so far.")))), "minimal")
        output(AiCapability.Ask, AiJob("job-2", AiTask.ASK, JobState.QUEUED, "claude", createdAtEpochMs = T0, updatedAtEpochMs = T0))
        input(AiCapability.GetJob, JobIdRequest("job-1"))
        output(
            AiCapability.GetJob,
            queuedSummary.copy(
                state = JobState.SUCCEEDED,
                progressPercent = 100,
                updatedAtEpochMs = T0 + 95_000,
                summary = MeetingSummary(
                    overview = "The team agreed on the release date.",
                    decisions = listOf("Ship on Friday."),
                    actionItems = listOf("Jon writes the release notes."),
                ),
            ),
        )
        output(
            AiCapability.GetJob,
            AiJob("job-2", AiTask.ASK, JobState.SUCCEEDED, "claude", 100, T0, T0 + 4_000, answer = "On Friday."),
            "answer",
        )
        output(
            AiCapability.GetJob,
            queuedSummary.copy(
                state = JobState.FAILED,
                updatedAtEpochMs = T0 + 2_000,
                failure = JobFailure(FailureReason.ENGINE_UNAVAILABLE, "Model not downloaded"),
            ),
            "failed",
        )
        output(
            AiCapability.GetJob,
            AiJob(
                "job-3", AiTask.TRANSCRIBE, JobState.SUCCEEDED, "whisper-base", 100, T0, T0 + 240_000,
                transcript = TranscriptInfo(passageCount = 412, language = "es-ES"),
            ),
            "transcript",
        )
        input(AiCapability.GetTranscriptPassages, TranscriptPassagesRequest("job-3", offset = 100, limit = 2))
        input(AiCapability.GetTranscriptPassages, TranscriptPassagesRequest("job-3"), "defaults")
        output(
            AiCapability.GetTranscriptPassages,
            TranscriptPage(
                listOf(
                    TranscriptPassage(612_400, 615_900, "Ostiralean argitaratuko dugu."),
                    TranscriptPassage(616_000, 619_250, "Yo escribo las notas."),
                ),
                total = 412,
                offset = 100,
            ),
        )
        output(AiCapability.GetTranscriptPassages, TranscriptPage(emptyList(), total = 0, offset = 0), "empty")
        input(AiCapability.CancelJob, JobIdRequest("job-1"))
        output(AiCapability.CancelJob, queuedSummary.copy(state = JobState.CANCELLED, updatedAtEpochMs = T0 + 1_000))
        event(AiCapability.JobChanged, queuedSummary.copy(state = JobState.RUNNING, progressPercent = 40, updatedAtEpochMs = T0 + 30_000))
    }

    private fun <T> envelopeCase(name: String, serializer: KSerializer<T>, value: T) =
        FixtureCase(ENVELOPE_GROUP, ENVELOPE_VERSION, name, serializer, value)

    private val envelope: List<FixtureCase<*>> = listOf(
        envelopeCase(
            "kora_request",
            KoraRequest.serializer(),
            KoraRequest("kardio-req-1", "media.playback", CapabilityVersion(1, 0), "seek_to", MediaPlayback.SeekTo.encodeInput(CborCodec, SeekRequest(90_000))),
        ),
        envelopeCase(
            "kora_response.success",
            KoraResponse.serializer(),
            KoraResponse.Success("kardio-req-1", MediaPlayback.GetState.encodeOutput(CborCodec, playing)),
        ),
        envelopeCase(
            "kora_response.failure",
            KoraResponse.serializer(),
            KoraResponse.Failure("kardio-req-1", KoraError(ErrorCode.INCOMPATIBLE_VERSION, "'media.playback' is at 1.0, which cannot serve 1.2")),
        ),
        envelopeCase("kora_error", KoraError.serializer(), KoraError(ErrorCode.UNAUTHORIZED, "Scope 'media.playback.control' is not granted")),
        envelopeCase(
            "subscribe_request",
            SubscribeRequest.serializer(),
            SubscribeRequest("kardio-sub-1", "location", CapabilityVersion(1, 1), "fixes_batched"),
        ),
        envelopeCase(
            "kora_event",
            KoraEvent.serializer(),
            KoraEvent("kardio-sub-1", "location", "fix_changed", LocationCapability.FixChanged.encode(CborCodec, fix)),
        ),
        envelopeCase(
            "node_descriptor",
            NodeDescriptor.serializer(),
            NodeDescriptor(
                "eus.bihurri.sonora",
                "Sonora",
                listOf(CapabilityDescriptor("media.library", CapabilityVersion(1, 0)), CapabilityDescriptor("media.playback", CapabilityVersion(1, 0))),
                koraVersion = "1.0.0",
            ),
        ),
    )

    /** All samples of the current code. */
    val cases: List<FixtureCase<*>> = library + playback + history + artwork + fitness + location +
        reminders + calendar + notes + reminderAttachments + noteAttachments + files + volume + ai + envelope

    /** Operation inputs, outputs and events of a built-in capability that a sample must cover. */
    fun requiredNames(capability: Capability): Set<String> =
        capability.operations.flatMap { listOf("${it.name}.input", "${it.name}.output") }.toSet() +
            capability.events.map { "${it.name}.event" }
}
