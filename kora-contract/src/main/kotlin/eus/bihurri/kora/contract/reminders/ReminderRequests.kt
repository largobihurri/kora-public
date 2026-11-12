package eus.bihurri.kora.contract.reminders

import eus.bihurri.kora.contract.IntegerRange
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime

/*
 * Request types of the reminders capability. Every request is flat (scalars, wire enums and lists of scalars), so
 * each field reaches a model as a typed tool parameter. A request checks the wire format only: a blank id, a date or
 * a time that does not parse, a `limit`, an `offset`, a day of the month or a month out of its range, fields that
 * exclude each other, and fields that need each other within the request (a time without a date in a new reminder,
 * a `repeat*` field beside `repeatUnit` [RepeatUnit.NONE], a list and a parent together in a new reminder, a kind of
 * day without its ordinal). Such a request fails with `invalid_payload` before it reaches the provider. The rules of
 * the domain (a blank title, a repetition or an early alert without a date, which repetitions make sense, how many
 * units a repetition or an early alert counts) are the provider's.
 *
 * An enum constant this version does not know, sent by a newer caller or misspelled, decodes as `UNKNOWN` and is not
 * rejected here: old Kora reads new Kora (`docs/compatibility.md`). The provider rejects it with `invalid_payload`
 * and never reads it as another constant.
 */

private val DATE_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")
private val TIME_SHAPE = Regex("""\d{2}:\d{2}""")

/** Days of the month a repetition may name. */
private val MONTH_DAYS = 1..31

/** Months of the year a repetition may name. */
private val MONTHS = 1..12

private fun isLocalDate(value: String): Boolean =
    DATE_SHAPE.matches(value) && runCatching { LocalDate.parse(value) }.isSuccess

private fun isLocalTime(value: String): Boolean =
    TIME_SHAPE.matches(value) && runCatching { LocalTime.parse(value) }.isSuccess

/** [value] is absent (null or empty) or a local date `YYYY-MM-DD`. */
private fun requireDateOrEmpty(name: String, value: String?) {
    require(value.isNullOrEmpty() || isLocalDate(value)) { "$name must be a date YYYY-MM-DD or empty: '$value'" }
}

/** [value] is absent (null or empty) or a local time of day `HH:mm`. */
private fun requireTimeOrEmpty(name: String, value: String?) {
    require(value.isNullOrEmpty() || isLocalTime(value)) { "$name must be a time HH:mm or empty: '$value'" }
}

private fun requireMonthDays(days: List<Int>?) {
    require(days == null || days.all { it in MONTH_DAYS }) { "repeatMonthDays must be within $MONTH_DAYS: $days" }
}

private fun requireMonths(months: List<Int>?) {
    require(months == null || months.all { it in MONTHS }) { "repeatMonths must be within $MONTHS: $months" }
}

/** A rule that does not repeat ([repeatUnit] [RepeatUnit.NONE]) sets no other `repeat*` field. */
private fun requireNothingElseWhenNotRepeating(repeatUnit: RepeatUnit?, anyOtherSet: Boolean) {
    require(repeatUnit != RepeatUnit.NONE || !anyOtherSet) {
        "repeatUnit none does not repeat: send no other repeat field with it"
    }
}

/** A kind of day (`repeatDayKind`) counts only with an ordinal ([repeatOrdinal]) that says which of those days. */
private fun requireOrdinalForDayKind(dayKindSet: Boolean, repeatOrdinal: RepeatOrdinal?) {
    require(!dayKindSet || (repeatOrdinal != null && repeatOrdinal != RepeatOrdinal.NONE)) {
        "repeatDayKind needs repeatOrdinal: the first to fifth or the last of those days"
    }
}

/** The window of a listing: [limit] within 1..[RemindersCapability.MAX_LIMIT], [offset] not negative. */
private fun requirePage(limit: Int, offset: Int) {
    require(limit in 1..RemindersCapability.MAX_LIMIT) {
        "limit must be within 1..${RemindersCapability.MAX_LIMIT}: $limit"
    }
    require(offset >= 0) { "offset must not be negative: $offset" }
}

/**
 * Request of [RemindersCapability.GetOverview]: [limit] and [offset] window both the lists and the tags of the
 * answer. [limit] is within 1..[RemindersCapability.MAX_LIMIT] and defaults to [RemindersCapability.DEFAULT_LIMIT];
 * [offset] is not negative.
 */
@Serializable
public data class RemindersOverviewRequest(
    @IntegerRange(min = 1, max = RemindersCapability.MAX_LIMIT.toLong())
    public val limit: Int = RemindersCapability.DEFAULT_LIMIT,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
) {
    init {
        requirePage(limit, offset)
    }
}

/**
 * Request of [RemindersCapability.ListReminders]: one page of reminders, chosen by at most one selector.
 *
 * - [listId]: the reminders of that list.
 * - [smartList]: the reminders of that smart list.
 * - [tags], when not empty: the reminders that carry all of them.
 * - [parentId]: the subtasks of that reminder.
 * - With no selector: every open reminder.
 *
 * Two selectors at once are rejected.
 *
 * - **Subtasks.** A listing of a list ([listId]) holds its top-level reminders, each with its `subtaskCount`; the
 *   subtasks of a reminder are the listing by [parentId]. A smart list, tags and no selector list every reminder
 *   that matches, a subtask included, which carries its `parentId`.
 * - **Order.** Items come in the provider's display order: for a list grouped in sections, the order the app shows
 *   it in, section by section, not a flat sort.
 * - **Completed reminders.** [includeCompleted] adds the completed reminders of a list ([listId]). Only a list
 *   honours it: a smart list ignores it and shows what it always shows ([SmartList.COMPLETED] is the one that lists
 *   completed reminders), and with the other selectors the provider may ignore it.
 *
 * [limit] is within 1..[RemindersCapability.MAX_LIMIT] and defaults to [RemindersCapability.DEFAULT_LIMIT]; [offset]
 * is not negative. An id that is present must not be blank.
 */
@Serializable
public data class ListRemindersRequest(
    public val listId: String? = null,
    public val smartList: SmartList? = null,
    public val tags: List<String> = emptyList(),
    public val parentId: String? = null,
    public val includeCompleted: Boolean = false,
    @IntegerRange(min = 1, max = RemindersCapability.MAX_LIMIT.toLong())
    public val limit: Int = RemindersCapability.DEFAULT_LIMIT,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
) {
    init {
        require(listId == null || listId.isNotBlank()) { "listId must not be blank" }
        require(parentId == null || parentId.isNotBlank()) { "parentId must not be blank" }
        val selectors = listOf(listId != null, smartList != null, tags.isNotEmpty(), parentId != null).count { it }
        require(selectors <= 1) { "At most one of listId, smartList, tags and parentId selects the reminders" }
        requirePage(limit, offset)
    }
}

/**
 * Names one reminder by its opaque [reminderId], which must not be blank. Request of
 * [RemindersCapability.GetReminder], [RemindersCapability.ReopenReminder] and [RemindersCapability.DeleteReminder].
 */
@Serializable
public data class ReminderIdRequest(public val reminderId: String) {
    init {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
    }
}

/**
 * Request of [RemindersCapability.CreateReminder]. An absent optional field is the provider's to fill.
 *
 * - [title] may hold tags: a provider may read a `#word` in it as a tag, and the answer shows the title and the tags
 *   it stored.
 * - [listId] absent: the default list. [parentId] makes it a subtask of that reminder, in its list, so [listId] and
 *   [parentId] together are rejected. An id that is present must not be blank.
 * - [dueDate] is a local date `YYYY-MM-DD` and [dueTime] a local time `HH:mm`; empty means none. A [dueTime] without
 *   a [dueDate] is rejected.
 * - The `repeat*` fields are the repetition rule, in the shape the reminders domain has:
 *   - [repeatUnit]: does not repeat ([RepeatUnit.NONE], the default), or by the hour, day, week, month or year;
 *   - [repeatInterval]: every N units; which N make sense is the provider's rule;
 *   - [repeatWeekdays]: by the week, on these days;
 *   - [repeatMonthDays]: by the month, on these days (each within 1..31);
 *   - [repeatOrdinal] and [repeatDayKind]: by the month, on the first to fifth or last weekday, day, working day or
 *     weekend day. A [repeatDayKind] other than [RepeatDayKind.DAY] needs a [repeatOrdinal] other than
 *     [RepeatOrdinal.NONE];
 *   - [repeatMonths]: by the year, in these months (each within 1..12);
 *   - [repeatUntil]: the last day it may fall on, a date `YYYY-MM-DD`; empty is never;
 *   - [repeatFromCompletion]: the next occurrence counts from the day the reminder is completed instead of from its
 *     due date.
 *
 *   With [RepeatUnit.NONE] every other `repeat*` field keeps its default; a request that sets one is rejected.
 *
 *   Which combinations make sense, and the day a rule lands on when a month lacks it, are the provider's rules. This
 *   is not the calendar's `rrule`, which is an exact RFC 5545 rule.
 * - [earlyAlertAmount] `0` means no early alert; otherwise that many [earlyAlertUnit] before it is due. Which amounts
 *   make sense is the provider's rule.
 */
@Serializable
public data class CreateReminderRequest(
    public val title: String,
    public val listId: String? = null,
    public val parentId: String? = null,
    public val notes: String = "",
    public val dueDate: String = "",
    public val dueTime: String = "",
    public val repeatUnit: RepeatUnit = RepeatUnit.NONE,
    public val repeatInterval: Int = 1,
    public val repeatWeekdays: List<Weekday> = emptyList(),
    public val repeatMonthDays: List<Int> = emptyList(),
    public val repeatOrdinal: RepeatOrdinal = RepeatOrdinal.NONE,
    public val repeatDayKind: RepeatDayKind = RepeatDayKind.DAY,
    public val repeatMonths: List<Int> = emptyList(),
    public val repeatUntil: String = "",
    public val repeatFromCompletion: Boolean = false,
    public val flagged: Boolean = false,
    public val priority: ReminderPriority = ReminderPriority.NONE,
    public val url: String = "",
    public val tags: List<String> = emptyList(),
    public val earlyAlertAmount: Int = 0,
    public val earlyAlertUnit: EarlyAlertUnit = EarlyAlertUnit.MINUTES,
) {
    init {
        require(listId == null || listId.isNotBlank()) { "listId must not be blank" }
        require(parentId == null || parentId.isNotBlank()) { "parentId must not be blank" }
        require(listId == null || parentId == null) {
            "A subtask goes to the list of its parent: send listId or parentId"
        }
        requireDateOrEmpty("dueDate", dueDate)
        requireTimeOrEmpty("dueTime", dueTime)
        require(dueTime.isEmpty() || dueDate.isNotEmpty()) { "dueTime needs a dueDate" }
        val anyOtherRepeatField = repeatInterval != 1 || repeatWeekdays.isNotEmpty() || repeatMonthDays.isNotEmpty() ||
            repeatOrdinal != RepeatOrdinal.NONE || repeatDayKind != RepeatDayKind.DAY || repeatMonths.isNotEmpty() ||
            repeatUntil.isNotEmpty() || repeatFromCompletion
        requireNothingElseWhenNotRepeating(repeatUnit, anyOtherRepeatField)
        requireOrdinalForDayKind(repeatDayKind != RepeatDayKind.DAY, repeatOrdinal)
        requireMonthDays(repeatMonthDays)
        requireMonths(repeatMonths)
        requireDateOrEmpty("repeatUntil", repeatUntil)
    }
}

/**
 * Request of [RemindersCapability.UpdateReminder]: a patch of the reminder [reminderId].
 *
 * Only [reminderId] is required. Every other field is nullable, and null (or absent) means "leave as it is". To
 * empty a field send an empty string (texts, dates, times), an empty list ([tags]) or `0` ([earlyAlertAmount]);
 * enums and booleans always take a value. The change is applied whole or not at all, and it never deletes or
 * completes: those are operations of their own.
 *
 * - [listId] moves the reminder, with its subtasks; a subtask moved alone leaves its parent. It must not be blank. A
 *   [listId] equal to the reminder's current list changes nothing: the reminder keeps its parent and its place.
 * - Emptying [dueDate] also empties [dueTime], the repetition and the early alert, which mean nothing without a date.
 * - The `repeat*` fields are one group, [repeatFromCompletion] included. With [repeatUnit] present the rule is
 *   replaced by the `repeat*` fields of the request, the absent ones taking the defaults of [CreateReminderRequest];
 *   [RepeatUnit.NONE] stops the repetition and is sent alone, so any other `repeat*` field beside it is rejected.
 *   Without [repeatUnit] the rule is left alone, and any other `repeat*` field is rejected. A [repeatDayKind] needs
 *   a [repeatOrdinal] other than [RepeatOrdinal.NONE] in the same request.
 * - Changing [dueDate] or the repetition starts a new series at the new due date; changing only [dueTime] does not.
 *
 * Dates, times and the ranges of [repeatMonthDays] and [repeatMonths] are checked as in [CreateReminderRequest].
 */
@Serializable
public data class UpdateReminderRequest(
    public val reminderId: String,
    public val title: String? = null,
    public val listId: String? = null,
    public val notes: String? = null,
    public val dueDate: String? = null,
    public val dueTime: String? = null,
    public val repeatUnit: RepeatUnit? = null,
    public val repeatInterval: Int? = null,
    public val repeatWeekdays: List<Weekday>? = null,
    public val repeatMonthDays: List<Int>? = null,
    public val repeatOrdinal: RepeatOrdinal? = null,
    public val repeatDayKind: RepeatDayKind? = null,
    public val repeatMonths: List<Int>? = null,
    public val repeatUntil: String? = null,
    public val repeatFromCompletion: Boolean? = null,
    public val flagged: Boolean? = null,
    public val priority: ReminderPriority? = null,
    public val url: String? = null,
    public val tags: List<String>? = null,
    public val earlyAlertAmount: Int? = null,
    public val earlyAlertUnit: EarlyAlertUnit? = null,
) {
    init {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
        require(listId == null || listId.isNotBlank()) { "listId must not be blank" }
        requireDateOrEmpty("dueDate", dueDate)
        requireTimeOrEmpty("dueTime", dueTime)
        require(dueDate != "" || dueTime.isNullOrEmpty()) { "A change that empties dueDate cannot set a dueTime" }
        val otherRepeatFields = listOf(
            repeatInterval, repeatWeekdays, repeatMonthDays, repeatOrdinal, repeatDayKind, repeatMonths, repeatUntil,
            repeatFromCompletion,
        )
        require(repeatUnit != null || otherRepeatFields.all { it == null }) {
            "The repeat fields are one group: send repeatUnit with them"
        }
        requireNothingElseWhenNotRepeating(repeatUnit, otherRepeatFields.any { it != null })
        requireOrdinalForDayKind(repeatDayKind != null, repeatOrdinal)
        requireMonthDays(repeatMonthDays)
        requireMonths(repeatMonths)
        requireDateOrEmpty("repeatUntil", repeatUntil)
    }
}

/**
 * Request of [RemindersCapability.CompleteReminder]: complete the reminder [reminderId] at the occurrence the caller
 * saw.
 *
 * [dueDate] (`YYYY-MM-DD`) and [dueTime] (`HH:mm`) name that occurrence. If the reminder no longer stands at it (it
 * was completed in the app, or by this same call a moment ago), nothing changes and the answer shows the reminder as
 * it is, so a repeat after a timeout cannot skip an occurrence. With an empty [dueDate] the reminder is completed as
 * it stands, which is right for one without a date.
 */
@Serializable
public data class CompleteReminderRequest(
    public val reminderId: String,
    public val dueDate: String = "",
    public val dueTime: String = "",
) {
    init {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
        requireDateOrEmpty("dueDate", dueDate)
        requireTimeOrEmpty("dueTime", dueTime)
    }
}

/**
 * Request of [RemindersCapability.SnoozeReminder]: postpone by [snooze] the alert of the occurrence of
 * [reminderId] the caller saw.
 *
 * [dueDate] (`YYYY-MM-DD`, required) and [dueTime] (`HH:mm`, empty when the reminder has no time) name that
 * occurrence, as in [CompleteReminderRequest]: if the reminder no longer stands at it, nothing changes. Only the
 * alert moves, never the due date.
 */
@Serializable
public data class SnoozeReminderRequest(
    public val reminderId: String,
    public val snooze: Snooze,
    public val dueDate: String,
    public val dueTime: String = "",
) {
    init {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
        require(isLocalDate(dueDate)) { "dueDate must be a date YYYY-MM-DD: '$dueDate'" }
        requireTimeOrEmpty("dueTime", dueTime)
    }
}

/** Request of [RemindersCapability.CreateList]: a new list called [name], in [color]. Both are required. */
@Serializable
public data class CreateListRequest(public val name: String, public val color: ListColor)

/**
 * Request of [RemindersCapability.UpdateList]: a patch of the list [listId], which must not be blank. [name] and
 * [color] change when present; null (or absent) leaves them as they are.
 */
@Serializable
public data class UpdateListRequest(
    public val listId: String,
    public val name: String? = null,
    public val color: ListColor? = null,
) {
    init {
        require(listId.isNotBlank()) { "listId must not be blank" }
    }
}

/** Request of [RemindersCapability.DeleteList]: the list [listId], which must not be blank. */
@Serializable
public data class ReminderListIdRequest(public val listId: String) {
    init {
        require(listId.isNotBlank()) { "listId must not be blank" }
    }
}

/**
 * Request of [RemindersCapability.RenameTag]: the tag called [tag] becomes [newName]. A tag is named by its text,
 * so [tag] must not be blank; whether [newName] is acceptable is the provider's rule.
 */
@Serializable
public data class RenameTagRequest(public val tag: String, public val newName: String) {
    init {
        require(tag.isNotBlank()) { "tag must not be blank" }
    }
}

/** Request of [RemindersCapability.DeleteTag]: the tag called [tag], which must not be blank. */
@Serializable
public data class TagRequest(public val tag: String) {
    init {
        require(tag.isNotBlank()) { "tag must not be blank" }
    }
}
