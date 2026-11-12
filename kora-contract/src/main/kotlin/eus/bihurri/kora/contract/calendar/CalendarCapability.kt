package eus.bihurri.kora.contract.calendar

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.Page
import kotlinx.serialization.builtins.serializer

/**
 * The calendar of the provider app: the calendars of every account on the phone, events, repetition, alerts and the
 * settings of the section. The vocabulary is app-neutral; what an event does stays in the app that provides it.
 *
 * - **Scopes and effects.** Reads need [ReadScope] and changes [WriteScope]. The reads are [OperationEffect.READ];
 *   [DeleteEvent] and [DeleteCalendar] lose something for good and are [OperationEffect.DESTRUCTIVE]; every other
 *   change is [OperationEffect.WRITE].
 * - **Occurrences.** One occurrence of a repeating event is named by an `occurrenceId`, an opaque string the
 *   provider makes. Listings give it, and reading, changing and deleting take it, so a caller never handles an
 *   instant.
 * - **`span`.** A change or a deletion says how far it reaches with [EventSpan]. It **defaults to
 *   [EventSpan.THIS]** on purpose: a caller that forgets the field changes one occurrence and not every one.
 *   [EventDetail.spans] lists the spans an occurrence accepts (none for an event that does not repeat); on a
 *   repeating event a span outside the list fails with `invalid_payload`. A change with [EventSpan.THIS] or [EventSpan.FOLLOWING] can make
 *   a new event, and the answer carries the `occurrenceId` and `eventId` to use from then on. A repetition is made,
 *   changed or stopped only with [EventSpan.ALL] or [EventSpan.FOLLOWING]: an `rrule` with [EventSpan.THIS] is
 *   rejected.
 * - **Ids change.** Any change of an event's calendar or start can give it new ids. Use the ids of the answer;
 *   earlier ones may no longer resolve.
 * - **Dates.** `start` and `end` are always a local date and time, `YYYY-MM-DDTHH:mm`; for an all-day event only
 *   their days count, both included. They have no zone: they mean the wall clock where the user is. A wall time a
 *   clock change skips means the same time after the change, later by the length of the gap; one that happens
 *   twice means the first of the two. `rrule` is an exact RFC 5545 `RRULE` value without the prefix; the provider
 *   may rewrite it in a canonical form, and the answer carries the rule as stored. An event carries the IANA time
 *   zone its rule is read in ([EventDetail.timeZone]), and a `UNTIL` in the rule is read as in RFC 5545:
 *   ending in `Z` it is UTC, otherwise a time in that zone. A new event takes the phone's time zone.
 * - **Create and change.** [CreateEvent] and [UpdateEvent] answer the resulting [EventDetail]. [UpdateEvent],
 *   [UpdateCalendar] and [UpdateSettings] are patches: null or absent leaves a field as it is, and a change is
 *   applied whole or not at all. A change never deletes. A provider whose calendar has no field for an event's `url`
 *   may keep it as the last line of the event's notes, as the phone's calendar does.
 * - **Listings** are paged with `limit` (default [DEFAULT_LIMIT], at most [MAX_LIMIT]) and `offset`.
 * - **Failures.** A malformed request, an id the provider does not have and a change the provider's rules reject
 *   are `invalid_payload`. When the app may not read the phone's calendars, [GetOverview] says so
 *   ([CalendarOverview.accessGranted]) and the other operations answer `provider_unavailable`.
 *
 * The reminders a calendar screen shows are reminders: a caller reads and completes them through the `reminders`
 * capability. Here only the switch that shows them is exposed ([CalendarOverview.showReminders]).
 *
 * Version history:
 * - 1.0: [GetOverview], [ListEvents], [GetEvent], [CreateEvent], [UpdateEvent], [DeleteEvent], [CreateCalendar],
 *   [UpdateCalendar], [DeleteCalendar], [UpdateSettings].
 */
public object CalendarCapability : Capability(CapabilityId("calendar"), CapabilityVersion(1, 0)) {
    /** `limit` of a listing when the caller does not choose: 20 items, which fit the size an assistant shows. */
    public const val DEFAULT_LIMIT: Int = 20

    /** Largest `limit` of a listing. */
    public const val MAX_LIMIT: Int = 50

    public val ReadScope: Scope = Scope("calendar.read")

    public val WriteScope: Scope = Scope("calendar.write")

    public val GetOverview: OperationSpec<CalendarOverviewRequest, CalendarOverview> = operation(
        name = "get_overview",
        input = CalendarOverviewRequest.serializer(),
        output = CalendarOverview.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get the calendars of every account, the default calendar and the settings. limit and offset " +
            "page the calendars; accessGranted false means the app may not read the phone's calendars.",
    )

    public val ListEvents: OperationSpec<ListEventsRequest, Page<EventOccurrence>> = operation(
        name = "list_events",
        input = ListEventsRequest.serializer(),
        output = Page.serializer(EventOccurrence.serializer()),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "List the event occurrences that touch the days from..to (YYYY-MM-DD, both included), in the " +
            "order they start, one page at a time. calendarIds narrows the visible calendars to those.",
    )

    public val GetEvent: OperationSpec<OccurrenceIdRequest, EventDetail> = operation(
        name = "get_event",
        input = OccurrenceIdRequest.serializer(),
        output = EventDetail.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get one event occurrence with all its fields: repetition rule and its time zone, alerts, " +
            "notes and the spans a change of it accepts.",
    )

    public val CreateEvent: OperationSpec<CreateEventRequest, EventDetail> = operation(
        name = "create_event",
        input = CreateEventRequest.serializer(),
        output = EventDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Create an event, in the default calendar unless calendarId is given. start and end are " +
            "YYYY-MM-DDTHH:mm in the user's local time; rrule is an RFC 5545 RRULE value without the prefix. " +
            "Without alertMinutes the calendar's default alerts apply; an empty list means none.",
    )

    public val UpdateEvent: OperationSpec<UpdateEventRequest, EventDetail> = operation(
        name = "update_event",
        input = UpdateEventRequest.serializer(),
        output = EventDetail.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Change an event occurrence: only the fields sent change. In a repeating event span says how " +
            "far the change reaches: this (the default), following or all. rrule needs span all or following. A " +
            "new start without end keeps the length. Use the ids of the answer from then on.",
    )

    public val DeleteEvent: OperationSpec<DeleteEventRequest, Unit> = operation(
        name = "delete_event",
        input = DeleteEventRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete an event occurrence for good. In a repeating event span says how far the deletion " +
            "reaches: this (the default), following or all.",
    )

    public val CreateCalendar: OperationSpec<CreateCalendarRequest, CalendarSummary> = operation(
        name = "create_calendar",
        input = CreateCalendarRequest.serializer(),
        output = CalendarSummary.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Create a calendar in the app with a name and a colour (#RRGGBB).",
    )

    public val UpdateCalendar: OperationSpec<UpdateCalendarRequest, CalendarSummary> = operation(
        name = "update_calendar",
        input = UpdateCalendarRequest.serializer(),
        output = CalendarSummary.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Rename, recolour, show or hide a calendar; only the fields sent change. Only a calendar made " +
            "in the app (local) is renamed and recoloured.",
    )

    public val DeleteCalendar: OperationSpec<CalendarIdRequest, Unit> = operation(
        name = "delete_calendar",
        input = CalendarIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Delete a calendar made in the app (local), with all its events, for good.",
    )

    public val UpdateSettings: OperationSpec<UpdateCalendarSettingsRequest, CalendarOverview> = operation(
        name = "update_settings",
        input = UpdateCalendarSettingsRequest.serializer(),
        output = CalendarOverview.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Change the settings of the calendar: the default calendar and whether the reminders are " +
            "shown in it. Only the fields sent change.",
    )
}
