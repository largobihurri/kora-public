package eus.bihurri.kora.contract.calendar

import eus.bihurri.kora.contract.IntegerRange
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * Request types of the calendar capability. Every request is flat (scalars, wire enums and lists of scalars), so
 * each field reaches a model as a typed tool parameter. A request checks the wire format only: a blank id, a date
 * or a date and time that does not parse, a number out of its range, a colour that is not `#RRGGBB`, an empty `end`
 * in a change, an `rrule` in a change with [EventSpan.THIS]. Such a request fails with `invalid_payload` before it
 * reaches the provider. The rules of the domain (a blank title, an end before a start, a span the occurrence does
 * not accept) are the provider's.
 *
 * An enum constant this version does not know, sent by a newer caller or misspelled, decodes as `UNKNOWN` and is not
 * rejected here: old Kora reads new Kora (`docs/compatibility.md`). Where the field means something, the provider
 * rejects it with `invalid_payload` and never reads it as another constant: on a repeating event an
 * [EventSpan.UNKNOWN] changes and deletes nothing. On an event that does not repeat a span is ignored, except that an
 * `rrule` there needs [EventSpan.ALL].
 */

private val DATE_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")
private val DATE_TIME_SHAPE = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
private val COLOR_SHAPE = Regex("#[0-9A-Fa-f]{6}")

private fun isLocalDate(value: String): Boolean =
    DATE_SHAPE.matches(value) && runCatching { LocalDate.parse(value) }.isSuccess

private fun isLocalDateTime(value: String): Boolean =
    DATE_TIME_SHAPE.matches(value) && runCatching { LocalDateTime.parse(value) }.isSuccess

private fun requireDate(name: String, value: String) {
    require(isLocalDate(value)) { "$name must be a date YYYY-MM-DD: '$value'" }
}

private fun requireColor(value: String) {
    require(COLOR_SHAPE.matches(value)) { "color must be #RRGGBB: '$value'" }
}

/** The window of a listing: [limit] within 1..[CalendarCapability.MAX_LIMIT], [offset] not negative. */
private fun requirePage(limit: Int, offset: Int) {
    require(limit in 1..CalendarCapability.MAX_LIMIT) {
        "limit must be within 1..${CalendarCapability.MAX_LIMIT}: $limit"
    }
    require(offset >= 0) { "offset must not be negative: $offset" }
}

/**
 * Request of [CalendarCapability.GetOverview]: [limit] and [offset] window the calendars of the answer. [limit] is
 * within 1..[CalendarCapability.MAX_LIMIT] and defaults to [CalendarCapability.DEFAULT_LIMIT]; [offset] is not
 * negative.
 */
@Serializable
public data class CalendarOverviewRequest(
    @IntegerRange(min = 1, max = CalendarCapability.MAX_LIMIT.toLong())
    public val limit: Int = CalendarCapability.DEFAULT_LIMIT,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
) {
    init {
        requirePage(limit, offset)
    }
}

/**
 * Request of [CalendarCapability.ListEvents]: one page of the occurrences that touch the days [from] to [to], both
 * included, in the order they start. An occurrence that lasts several days appears once.
 *
 * [from] and [to] are local dates `YYYY-MM-DD`. [calendarIds], when not empty, narrows the visible calendars to
 * these; none of them may be blank. [limit] is within 1..[CalendarCapability.MAX_LIMIT] and defaults to
 * [CalendarCapability.DEFAULT_LIMIT]; [offset] is not negative.
 */
@Serializable
public data class ListEventsRequest(
    public val from: String,
    public val to: String,
    public val calendarIds: List<String> = emptyList(),
    @IntegerRange(min = 1, max = CalendarCapability.MAX_LIMIT.toLong())
    public val limit: Int = CalendarCapability.DEFAULT_LIMIT,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
) {
    init {
        requireDate("from", from)
        requireDate("to", to)
        require(calendarIds.none { it.isBlank() }) { "calendarIds must not hold a blank id" }
        requirePage(limit, offset)
    }
}

/**
 * Request of [CalendarCapability.GetEvent]: the occurrence [occurrenceId] ([EventOccurrence.occurrenceId]), which
 * must not be blank.
 */
@Serializable
public data class OccurrenceIdRequest(public val occurrenceId: String) {
    init {
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
    }
}

/**
 * Request of [CalendarCapability.CreateEvent]. An absent optional field is the provider's to fill.
 *
 * - [start] and [end] are always a local date and time, `YYYY-MM-DDTHH:mm`. For an [allDay] event only their days
 *   count, both included, and the time is ignored. An empty [end] lets the provider choose the length.
 * - [calendarId] absent: the default calendar. It must not be blank.
 * - [rrule] is an iCalendar `RRULE` value (RFC 5545) without the `RRULE:` prefix, for example
 *   `FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE`; empty means "does not repeat". It is exact; the provider may rewrite it in
 *   a canonical form, and the answer carries the rule as stored. A `UNTIL` in it is read as in RFC 5545: ending in
 *   `Z` it is UTC, otherwise a time in the event's zone ([EventDetail.timeZone]). A new event takes the phone's
 *   time zone.
 * - [alertMinutes] are minutes before the start; a negative value is after it, so "on the day at 09:00" of an
 *   all-day event is `-540`. Null (or absent) gives the event the calendar's default alerts; an empty list gives it
 *   none.
 * - [url] is the event's link. A provider whose calendar has no field for it may keep it as the last line of
 *   [notes], as the phone's calendar does.
 */
@Serializable
public data class CreateEventRequest(
    public val title: String,
    public val start: String,
    public val end: String = "",
    public val allDay: Boolean = false,
    public val calendarId: String? = null,
    public val location: String = "",
    public val rrule: String = "",
    public val alertMinutes: List<Int>? = null,
    public val availability: EventAvailability = EventAvailability.BUSY,
    public val url: String = "",
    public val notes: String = "",
) {
    init {
        require(isLocalDateTime(start)) { "start must be a date and time YYYY-MM-DDTHH:mm: '$start'" }
        require(end.isEmpty() || isLocalDateTime(end)) { "end must be a date and time YYYY-MM-DDTHH:mm or empty: '$end'" }
        require(calendarId == null || calendarId.isNotBlank()) { "calendarId must not be blank" }
    }
}

/**
 * Request of [CalendarCapability.UpdateEvent]: a patch of the occurrence [occurrenceId].
 *
 * Only [occurrenceId] is required. [span] says how far the change reaches in a repeating event and **defaults to
 * [EventSpan.THIS]**: a caller that forgets the field changes one occurrence and not every one. On a repeating
 * event a span the occurrence does not accept ([EventDetail.spans]), [EventSpan.UNKNOWN] included, fails with
 * `invalid_payload`. On an event that does not repeat it is ignored, except for [rrule] below.
 *
 * Every other field is nullable, and null (or absent) means "leave as it is". To empty a field send an empty string
 * (texts, [rrule]) or an empty list ([alertMinutes]); enums and booleans always take a value. The change is applied
 * whole or not at all, and it never deletes.
 *
 * - [start] and [end], when present, are `YYYY-MM-DDTHH:mm`. An event always has an end, so an empty [end] is
 *   rejected. A new [start] without an [end] keeps the event's length. A patch that only flips [allDay] needs no
 *   other field.
 * - [rrule] makes the event repeat, changes its rule, or with an empty string stops it repeating. That reaches more
 *   than one occurrence, so [rrule] needs [span] [EventSpan.ALL] or [EventSpan.FOLLOWING] (on an event that does not
 *   repeat, [EventSpan.ALL]); with [EventSpan.THIS] it is rejected.
 * - [calendarId] moves the event to that calendar. It must not be blank.
 * - A change of [calendarId] or [start] can give the event new ids: use the `occurrenceId` and `eventId` of the
 *   answer from then on.
 */
@Serializable
public data class UpdateEventRequest(
    public val occurrenceId: String,
    public val span: EventSpan = EventSpan.THIS,
    public val title: String? = null,
    public val start: String? = null,
    public val end: String? = null,
    public val allDay: Boolean? = null,
    public val calendarId: String? = null,
    public val location: String? = null,
    public val rrule: String? = null,
    public val alertMinutes: List<Int>? = null,
    public val availability: EventAvailability? = null,
    public val url: String? = null,
    public val notes: String? = null,
) {
    init {
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
        require(start == null || isLocalDateTime(start)) { "start must be a date and time YYYY-MM-DDTHH:mm: '$start'" }
        require(end == null || isLocalDateTime(end)) { "end must be a date and time YYYY-MM-DDTHH:mm: '$end'" }
        require(rrule == null || span != EventSpan.THIS) {
            "rrule reaches more than one occurrence: send it with span all or following"
        }
        require(calendarId == null || calendarId.isNotBlank()) { "calendarId must not be blank" }
    }
}

/**
 * Request of [CalendarCapability.DeleteEvent]: delete the occurrence [occurrenceId], which must not be blank.
 * [span] says how far the deletion reaches in a repeating event and defaults to [EventSpan.THIS], as in
 * [UpdateEventRequest]: on a repeating event, a span the occurrence does not accept, [EventSpan.UNKNOWN] included,
 * fails with `invalid_payload` and deletes nothing.
 */
@Serializable
public data class DeleteEventRequest(
    public val occurrenceId: String,
    public val span: EventSpan = EventSpan.THIS,
) {
    init {
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
    }
}

/**
 * Request of [CalendarCapability.CreateCalendar]: a new calendar in the app called [name], in [color]. Both are
 * required, and [color] is the string `#RRGGBB`.
 */
@Serializable
public data class CreateCalendarRequest(public val name: String, public val color: String) {
    init {
        requireColor(color)
    }
}

/**
 * Request of [CalendarCapability.UpdateCalendar]: a patch of the calendar [calendarId], which must not be blank.
 * [name], [color] (`#RRGGBB`) and [visible] change when present; null (or absent) leaves them as they are.
 */
@Serializable
public data class UpdateCalendarRequest(
    public val calendarId: String,
    public val name: String? = null,
    public val color: String? = null,
    public val visible: Boolean? = null,
) {
    init {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
        if (color != null) requireColor(color)
    }
}

/** Request of [CalendarCapability.DeleteCalendar]: the calendar [calendarId], which must not be blank. */
@Serializable
public data class CalendarIdRequest(public val calendarId: String) {
    init {
        require(calendarId.isNotBlank()) { "calendarId must not be blank" }
    }
}

/**
 * Request of [CalendarCapability.UpdateSettings]: a patch of the settings of the section. [defaultCalendarId] (not
 * blank) and [showReminders] change when present; null (or absent) leaves them as they are.
 */
@Serializable
public data class UpdateCalendarSettingsRequest(
    public val defaultCalendarId: String? = null,
    public val showReminders: Boolean? = null,
) {
    init {
        require(defaultCalendarId == null || defaultCalendarId.isNotBlank()) { "defaultCalendarId must not be blank" }
    }
}
