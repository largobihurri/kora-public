package eus.bihurri.kora.contract.reminders

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.Page
import kotlinx.serialization.builtins.serializer

/**
 * Reminders of the provider app: lists, reminders, one level of subtasks, tags, repetition, the early alert,
 * snoozing and smart lists. The vocabulary is app-neutral; what a reminder does stays in the app that provides it.
 *
 * - **Scopes and effects.** Reads need [ReadScope] and changes [WriteScope], so a caller can tell a read from a
 *   change by the scope alone. Each operation also declares its [OperationSpec.effect]: the reads are
 *   [OperationEffect.READ]; [DeleteReminder], [DeleteList] and [DeleteTag] lose something for good and are
 *   [OperationEffect.DESTRUCTIVE]; every other change is [OperationEffect.WRITE].
 * - **Dates and times.** A local date is `YYYY-MM-DD` and a local time of day `HH:mm`, without a zone: the wall
 *   clock where the user is. A field has one format whatever the other fields say. A wall time a clock change
 *   skips means the same time after the change, later by the length of the gap; one that happens twice means the
 *   first of the two. Instants (created, completed, snoozed) are epoch milliseconds in `...EpochMs` fields.
 * - **One listing.** [ListReminders] takes at most one selector (a list, a smart list, tags or the parent of
 *   subtasks) instead of one operation per kind of listing. A list shows its top-level reminders and each
 *   reminder's subtasks are a listing of their own; see [ListRemindersRequest]. Listings are paged with `limit`
 *   (default [DEFAULT_LIMIT], at most [MAX_LIMIT]) and `offset`.
 * - **Create and change.** [CreateReminder] and [UpdateReminder] answer the resulting [ReminderDetail].
 *   [UpdateReminder] and [UpdateList] are patches: only the id is required, null or absent leaves a field as it is,
 *   and a change is applied whole or not at all. A change never deletes and never completes. A provider may read a
 *   `#word` in a title as a tag; the answer shows what it stored.
 * - **Completing names the occurrence.** [CompleteReminder] carries the `dueDate` (and `dueTime`) the caller saw. If
 *   the reminder no longer stands at that occurrence, nothing changes and the answer shows it as it is, so a repeat
 *   after a timeout cannot skip an occurrence. Without a `dueDate` it completes the reminder as it stands. A
 *   repeating reminder moves to its next occurrence and stays open, which the answer shows. [SnoozeReminder] works
 *   the same way on the alert of that occurrence and moves only the alert, never the due date.
 * - **Failures.** A malformed request, an id the provider does not have and a change the provider's rules reject
 *   are `invalid_payload`; a provider that cannot serve until the user acts in the app answers
 *   `provider_unavailable`.
 *
 * Version history:
 * - 1.0: [GetOverview], [ListReminders], [GetReminder], [CreateReminder], [UpdateReminder], [CompleteReminder],
 *   [ReopenReminder], [SnoozeReminder], [DeleteReminder], [CreateList], [UpdateList], [DeleteList], [RenameTag],
 *   [DeleteTag].
 */
public object RemindersCapability : Capability(CapabilityId("reminders"), CapabilityVersion(1, 0)) {
    /** `limit` of a listing when the caller does not choose: 20 items, which fit the size an assistant shows. */
    public const val DEFAULT_LIMIT: Int = 20

    /** Largest `limit` of a listing. */
    public const val MAX_LIMIT: Int = 50

    public val ReadScope: Scope = Scope("reminders.read")

    public val WriteScope: Scope = Scope("reminders.write")

    public val GetOverview: OperationSpec<RemindersOverviewRequest, RemindersOverview> = operation(
        name = "get_overview",
        input = RemindersOverviewRequest.serializer(),
        output = RemindersOverview.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get the counts of the smart lists, the reminder lists with their open counts, the tags and " +
            "the default list. limit and offset page the lists and the tags.",
    )

    public val ListReminders: OperationSpec<ListRemindersRequest, Page<ReminderSummary>> = operation(
        name = "list_reminders",
        input = ListRemindersRequest.serializer(),
        output = Page.serializer(ReminderSummary.serializer()),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "List reminders, one page at a time: those of a list (listId), of a smart list (smartList), " +
            "carrying all the given tags, or the subtasks of a reminder (parentId). Use at most one of them; with " +
            "none it lists every open reminder. A list shows its top-level reminders; includeCompleted counts only " +
            "for a list.",
    )

    public val GetReminder: OperationSpec<ReminderIdRequest, ReminderDetail> = operation(
        name = "get_reminder",
        input = ReminderIdRequest.serializer(),
        output = ReminderDetail.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get one reminder with all its fields: notes, repetition, early alert and attachments.",
    )

    public val CreateReminder: OperationSpec<CreateReminderRequest, ReminderDetail> = operation(
        name = "create_reminder",
        input = CreateReminderRequest.serializer(),
        output = ReminderDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Create a reminder, in the default list unless listId is given, or a subtask of parentId " +
            "(then without listId). dueDate is YYYY-MM-DD and dueTime HH:mm, in the user's local time; a dueTime " +
            "needs a dueDate. A #word in the title may become a tag.",
    )

    public val UpdateReminder: OperationSpec<UpdateReminderRequest, ReminderDetail> = operation(
        name = "update_reminder",
        input = UpdateReminderRequest.serializer(),
        output = ReminderDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Change a reminder: only the fields sent change, and an empty text, an empty list or 0 " +
            "empties one. listId moves it to another list; the repeat fields are replaced together and need " +
            "repeatUnit, and repeatUnit none stops the repetition on its own.",
    )

    public val CompleteReminder: OperationSpec<CompleteReminderRequest, ReminderDetail> = operation(
        name = "complete_reminder",
        input = CompleteReminderRequest.serializer(),
        output = ReminderDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Mark a reminder as done. Send the dueDate and dueTime you saw, so a repeated call cannot " +
            "skip an occurrence; a repeating reminder moves to its next occurrence and stays open.",
    )

    public val ReopenReminder: OperationSpec<ReminderIdRequest, ReminderDetail> = operation(
        name = "reopen_reminder",
        input = ReminderIdRequest.serializer(),
        output = ReminderDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Mark a completed reminder as not done.",
    )

    public val SnoozeReminder: OperationSpec<SnoozeReminderRequest, ReminderDetail> = operation(
        name = "snooze_reminder",
        input = SnoozeReminderRequest.serializer(),
        output = ReminderDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Postpone the alert of a reminder by one hour or until tomorrow. Send the dueDate and dueTime " +
            "you saw; only the alert moves, never the due date.",
    )

    public val DeleteReminder: OperationSpec<ReminderIdRequest, Unit> = operation(
        name = "delete_reminder",
        input = ReminderIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete a reminder for good, with its subtasks and the files attached to them. It cannot be " +
            "undone.",
    )

    public val CreateList: OperationSpec<CreateListRequest, ReminderListSummary> = operation(
        name = "create_list",
        input = CreateListRequest.serializer(),
        output = ReminderListSummary.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Create a reminder list with a name and a colour.",
    )

    public val UpdateList: OperationSpec<UpdateListRequest, ReminderListSummary> = operation(
        name = "update_list",
        input = UpdateListRequest.serializer(),
        output = ReminderListSummary.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Rename or recolour a reminder list; only the fields sent change.",
    )

    public val DeleteList: OperationSpec<ReminderListIdRequest, Unit> = operation(
        name = "delete_list",
        input = ReminderListIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete a reminder list for good, with its reminders and their attached files. It cannot be " +
            "undone. The only remaining list cannot be deleted (invalid_payload).",
    )

    public val RenameTag: OperationSpec<RenameTagRequest, TagName> = operation(
        name = "rename_tag",
        input = RenameTagRequest.serializer(),
        output = TagName.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Rename a tag of the reminders.",
    )

    public val DeleteTag: OperationSpec<TagRequest, Unit> = operation(
        name = "delete_tag",
        input = TagRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete a tag of the reminders for good: every reminder loses it.",
    )
}
