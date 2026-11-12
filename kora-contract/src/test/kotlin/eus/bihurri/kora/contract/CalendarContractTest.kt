package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.calendar.CalendarCapability
import eus.bihurri.kora.contract.calendar.CalendarIdRequest
import eus.bihurri.kora.contract.calendar.CalendarOverviewRequest
import eus.bihurri.kora.contract.calendar.CreateCalendarRequest
import eus.bihurri.kora.contract.calendar.CreateEventRequest
import eus.bihurri.kora.contract.calendar.DeleteEventRequest
import eus.bihurri.kora.contract.calendar.EventAvailability
import eus.bihurri.kora.contract.calendar.EventDetail
import eus.bihurri.kora.contract.calendar.EventSpan
import eus.bihurri.kora.contract.calendar.ListEventsRequest
import eus.bihurri.kora.contract.calendar.OccurrenceIdRequest
import eus.bihurri.kora.contract.calendar.UpdateCalendarRequest
import eus.bihurri.kora.contract.calendar.UpdateCalendarSettingsRequest
import eus.bihurri.kora.contract.calendar.UpdateEventRequest
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The wire-format checks of the `calendar` requests, and what they leave to the provider. */
class CalendarContractTest {
    private fun rejected(build: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    private fun event(start: String = "2026-11-10T17:30", end: String = "") = CreateEventRequest("Dentist", start, end)

    // ---- The capability ---------------------------------------------------------------------------------------

    @Test
    fun `calendar has ten operations, no event, and separate read and write scopes`() {
        assertEquals(
            listOf(
                "get_overview", "list_events", "get_event", "create_event", "update_event", "delete_event",
                "create_calendar", "update_calendar", "delete_calendar", "update_settings",
            ),
            CalendarCapability.operations.map { it.name },
        )
        assertEquals(emptyList<String>(), CalendarCapability.events.map { it.name })
        assertEquals(CapabilityDescriptor("calendar", CapabilityVersion(1, 0)), CalendarCapability.descriptor())
        assertEquals(Scope("calendar.read"), CalendarCapability.ReadScope)
        assertEquals(Scope("calendar.write"), CalendarCapability.WriteScope)
        assertEquals(
            listOf("get_overview", "list_events", "get_event"),
            CalendarCapability.operations.filter { it.scope == CalendarCapability.ReadScope }.map { it.name },
        )
        assertTrue(CalendarCapability.operations.all { it.scope in setOf(CalendarCapability.ReadScope, CalendarCapability.WriteScope) })
    }

    // ---- Ids --------------------------------------------------------------------------------------------------

    @Test
    fun `a request rejects a blank id`() {
        assertEquals("o-1", OccurrenceIdRequest("o-1").occurrenceId)
        rejected { OccurrenceIdRequest("") }
        rejected { OccurrenceIdRequest("  ") }
        rejected { UpdateEventRequest(" ") }
        rejected { DeleteEventRequest("") }
        assertEquals("c-1", CalendarIdRequest("c-1").calendarId)
        rejected { CalendarIdRequest(" ") }
        assertEquals("c-1", UpdateCalendarRequest("c-1").calendarId)
        rejected { UpdateCalendarRequest("") }
    }

    @Test
    fun `an optional id is absent or not blank`() {
        assertEquals("c-1", event().copy(calendarId = "c-1").calendarId)
        rejected { event().copy(calendarId = " ") }
        assertEquals("c-2", UpdateEventRequest("o-1", calendarId = "c-2").calendarId)
        rejected { UpdateEventRequest("o-1", calendarId = "") }
        assertEquals("c-1", UpdateCalendarSettingsRequest(defaultCalendarId = "c-1").defaultCalendarId)
        rejected { UpdateCalendarSettingsRequest(defaultCalendarId = "") }
        assertEquals(listOf("c-1", "c-2"), ListEventsRequest("2026-11-10", "2026-11-16", listOf("c-1", "c-2")).calendarIds)
        rejected { ListEventsRequest("2026-11-10", "2026-11-16", listOf("c-1", " ")) }
    }

    // ---- Dates ------------------------------------------------------------------------------------------------

    @Test
    fun `the days of a listing are dates YYYY-MM-DD`() {
        assertEquals(ListEventsRequest(from = "2026-11-10", to = "2026-11-10"), ListEventsRequest("2026-11-10", "2026-11-10"))
        for (bad in listOf("", "10/11/2026", "2026-11-1", "2026-13-01", "2026-02-30", "2026-11-10T00:00", "today")) {
            rejected { ListEventsRequest(bad, "2026-11-16") }
            rejected { ListEventsRequest("2026-11-10", bad) }
        }
    }

    @Test
    fun `the start of an event is a date and a time YYYY-MM-DDTHH_mm`() {
        assertEquals("2026-11-10T17:30", event().start)
        assertEquals("2026-11-10T00:00", UpdateEventRequest("o-1", start = "2026-11-10T00:00").start)
        assertEquals(null, UpdateEventRequest("o-1").start)
        for (bad in listOf("", "2026-11-10", "2026-11-10 17:30", "2026-11-10T17:30:00", "2026-11-10T24:00", "2026-02-30T10:00", "17:30")) {
            rejected { event(start = bad) }
            rejected { UpdateEventRequest("o-1", start = bad) }
        }
    }

    @Test
    fun `the end of a new event is a date and a time, or empty for the provider to choose`() {
        assertEquals("", event().end)
        assertEquals("2026-11-10T18:30", event(end = "2026-11-10T18:30").end)
        assertEquals("2026-11-11T09:00", UpdateEventRequest("o-1", end = "2026-11-11T09:00").end)
        for (bad in listOf(" ", "2026-11-10", "2026-11-10T18:3", "2026-11-10T18:60", "later")) {
            rejected { event(end = bad) }
            rejected { UpdateEventRequest("o-1", end = bad) }
        }
    }

    @Test
    fun `a change cannot empty the end, and a new start without an end keeps the length`() {
        rejected { UpdateEventRequest("o-1", end = "") }
        rejectedOnTheWire(CalendarCapability.UpdateEvent, """{"occurrenceId":"o-1","end":""}""")
        val moved = UpdateEventRequest("o-1", start = "2026-11-11T10:00")
        assertEquals(null, moved.end)
        assertEquals(moved, CalendarCapability.UpdateEvent.decodeInput(JsonCodec, """{"occurrenceId":"o-1","start":"2026-11-11T10:00"}""".encodeToByteArray()))
    }

    @Test
    fun `a repetition is made or changed for the whole series or the following occurrences, never for one`() {
        assertEquals("FREQ=DAILY", UpdateEventRequest("o-1", EventSpan.ALL, rrule = "FREQ=DAILY").rrule)
        assertEquals("", UpdateEventRequest("o-1", EventSpan.FOLLOWING, rrule = "").rrule)
        assertEquals(EventSpan.THIS, UpdateEventRequest("o-1", title = "Dentist").span)
        rejected { UpdateEventRequest("o-1", rrule = "FREQ=DAILY") }
        rejected { UpdateEventRequest("o-1", EventSpan.THIS, rrule = "") }
        rejectedOnTheWire(CalendarCapability.UpdateEvent, """{"occurrenceId":"o-1","rrule":"FREQ=WEEKLY"}""")
        rejectedOnTheWire(CalendarCapability.UpdateEvent, """{"occurrenceId":"o-1","span":"this","rrule":""}""")
        // A span this version does not know is the provider's to reject.
        assertEquals(EventSpan.UNKNOWN, UpdateEventRequest("o-1", EventSpan.UNKNOWN, rrule = "FREQ=DAILY").span)
    }

    @Test
    fun `the alerts of a new event are the provider's default unless the caller gives them`() {
        assertEquals(null, event().alertMinutes)
        assertEquals(emptyList<Int>(), event().copy(alertMinutes = emptyList()).alertMinutes)
        assertEquals(listOf(15), event().copy(alertMinutes = listOf(15)).alertMinutes)
        val spec = CalendarCapability.CreateEvent
        val none = """{"title":"Dentist","start":"2026-11-10T17:30","alertMinutes":[]}"""
        assertEquals(emptyList<Int>(), spec.decodeInput(JsonCodec, none.encodeToByteArray()).alertMinutes)
        val given = event().copy(alertMinutes = listOf(15, -540))
        assertEquals(given, spec.decodeInput(CborCodec, spec.encodeInput(CborCodec, given)))
    }

    @Test
    fun `an event carries the time zone its repetition is read in`() {
        val stored = """{"occurrenceId":"o-1","eventId":"e-1","calendarId":"c-1","title":"t","location":"","allDay":false,""" +
            """"start":"2026-11-10T17:30","end":"2026-11-10T18:30","timeZone":"Europe/Madrid","repeats":true,""" +
            """"rrule":"FREQ=DAILY;UNTIL=20261231T235959","spans":["this","all"],"alertMinutes":[],"availability":"busy",""" +
            """"url":"","notes":"","editable":true}"""
        assertEquals("Europe/Madrid", CalendarCapability.GetEvent.decodeOutput(JsonCodec, stored.encodeToByteArray()).timeZone)
        assertTrue("timeZone" in EventDetail.serializer().descriptor.elementNames.toList())
        assertFalse("attachments" in EventDetail.serializer().descriptor.elementNames.toList())
    }

    @Test
    fun `deleting a calendar says that its events go with it`() {
        assertTrue("events" in CalendarCapability.DeleteCalendar.description, CalendarCapability.DeleteCalendar.description)
    }

    // ---- Listings ---------------------------------------------------------------------------------------------

    @Test
    fun `a listing takes a limit from 1 to 50, 20 by default, and an offset that is not negative`() {
        fun list(limit: Int = CalendarCapability.DEFAULT_LIMIT, offset: Int = 0) =
            ListEventsRequest("2026-11-10", "2026-11-16", limit = limit, offset = offset)

        assertEquals(20, CalendarCapability.DEFAULT_LIMIT)
        assertEquals(50, CalendarCapability.MAX_LIMIT)
        assertEquals(list(20, 0), ListEventsRequest("2026-11-10", "2026-11-16"))
        assertEquals(CalendarOverviewRequest(20, 0), CalendarOverviewRequest())
        assertEquals(1, list(limit = 1).limit)
        assertEquals(50, CalendarOverviewRequest(limit = 50, offset = 3).limit)
        rejected { list(limit = 0) }
        rejected { list(limit = 51) }
        rejected { list(offset = -1) }
        rejected { CalendarOverviewRequest(limit = 0) }
        rejected { CalendarOverviewRequest(limit = 51) }
        rejected { CalendarOverviewRequest(offset = -1) }
    }

    // ---- Colours ----------------------------------------------------------------------------------------------

    @Test
    fun `a calendar colour is the string #RRGGBB`() {
        assertEquals("#1A73E8", CreateCalendarRequest("Work", "#1A73E8").color)
        assertEquals("#ff00aa", UpdateCalendarRequest("c-1", color = "#ff00aa").color)
        assertEquals(null, UpdateCalendarRequest("c-1", name = "Home").color)
        for (bad in listOf("", "blue", "1A73E8", "#1A73E", "#1A73E8FF", "#GGGGGG", "# 1A73E")) {
            rejected { CreateCalendarRequest("Work", bad) }
            rejected { UpdateCalendarRequest("c-1", color = bad) }
        }
    }

    // ---- Spans, defaults and patches --------------------------------------------------------------------------

    @Test
    fun `span defaults to this, in a change and in a deletion`() {
        assertEquals(EventSpan.THIS, UpdateEventRequest("o-1").span)
        assertEquals(EventSpan.THIS, DeleteEventRequest("o-1").span)
        assertEquals(
            EventSpan.THIS,
            CalendarCapability.DeleteEvent.decodeInput(JsonCodec, """{"occurrenceId":"o-1"}""".encodeToByteArray()).span,
        )
        assertEquals(
            DeleteEventRequest("o-1", EventSpan.FOLLOWING),
            CalendarCapability.DeleteEvent.decodeInput(JsonCodec, """{"occurrenceId":"o-1","span":"following"}""".encodeToByteArray()),
        )
        assertEquals("""{"occurrenceId":"o-1","span":"all"}""", CalendarCapability.DeleteEvent.encodeInput(JsonCodec, DeleteEventRequest("o-1", EventSpan.ALL)).decodeToString())
    }

    @Test
    fun `a new event needs a title and a start and the rest takes the documented defaults`() {
        val spec = CalendarCapability.CreateEvent
        val decoded = spec.decodeInput(JsonCodec, """{"title":"Dentist","start":"2026-11-10T17:30"}""".encodeToByteArray())

        assertEquals(event(), decoded)
        assertEquals(
            """{"title":"Dentist","start":"2026-11-10T17:30","end":"","allDay":false,"calendarId":null,"location":"",""" +
                """"rrule":"","alertMinutes":null,"availability":"busy","url":"","notes":""}""",
            spec.encodeInput(JsonCodec, decoded).decodeToString(),
        )
        undecodable(spec, """{"title":"Dentist"}""")
        rejectedOnTheWire(spec, """{"title":"Dentist","start":""}""")
    }

    @Test
    fun `a change is a patch in which only the id is required`() {
        val spec = CalendarCapability.UpdateEvent
        val untouched = spec.decodeInput(JsonCodec, """{"occurrenceId":"o-1"}""".encodeToByteArray())

        assertEquals(UpdateEventRequest("o-1"), untouched)
        assertEquals(
            List<Any?>(11) { null },
            with(untouched) { listOf(title, start, end, allDay, calendarId, location, rrule, alertMinutes, availability, url, notes) },
        )
        val flipped = UpdateEventRequest("o-1", EventSpan.ALL, allDay = true, alertMinutes = emptyList(), rrule = "")
        assertEquals(flipped, spec.decodeInput(JsonCodec, spec.encodeInput(JsonCodec, flipped)))
        assertEquals(flipped, spec.decodeInput(CborCodec, spec.encodeInput(CborCodec, flipped)))
        assertEquals(UpdateCalendarRequest("c-1", null, null, null), UpdateCalendarRequest("c-1"))
        assertEquals(UpdateCalendarSettingsRequest(null, null), UpdateCalendarSettingsRequest())
    }

    @Test
    fun `the rules of the domain are left to the provider`() {
        assertEquals("", CreateEventRequest("", "2026-11-10T17:30").title)
        assertEquals("2026-11-10T09:00", event(end = "2026-11-10T09:00").end)
        assertEquals("FREQ=SOMETIMES", event().copy(rrule = "FREQ=SOMETIMES").rrule)
        assertEquals(listOf(-540, 15), event().copy(alertMinutes = listOf(-540, 15)).alertMinutes)
        assertEquals("2026-11-01", ListEventsRequest("2026-11-10", "2026-11-01").to)
        assertEquals("", CreateCalendarRequest("", "#000000").name)
    }

    // ---- The wire ---------------------------------------------------------------------------------------------

    @Test
    fun `a request decoded from the wire is validated too`() {
        rejectedOnTheWire(CalendarCapability.GetEvent, """{"occurrenceId":" "}""")
        rejectedOnTheWire(CalendarCapability.ListEvents, """{"from":"2026-11-10","to":"16/11/2026"}""")
        rejectedOnTheWire(CalendarCapability.ListEvents, """{"from":"2026-11-10","to":"2026-11-16","limit":0}""")
        rejectedOnTheWire(CalendarCapability.CreateEvent, """{"title":"t","start":"2026-11-10"}""")
        rejectedOnTheWire(CalendarCapability.UpdateEvent, """{"occurrenceId":"o-1","end":"soon"}""")
        rejectedOnTheWire(CalendarCapability.CreateCalendar, """{"name":"Work","color":"blue"}""")
        rejectedOnTheWire(CalendarCapability.UpdateSettings, """{"defaultCalendarId":""}""")
        assertEquals(
            ListEventsRequest("2026-11-10", "2026-11-16", listOf("c-1"), limit = 5, offset = 10),
            CalendarCapability.ListEvents.decodeInput(
                JsonCodec,
                """{"from":"2026-11-10","to":"2026-11-16","calendarIds":["c-1"],"limit":5,"offset":10}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `the enums travel as snake_case and read constants they do not know as unknown`() {
        assertEquals(listOf("busy", "free"), WireEnums.allowedValues(EventAvailability.serializer().descriptor))
        assertEquals(listOf("this", "following", "all"), WireEnums.allowedValues(EventSpan.serializer().descriptor))
        val future = """{"occurrenceId":"o-1","eventId":"e-1","calendarId":"c-1","title":"t","location":"","allDay":false,""" +
            """"start":"2026-11-10T17:30","end":"2026-11-10T18:30","timeZone":"Europe/Madrid","repeats":true,"rrule":"FREQ=DAILY",""" +
            """"spans":["this","this_month","all"],"alertMinutes":[],"availability":"tentative","url":"","notes":"","editable":true}"""
        val detail = CalendarCapability.GetEvent.decodeOutput(JsonCodec, future.encodeToByteArray())
        assertEquals(listOf(EventSpan.THIS, EventSpan.UNKNOWN, EventSpan.ALL), detail.spans)
        assertEquals(EventAvailability.UNKNOWN, detail.availability)
    }

    @Test
    fun `answers carry no checks`() {
        val odd = """{"id":"","name":"","accountName":"","accountType":"","color":"teal","visible":true,"writable":false,"local":false}"""
        assertEquals("teal", CalendarCapability.CreateCalendar.decodeOutput(JsonCodec, odd.encodeToByteArray()).color)
    }
}
