package eus.bihurri.kora.contract.reminders

import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import eus.bihurri.kora.contract.files.AttachmentInfo
import kotlinx.serialization.Serializable

/*
 * Enums and answer types of the reminders capability. Every id is an opaque string chosen by the provider. Local
 * dates are `YYYY-MM-DD` and local times of day `HH:mm`, without a zone: the wall clock where the user is. Instants
 * are epoch milliseconds in `...EpochMs` fields. Answer types carry no checks, so a newer provider never breaks an
 * older reader.
 */

/** Colour of a reminder list. A newer provider may use a colour this version lacks, which reads as [UNKNOWN]. */
@Serializable(with = ListColor.Serializer::class)
public enum class ListColor(override val wireName: String) : WireEnum {
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    BLUE("blue"),
    PURPLE("purple"),
    BROWN("brown"),

    /** A colour this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<ListColor>(
        "eus.bihurri.kora.contract.reminders.ListColor",
        entries,
        UNKNOWN,
    )
}

/** Priority of a reminder. A newer provider may use a priority this version lacks, which reads as [UNKNOWN]. */
@Serializable(with = ReminderPriority.Serializer::class)
public enum class ReminderPriority(override val wireName: String) : WireEnum {
    NONE("none"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    /** A priority this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<ReminderPriority>(
        "eus.bihurri.kora.contract.reminders.ReminderPriority",
        entries,
        UNKNOWN,
    )
}

/**
 * The built-in smart lists, a selector of [ListRemindersRequest] and the counts of [SmartListCounts]. A constant this
 * version lacks reads as [UNKNOWN].
 */
@Serializable(with = SmartList.Serializer::class)
public enum class SmartList(override val wireName: String) : WireEnum {
    TODAY("today"),
    SCHEDULED("scheduled"),
    ALL("all"),
    FLAGGED("flagged"),
    COMPLETED("completed"),

    /** A smart list this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<SmartList>(
        "eus.bihurri.kora.contract.reminders.SmartList",
        entries,
        UNKNOWN,
    )
}

/** Unit of the early alert of a reminder (`earlyAlertAmount` of them before it is due). */
@Serializable(with = EarlyAlertUnit.Serializer::class)
public enum class EarlyAlertUnit(override val wireName: String) : WireEnum {
    MINUTES("minutes"),
    HOURS("hours"),
    DAYS("days"),
    WEEKS("weeks"),
    MONTHS("months"),

    /** A unit this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<EarlyAlertUnit>(
        "eus.bihurri.kora.contract.reminders.EarlyAlertUnit",
        entries,
        UNKNOWN,
    )
}

/** How long [RemindersCapability.SnoozeReminder] postpones an alert. */
@Serializable(with = Snooze.Serializer::class)
public enum class Snooze(override val wireName: String) : WireEnum {
    IN_ONE_HOUR("in_one_hour"),
    TOMORROW("tomorrow"),

    /** A choice this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<Snooze>(
        "eus.bihurri.kora.contract.reminders.Snooze",
        entries,
        UNKNOWN,
    )
}

/** Unit of a reminder's repetition; [NONE] means it does not repeat. */
@Serializable(with = RepeatUnit.Serializer::class)
public enum class RepeatUnit(override val wireName: String) : WireEnum {
    NONE("none"),
    HOUR("hour"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),

    /** A unit this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<RepeatUnit>(
        "eus.bihurri.kora.contract.reminders.RepeatUnit",
        entries,
        UNKNOWN,
    )
}

/** A day of the week, for a repetition by the week (`repeatWeekdays`). */
@Serializable(with = Weekday.Serializer::class)
public enum class Weekday(override val wireName: String) : WireEnum {
    MONDAY("monday"),
    TUESDAY("tuesday"),
    WEDNESDAY("wednesday"),
    THURSDAY("thursday"),
    FRIDAY("friday"),
    SATURDAY("saturday"),
    SUNDAY("sunday"),

    /** A day this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<Weekday>(
        "eus.bihurri.kora.contract.reminders.Weekday",
        entries,
        UNKNOWN,
    )
}

/**
 * Which of the days of kind `repeatDayKind` a repetition by the month falls on: the first to fifth or the last one.
 * [NONE] means the rule does not use an ordinal.
 */
@Serializable(with = RepeatOrdinal.Serializer::class)
public enum class RepeatOrdinal(override val wireName: String) : WireEnum {
    NONE("none"),
    FIRST("first"),
    SECOND("second"),
    THIRD("third"),
    FOURTH("fourth"),
    FIFTH("fifth"),
    LAST("last"),

    /** An ordinal this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<RepeatOrdinal>(
        "eus.bihurri.kora.contract.reminders.RepeatOrdinal",
        entries,
        UNKNOWN,
    )
}

/**
 * The kind of day `repeatOrdinal` counts in a repetition by the month: one weekday, any day, a working day or a
 * weekend day.
 */
@Serializable(with = RepeatDayKind.Serializer::class)
public enum class RepeatDayKind(override val wireName: String) : WireEnum {
    MONDAY("monday"),
    TUESDAY("tuesday"),
    WEDNESDAY("wednesday"),
    THURSDAY("thursday"),
    FRIDAY("friday"),
    SATURDAY("saturday"),
    SUNDAY("sunday"),
    DAY("day"),
    WEEKDAY("weekday"),
    WEEKEND_DAY("weekend_day"),

    /** A kind this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<RepeatDayKind>(
        "eus.bihurri.kora.contract.reminders.RepeatDayKind",
        entries,
        UNKNOWN,
    )
}

/** How many reminders each smart list ([SmartList]) holds. */
@Serializable
public data class SmartListCounts(
    public val today: Int,
    public val scheduled: Int,
    public val all: Int,
    public val flagged: Int,
    public val completed: Int,
)

/** A reminder list: its [id], [name] and [color], and [openCount], the reminders in it that are not completed. */
@Serializable
public data class ReminderListSummary(
    public val id: String,
    public val name: String,
    public val color: ListColor,
    public val openCount: Int,
)

/**
 * Answer of [RemindersCapability.GetOverview]: the containers of the section and their counts.
 *
 * [lists] and [tags] are one window each of the whole collections, cut with the request's `limit` and `offset`;
 * [listCount] and [tagCount] are the sizes of the whole collections. [defaultListId] is the list a reminder created
 * without a `listId` goes to, or null when the provider has none.
 */
@Serializable
public data class RemindersOverview(
    public val smartCounts: SmartListCounts,
    public val lists: List<ReminderListSummary>,
    public val listCount: Int,
    public val tags: List<String>,
    public val tagCount: Int,
    public val defaultListId: String? = null,
)

/**
 * One reminder in a listing ([RemindersCapability.ListReminders]). Items come in the provider's display order; which
 * listings hold subtasks as rows of their own is said in [ListRemindersRequest].
 *
 * - [parentId] is set on a subtask and names the reminder it belongs to; subtasks are one level deep.
 * - [dueDate] (`YYYY-MM-DD`) and [dueTime] (`HH:mm`) are null when the reminder has none.
 * - [repeats] says whether it has a repetition; the rule itself is in [ReminderDetail].
 * - [subtaskCount] and [attachmentCount] are counts, not lists: the subtasks are a listing of their own
 *   (`parentId` of [ListRemindersRequest]) and the attachments come with [ReminderDetail].
 */
@Serializable
public data class ReminderSummary(
    public val id: String,
    public val listId: String,
    public val parentId: String? = null,
    public val title: String,
    public val dueDate: String? = null,
    public val dueTime: String? = null,
    public val repeats: Boolean,
    public val flagged: Boolean,
    public val priority: ReminderPriority,
    public val completed: Boolean,
    public val completedAtEpochMs: Long? = null,
    public val tags: List<String>,
    public val subtaskCount: Int,
    public val attachmentCount: Int,
)

/**
 * One reminder with every field: the fields of [ReminderSummary] plus its [notes], repetition, [url], early alert,
 * snooze, creation instant and [attachments]. Every create and change of a reminder answers it, so the caller sees
 * what was stored.
 *
 * - The `repeat*` fields are the repetition rule, with the meaning they have in [CreateReminderRequest];
 *   [repeatUnit] is [RepeatUnit.NONE] when the reminder does not repeat, [repeatUntil] is empty when the
 *   repetition never ends and [repeatFromCompletion] says whether the next occurrence counts from the completion.
 * - [seriesStartDate] is the date the repetition counts from, null when it does not repeat.
 * - [earlyAlertAmount] `0` means no early alert.
 * - [snoozedUntilEpochMs] is the instant a snoozed alert will fire, null when it is not snoozed.
 * - [attachments] is empty unless the provider also offers `reminders.attachments` ([ReminderAttachments]).
 */
@Serializable
public data class ReminderDetail(
    public val id: String,
    public val listId: String,
    public val parentId: String? = null,
    public val title: String,
    public val dueDate: String? = null,
    public val dueTime: String? = null,
    public val repeats: Boolean,
    public val flagged: Boolean,
    public val priority: ReminderPriority,
    public val completed: Boolean,
    public val completedAtEpochMs: Long? = null,
    public val tags: List<String>,
    public val subtaskCount: Int,
    public val attachmentCount: Int,
    public val notes: String,
    public val repeatUnit: RepeatUnit,
    public val repeatInterval: Int,
    public val repeatWeekdays: List<Weekday>,
    public val repeatMonthDays: List<Int>,
    public val repeatOrdinal: RepeatOrdinal,
    public val repeatDayKind: RepeatDayKind,
    public val repeatMonths: List<Int>,
    public val repeatUntil: String,
    public val repeatFromCompletion: Boolean,
    public val seriesStartDate: String? = null,
    public val url: String,
    public val earlyAlertAmount: Int,
    public val earlyAlertUnit: EarlyAlertUnit,
    public val snoozedUntilEpochMs: Long? = null,
    public val createdAtEpochMs: Long,
    public val attachments: List<AttachmentInfo> = emptyList(),
)

/** Answer of [RemindersCapability.RenameTag]: the [name] the tag has now. */
@Serializable
public data class TagName(public val name: String)
