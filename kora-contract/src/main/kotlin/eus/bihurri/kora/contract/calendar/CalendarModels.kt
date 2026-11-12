package eus.bihurri.kora.contract.calendar

import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import kotlinx.serialization.Serializable

/*
 * Enums and answer types of the calendar capability. Every id is an opaque string chosen by the provider. `start`
 * and `end` are always a local date and time `YYYY-MM-DDTHH:mm`, without a zone: the wall clock where the user is.
 * Answer types carry no checks, so a newer provider never breaks an older reader.
 */

/** Whether an event blocks the user's time. A constant this version lacks reads as [UNKNOWN]. */
@Serializable(with = EventAvailability.Serializer::class)
public enum class EventAvailability(override val wireName: String) : WireEnum {
    BUSY("busy"),
    FREE("free"),

    /** An availability this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<EventAvailability>(
        "eus.bihurri.kora.contract.calendar.EventAvailability",
        entries,
        UNKNOWN,
    )
}

/**
 * How far a change or a deletion of one occurrence of a repeating event reaches: only that occurrence ([THIS]), it
 * and the ones after it ([FOLLOWING]), or the whole series ([ALL]). On an event that does not repeat it is ignored,
 * except that an `rrule` there, which makes the event repeat, needs [ALL]. A constant this version lacks reads as
 * [UNKNOWN].
 */
@Serializable(with = EventSpan.Serializer::class)
public enum class EventSpan(override val wireName: String) : WireEnum {
    THIS("this"),
    FOLLOWING("following"),
    ALL("all"),

    /** A span this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<EventSpan>(
        "eus.bihurri.kora.contract.calendar.EventSpan",
        entries,
        UNKNOWN,
    )
}

/**
 * One calendar of an account on the phone, or one made in the provider app.
 *
 * [color] is the string `#RRGGBB`: account calendars come in any colour, so it is not an enum. [visible] says
 * whether its events are shown and [writable] whether events can be created and changed in it. [local] marks a
 * calendar made in the app: only these are renamed, recoloured and deleted.
 */
@Serializable
public data class CalendarSummary(
    public val id: String,
    public val name: String,
    public val accountName: String,
    public val accountType: String,
    public val color: String,
    public val visible: Boolean,
    public val writable: Boolean,
    public val local: Boolean,
)

/**
 * Answer of [CalendarCapability.GetOverview] and of [CalendarCapability.UpdateSettings]: the calendars and the
 * settings of the section.
 *
 * - [accessGranted] is false when the app may not read the phone's calendars: then [calendars] is empty and the
 *   other operations answer `provider_unavailable`.
 * - [calendars] is one window of the whole collection, cut with the request's `limit` and `offset` (the default
 *   window after [CalendarCapability.UpdateSettings], which takes neither); [calendarCount] is the size of the whole
 *   collection.
 * - [defaultCalendarId] is the calendar an event created without a `calendarId` goes to, or null when there is none.
 * - [showReminders] is the switch that shows the user's reminders in the calendar. The reminders themselves are read
 *   and completed through the `reminders` capability.
 */
@Serializable
public data class CalendarOverview(
    public val accessGranted: Boolean,
    public val calendars: List<CalendarSummary>,
    public val calendarCount: Int,
    public val defaultCalendarId: String? = null,
    public val showReminders: Boolean,
)

/**
 * One occurrence of an event in a listing ([CalendarCapability.ListEvents]).
 *
 * [occurrenceId] is an opaque string the provider makes to name this occurrence; reading, changing and deleting
 * take it, so a caller never handles an instant. [eventId] names the event the occurrence belongs to.
 * [start] and [end] are `YYYY-MM-DDTHH:mm`; for an [allDay] event only their days count, both included. [repeats]
 * says whether the event has a repetition; the rule itself is in [EventDetail].
 */
@Serializable
public data class EventOccurrence(
    public val occurrenceId: String,
    public val eventId: String,
    public val calendarId: String,
    public val title: String,
    public val location: String,
    public val allDay: Boolean,
    public val start: String,
    public val end: String,
    public val repeats: Boolean,
)

/**
 * One occurrence of an event with every field: the fields of [EventOccurrence] plus its time zone, repetition,
 * alerts, availability, [url] and [notes]. Every create and change of an event answers it, so the caller sees what
 * was stored.
 *
 * - **Use the ids of the answer.** A change with [EventSpan.THIS] or [EventSpan.FOLLOWING] can make a new event, and
 *   any change of the calendar or of the start can give the event new ids. The answer carries the [occurrenceId] and
 *   [eventId] to use from then on; earlier ones may no longer resolve.
 * - [timeZone] is the IANA id of the zone the event's rule is read in, for example `Europe/Madrid`. [start] and [end]
 *   stay the wall clock where the user is.
 * - [rrule] is an iCalendar `RRULE` value (RFC 5545) without the `RRULE:` prefix, as the provider stored it, which
 *   may be a canonical rewrite of the rule the caller sent; a `UNTIL` in it ending in `Z` is UTC (RFC 5545), otherwise a
 *   time in [timeZone]. Empty means the event does not repeat.
 * - [spans] lists the spans this occurrence accepts in a change or a deletion. It is empty for an event that does
 *   not repeat, and a rule kept as another calendar wrote it accepts only [EventSpan.THIS] and [EventSpan.ALL]. On
 *   a repeating event a span outside the list fails with `invalid_payload`; on one that does not repeat the list is
 *   not checked ([EventSpan]).
 * - [alertMinutes] are minutes before the start; a negative value is after it.
 * - [url] is the event's link; a provider whose calendar has no field for it may also keep it as the last line of
 *   [notes].
 * - [editable] says whether the event can be changed and deleted.
 */
@Serializable
public data class EventDetail(
    public val occurrenceId: String,
    public val eventId: String,
    public val calendarId: String,
    public val title: String,
    public val location: String,
    public val allDay: Boolean,
    public val start: String,
    public val end: String,
    public val timeZone: String,
    public val repeats: Boolean,
    public val rrule: String,
    public val spans: List<EventSpan>,
    public val alertMinutes: List<Int>,
    public val availability: EventAvailability,
    public val url: String,
    public val notes: String,
    public val editable: Boolean,
)
