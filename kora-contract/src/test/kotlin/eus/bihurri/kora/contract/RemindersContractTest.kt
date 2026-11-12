package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.reminders.CompleteReminderRequest
import eus.bihurri.kora.contract.reminders.CreateListRequest
import eus.bihurri.kora.contract.reminders.CreateReminderRequest
import eus.bihurri.kora.contract.reminders.EarlyAlertUnit
import eus.bihurri.kora.contract.reminders.ListColor
import eus.bihurri.kora.contract.reminders.ListRemindersRequest
import eus.bihurri.kora.contract.reminders.ReminderIdRequest
import eus.bihurri.kora.contract.reminders.ReminderListIdRequest
import eus.bihurri.kora.contract.reminders.ReminderPriority
import eus.bihurri.kora.contract.reminders.RemindersCapability
import eus.bihurri.kora.contract.reminders.RemindersOverviewRequest
import eus.bihurri.kora.contract.reminders.RenameTagRequest
import eus.bihurri.kora.contract.reminders.RepeatDayKind
import eus.bihurri.kora.contract.reminders.RepeatOrdinal
import eus.bihurri.kora.contract.reminders.RepeatUnit
import eus.bihurri.kora.contract.reminders.SmartList
import eus.bihurri.kora.contract.reminders.Snooze
import eus.bihurri.kora.contract.reminders.SnoozeReminderRequest
import eus.bihurri.kora.contract.reminders.TagRequest
import eus.bihurri.kora.contract.reminders.UpdateListRequest
import eus.bihurri.kora.contract.reminders.UpdateReminderRequest
import eus.bihurri.kora.contract.reminders.Weekday
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The wire-format checks of the `reminders` requests, and what they leave to the provider. */
class RemindersContractTest {
    private fun rejected(build: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    // ---- The capability ---------------------------------------------------------------------------------------

    @Test
    fun `reminders has fourteen operations, no event, and separate read and write scopes`() {
        assertEquals(
            listOf(
                "get_overview", "list_reminders", "get_reminder", "create_reminder", "update_reminder",
                "complete_reminder", "reopen_reminder", "snooze_reminder", "delete_reminder", "create_list",
                "update_list", "delete_list", "rename_tag", "delete_tag",
            ),
            RemindersCapability.operations.map { it.name },
        )
        assertEquals(emptyList<String>(), RemindersCapability.events.map { it.name })
        assertEquals(CapabilityDescriptor("reminders", CapabilityVersion(1, 0)), RemindersCapability.descriptor())
        assertEquals(Scope("reminders.read"), RemindersCapability.ReadScope)
        assertEquals(Scope("reminders.write"), RemindersCapability.WriteScope)
        assertEquals(
            listOf("get_overview", "list_reminders", "get_reminder"),
            RemindersCapability.operations.filter { it.scope == RemindersCapability.ReadScope }.map { it.name },
        )
        assertTrue(RemindersCapability.operations.all { it.scope in setOf(RemindersCapability.ReadScope, RemindersCapability.WriteScope) })
    }

    // ---- Ids --------------------------------------------------------------------------------------------------

    @Test
    fun `a request rejects a blank id`() {
        assertEquals("r-1", ReminderIdRequest("r-1").reminderId)
        rejected { ReminderIdRequest("") }
        rejected { ReminderIdRequest("  ") }
        rejected { CompleteReminderRequest(" ") }
        rejected { SnoozeReminderRequest(" ", Snooze.TOMORROW, "2026-11-10") }
        rejected { UpdateReminderRequest("") }
        assertEquals("l-1", ReminderListIdRequest("l-1").listId)
        rejected { ReminderListIdRequest(" ") }
        assertEquals("l-1", UpdateListRequest("l-1").listId)
        rejected { UpdateListRequest("") }
    }

    @Test
    fun `an optional id is absent or not blank`() {
        assertEquals("l-1", CreateReminderRequest("Buy milk", listId = "l-1").listId)
        assertEquals("r-1", CreateReminderRequest("Buy milk", parentId = "r-1").parentId)
        rejected { CreateReminderRequest("Buy milk", listId = "") }
        rejected { CreateReminderRequest("Buy milk", parentId = " ") }
        assertEquals("l-2", UpdateReminderRequest("r-1", listId = "l-2").listId)
        rejected { UpdateReminderRequest("r-1", listId = "") }
        rejected { ListRemindersRequest(listId = " ") }
        rejected { ListRemindersRequest(parentId = "") }
    }

    @Test
    fun `a tag is named by its text, which is not blank`() {
        assertEquals(RenameTagRequest("home", "house"), RenameTagRequest(tag = "home", newName = "house"))
        rejected { RenameTagRequest(" ", "house") }
        assertEquals("home", TagRequest("home").tag)
        rejected { TagRequest("") }
    }

    // ---- Dates and times --------------------------------------------------------------------------------------

    @Test
    fun `a due date is YYYY-MM-DD or empty`() {
        assertEquals("2026-11-10", CreateReminderRequest("t", dueDate = "2026-11-10").dueDate)
        assertEquals("", CreateReminderRequest("t").dueDate)
        assertEquals("", UpdateReminderRequest("r-1", dueDate = "").dueDate)
        assertEquals("2028-02-29", UpdateReminderRequest("r-1", dueDate = "2028-02-29").dueDate)
        assertEquals("2026-11-10", CompleteReminderRequest("r-1", "2026-11-10").dueDate)
        for (bad in listOf("10/11/2026", "2026-11-1", "2026-13-01", "2026-02-30", "2026-11-10T17:30", "+2026-11-10", " ", "tomorrow")) {
            rejected { CreateReminderRequest("t", dueDate = bad) }
            rejected { UpdateReminderRequest("r-1", dueDate = bad) }
            rejected { CompleteReminderRequest("r-1", dueDate = bad) }
            rejected { SnoozeReminderRequest("r-1", Snooze.IN_ONE_HOUR, bad) }
        }
    }

    @Test
    fun `a due time is HH_mm or empty`() {
        assertEquals("17:30", CreateReminderRequest("t", dueDate = "2026-11-10", dueTime = "17:30").dueTime)
        assertEquals("00:00", UpdateReminderRequest("r-1", dueTime = "00:00").dueTime)
        assertEquals("", UpdateReminderRequest("r-1", dueTime = "").dueTime)
        assertEquals("23:59", CompleteReminderRequest("r-1", "2026-11-10", "23:59").dueTime)
        assertEquals("09:05", SnoozeReminderRequest("r-1", Snooze.TOMORROW, "2026-11-10", "09:05").dueTime)
        for (bad in listOf("5:30", "17:30:00", "24:00", "17:60", "1730", "5pm", " ")) {
            rejected { CreateReminderRequest("t", dueTime = bad) }
            rejected { UpdateReminderRequest("r-1", dueTime = bad) }
            rejected { CompleteReminderRequest("r-1", dueTime = bad) }
            rejected { SnoozeReminderRequest("r-1", Snooze.TOMORROW, "2026-11-10", bad) }
        }
    }

    @Test
    fun `the end of a repetition is a date or empty`() {
        assertEquals("2027-01-31", CreateReminderRequest("t", repeatUnit = RepeatUnit.DAY, repeatUntil = "2027-01-31").repeatUntil)
        assertEquals("", UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.DAY, repeatUntil = "").repeatUntil)
        rejected { CreateReminderRequest("t", repeatUnit = RepeatUnit.DAY, repeatUntil = "31-01-2027") }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.DAY, repeatUntil = "never") }
    }

    @Test
    fun `snoozing names the occurrence, so its due date is required`() {
        assertEquals("2026-11-10", SnoozeReminderRequest("r-1", Snooze.IN_ONE_HOUR, "2026-11-10").dueDate)
        rejected { SnoozeReminderRequest("r-1", Snooze.IN_ONE_HOUR, "") }
        undecodable(RemindersCapability.SnoozeReminder, """{"reminderId":"r-1","snooze":"tomorrow"}""")
        rejectedOnTheWire(RemindersCapability.SnoozeReminder, """{"reminderId":"r-1","snooze":"tomorrow","dueDate":""}""")
    }

    // ---- Listings ---------------------------------------------------------------------------------------------

    @Test
    fun `a listing takes a limit from 1 to 50, 20 by default, and an offset that is not negative`() {
        assertEquals(20, RemindersCapability.DEFAULT_LIMIT)
        assertEquals(50, RemindersCapability.MAX_LIMIT)
        assertEquals(ListRemindersRequest(limit = 20, offset = 0), ListRemindersRequest())
        assertEquals(RemindersOverviewRequest(20, 0), RemindersOverviewRequest())
        assertEquals(1, ListRemindersRequest(limit = 1).limit)
        assertEquals(50, RemindersOverviewRequest(limit = 50, offset = 7).limit)
        rejected { ListRemindersRequest(limit = 0) }
        rejected { ListRemindersRequest(limit = 51) }
        rejected { ListRemindersRequest(offset = -1) }
        rejected { RemindersOverviewRequest(limit = 0) }
        rejected { RemindersOverviewRequest(limit = 51) }
        rejected { RemindersOverviewRequest(offset = -1) }
    }

    @Test
    fun `a listing takes at most one selector`() {
        assertEquals(null, ListRemindersRequest().smartList)
        assertEquals("l-1", ListRemindersRequest(listId = "l-1", includeCompleted = true).listId)
        assertEquals(SmartList.TODAY, ListRemindersRequest(smartList = SmartList.TODAY).smartList)
        assertEquals(listOf("home", "urgent"), ListRemindersRequest(tags = listOf("home", "urgent")).tags)
        assertEquals("r-1", ListRemindersRequest(parentId = "r-1").parentId)
        rejected { ListRemindersRequest(listId = "l-1", smartList = SmartList.FLAGGED) }
        rejected { ListRemindersRequest(listId = "l-1", tags = listOf("home")) }
        rejected { ListRemindersRequest(smartList = SmartList.ALL, parentId = "r-1") }
        rejected { ListRemindersRequest(tags = listOf("home"), parentId = "r-1") }
    }

    // ---- Repetition -------------------------------------------------------------------------------------------

    @Test
    fun `the days of the month of a repetition are within 1 to 31`() {
        assertEquals(listOf(1, 15, 31), CreateReminderRequest("t", repeatUnit = RepeatUnit.MONTH, repeatMonthDays = listOf(1, 15, 31)).repeatMonthDays)
        assertEquals(listOf(31), UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.MONTH, repeatMonthDays = listOf(31)).repeatMonthDays)
        rejected { CreateReminderRequest("t", repeatUnit = RepeatUnit.MONTH, repeatMonthDays = listOf(0)) }
        rejected { CreateReminderRequest("t", repeatUnit = RepeatUnit.MONTH, repeatMonthDays = listOf(1, 32)) }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.MONTH, repeatMonthDays = listOf(-1)) }
    }

    @Test
    fun `the months of a repetition are within 1 to 12`() {
        assertEquals(listOf(1, 12), CreateReminderRequest("t", repeatUnit = RepeatUnit.YEAR, repeatMonths = listOf(1, 12)).repeatMonths)
        assertEquals(listOf(6), UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.YEAR, repeatMonths = listOf(6)).repeatMonths)
        rejected { CreateReminderRequest("t", repeatUnit = RepeatUnit.YEAR, repeatMonths = listOf(0)) }
        rejected { CreateReminderRequest("t", repeatUnit = RepeatUnit.YEAR, repeatMonths = listOf(13)) }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.YEAR, repeatMonths = listOf(12, 13)) }
    }

    @Test
    fun `in a change the repeat fields are one group that needs repeatUnit`() {
        val replaced = UpdateReminderRequest(
            "r-1",
            repeatUnit = RepeatUnit.WEEK,
            repeatInterval = 2,
            repeatWeekdays = listOf(Weekday.MONDAY, Weekday.WEDNESDAY),
            repeatUntil = "2027-06-30",
        )
        assertEquals(RepeatUnit.WEEK, replaced.repeatUnit)
        assertEquals(RepeatUnit.NONE, UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.NONE).repeatUnit)
        rejected { UpdateReminderRequest("r-1", repeatInterval = 2) }
        rejected { UpdateReminderRequest("r-1", repeatWeekdays = listOf(Weekday.FRIDAY)) }
        rejected { UpdateReminderRequest("r-1", repeatMonthDays = listOf(1)) }
        rejected { UpdateReminderRequest("r-1", repeatOrdinal = RepeatOrdinal.LAST) }
        rejected { UpdateReminderRequest("r-1", repeatDayKind = RepeatDayKind.WEEKDAY) }
        rejected { UpdateReminderRequest("r-1", repeatMonths = listOf(1)) }
        rejected { UpdateReminderRequest("r-1", repeatUntil = "") }
        rejected { UpdateReminderRequest("r-1", repeatWeekdays = emptyList()) }
        rejected { UpdateReminderRequest("r-1", repeatFromCompletion = true) }
    }

    @Test
    fun `repeating from the completion is part of the rule, off by default`() {
        assertEquals(false, CreateReminderRequest("t").repeatFromCompletion)
        assertEquals(true, CreateReminderRequest("t", dueDate = "2026-11-10", repeatUnit = RepeatUnit.DAY, repeatFromCompletion = true).repeatFromCompletion)
        assertEquals(null, UpdateReminderRequest("r-1").repeatFromCompletion)
        assertEquals(true, UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.WEEK, repeatFromCompletion = true).repeatFromCompletion)
        assertEquals(
            CreateReminderRequest("t", dueDate = "2026-11-10", repeatUnit = RepeatUnit.DAY, repeatFromCompletion = true),
            RemindersCapability.CreateReminder.decodeInput(
                JsonCodec,
                """{"title":"t","dueDate":"2026-11-10","repeatUnit":"day","repeatFromCompletion":true}""".encodeToByteArray(),
            ),
        )
        rejectedOnTheWire(RemindersCapability.UpdateReminder, """{"reminderId":"r-1","repeatFromCompletion":false}""")
        val stored = """{"id":"r-1","listId":"l-1","title":"t","repeats":true,"flagged":false,"priority":"none",""" +
            """"completed":false,"tags":[],"subtaskCount":0,"attachmentCount":0,"notes":"","repeatUnit":"day",""" +
            """"repeatInterval":1,"repeatWeekdays":[],"repeatMonthDays":[],"repeatOrdinal":"none","repeatDayKind":"day",""" +
            """"repeatMonths":[],"repeatUntil":"","repeatFromCompletion":true,"url":"","earlyAlertAmount":0,""" +
            """"earlyAlertUnit":"minutes","createdAtEpochMs":1}"""
        assertEquals(true, RemindersCapability.GetReminder.decodeOutput(JsonCodec, stored.encodeToByteArray()).repeatFromCompletion)
    }

    // ---- Checks between fields ---------------------------------------------------------------------------------

    @Test
    fun `a new reminder with a time needs a date`() {
        assertEquals("17:30", CreateReminderRequest("t", dueDate = "2026-11-10", dueTime = "17:30").dueTime)
        assertEquals("", CreateReminderRequest("t", dueDate = "2026-11-10").dueTime)
        rejected { CreateReminderRequest("t", dueTime = "17:30") }
        rejectedOnTheWire(RemindersCapability.CreateReminder, """{"title":"t","dueTime":"17:30"}""")
        // In a change the reminder's own date counts, which only the provider knows.
        assertEquals("17:30", UpdateReminderRequest("r-1", dueTime = "17:30").dueTime)
    }

    @Test
    fun `a change that empties the date cannot set a time`() {
        assertEquals("", UpdateReminderRequest("r-1", dueDate = "", dueTime = "").dueTime)
        assertEquals(null, UpdateReminderRequest("r-1", dueDate = "").dueTime)
        rejected { UpdateReminderRequest("r-1", dueDate = "", dueTime = "17:30") }
        rejectedOnTheWire(RemindersCapability.UpdateReminder, """{"reminderId":"r-1","dueDate":"","dueTime":"17:30"}""")
    }

    @Test
    fun `a rule that does not repeat carries no other repeat field`() {
        assertEquals(CreateReminderRequest("t"), CreateReminderRequest("t", repeatUnit = RepeatUnit.NONE))
        val month = RepeatUnit.MONTH
        assertEquals(listOf(1), CreateReminderRequest("t", dueDate = "2026-11-10", repeatUnit = month, repeatMonthDays = listOf(1)).repeatMonthDays)
        rejected { CreateReminderRequest("t", repeatInterval = 2) }
        rejected { CreateReminderRequest("t", repeatWeekdays = listOf(Weekday.MONDAY)) }
        rejected { CreateReminderRequest("t", repeatMonthDays = listOf(1)) }
        rejected { CreateReminderRequest("t", repeatOrdinal = RepeatOrdinal.FIRST, repeatDayKind = RepeatDayKind.WEEKDAY) }
        rejected { CreateReminderRequest("t", repeatOrdinal = RepeatOrdinal.LAST) }
        rejected { CreateReminderRequest("t", repeatMonths = listOf(1)) }
        rejected { CreateReminderRequest("t", repeatUntil = "2027-01-01") }
        rejected { CreateReminderRequest("t", repeatFromCompletion = true) }
        assertEquals(RepeatUnit.NONE, UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.NONE).repeatUnit)
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.NONE, repeatInterval = 1) }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.NONE, repeatWeekdays = emptyList()) }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.NONE, repeatUntil = "") }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.NONE, repeatFromCompletion = false) }
        // A unit this version does not know is the provider's to reject, not this check's.
        assertEquals(2, CreateReminderRequest("t", repeatUnit = RepeatUnit.UNKNOWN, repeatInterval = 2).repeatInterval)
        rejectedOnTheWire(RemindersCapability.CreateReminder, """{"title":"t","repeatInterval":2}""")
        rejectedOnTheWire(RemindersCapability.UpdateReminder, """{"reminderId":"r-1","repeatUnit":"none","repeatMonths":[]}""")
    }

    @Test
    fun `a new subtask goes to the list of its parent, so it names no list`() {
        assertEquals("r-1", CreateReminderRequest("t", parentId = "r-1").parentId)
        assertEquals("l-1", CreateReminderRequest("t", listId = "l-1").listId)
        rejected { CreateReminderRequest("t", listId = "l-1", parentId = "r-1") }
        rejectedOnTheWire(RemindersCapability.CreateReminder, """{"title":"t","listId":"l-1","parentId":"r-1"}""")
    }

    @Test
    fun `a kind of day needs its ordinal`() {
        val rule = CreateReminderRequest(
            "t", dueDate = "2026-11-10", repeatUnit = RepeatUnit.MONTH, repeatOrdinal = RepeatOrdinal.LAST, repeatDayKind = RepeatDayKind.WEEKDAY,
        )
        assertEquals(RepeatDayKind.WEEKDAY, rule.repeatDayKind)
        assertEquals(
            RepeatDayKind.WEEKEND_DAY,
            UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.MONTH, repeatOrdinal = RepeatOrdinal.SECOND, repeatDayKind = RepeatDayKind.WEEKEND_DAY).repeatDayKind,
        )
        rejected { CreateReminderRequest("t", dueDate = "2026-11-10", repeatUnit = RepeatUnit.MONTH, repeatDayKind = RepeatDayKind.WEEKDAY) }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.MONTH, repeatDayKind = RepeatDayKind.FRIDAY) }
        rejected { UpdateReminderRequest("r-1", repeatUnit = RepeatUnit.MONTH, repeatOrdinal = RepeatOrdinal.NONE, repeatDayKind = RepeatDayKind.DAY) }
        rejectedOnTheWire(RemindersCapability.CreateReminder, """{"title":"t","repeatUnit":"month","repeatDayKind":"weekday"}""")
        rejectedOnTheWire(RemindersCapability.UpdateReminder, """{"reminderId":"r-1","repeatUnit":"month","repeatDayKind":"day"}""")
    }

    // ---- Defaults and patches ---------------------------------------------------------------------------------

    @Test
    fun `a new reminder needs only a title and the rest takes the documented defaults`() {
        val spec = RemindersCapability.CreateReminder
        val decoded = spec.decodeInput(JsonCodec, """{"title":"Buy milk"}""".encodeToByteArray())

        assertEquals(CreateReminderRequest("Buy milk"), decoded)
        assertEquals(
            """{"title":"Buy milk","listId":null,"parentId":null,"notes":"","dueDate":"","dueTime":"",""" +
                """"repeatUnit":"none","repeatInterval":1,"repeatWeekdays":[],"repeatMonthDays":[],"repeatOrdinal":"none",""" +
                """"repeatDayKind":"day","repeatMonths":[],"repeatUntil":"","repeatFromCompletion":false,"flagged":false,"priority":"none","url":"",""" +
                """"tags":[],"earlyAlertAmount":0,"earlyAlertUnit":"minutes"}""",
            spec.encodeInput(JsonCodec, decoded).decodeToString(),
        )
    }

    @Test
    fun `a change is a patch in which only the id is required`() {
        val spec = RemindersCapability.UpdateReminder
        val untouched = spec.decodeInput(JsonCodec, """{"reminderId":"r-1"}""".encodeToByteArray())

        assertEquals(UpdateReminderRequest("r-1"), untouched)
        assertEquals(
            List<Any?>(20) { null },
            with(untouched) {
                listOf(
                    title, listId, notes, dueDate, dueTime, repeatUnit, repeatInterval, repeatWeekdays, repeatMonthDays,
                    repeatOrdinal, repeatDayKind, repeatMonths, repeatUntil, repeatFromCompletion, flagged, priority, url, tags,
                    earlyAlertAmount, earlyAlertUnit,
                )
            },
        )
        val emptied = UpdateReminderRequest("r-1", notes = "", dueDate = "", tags = emptyList(), earlyAlertAmount = 0)
        assertEquals(emptied, spec.decodeInput(JsonCodec, spec.encodeInput(JsonCodec, emptied)))
        assertEquals(emptied, spec.decodeInput(CborCodec, spec.encodeInput(CborCodec, emptied)))
        assertEquals(UpdateListRequest("l-1", null, null), UpdateListRequest("l-1"))
        assertEquals(ListColor.GREEN, UpdateListRequest("l-1", color = ListColor.GREEN).color)
    }

    @Test
    fun `the rules of the domain are left to the provider`() {
        assertEquals("", CreateReminderRequest("").title)
        assertEquals(listOf(31), CreateReminderRequest("t", repeatUnit = RepeatUnit.HOUR, repeatMonthDays = listOf(31)).repeatMonthDays)
        assertEquals(0, CreateReminderRequest("t", repeatUnit = RepeatUnit.DAY, repeatInterval = 0).repeatInterval)
        assertEquals("", CreateListRequest("", ListColor.RED).name)
        assertEquals("", RenameTagRequest("home", "").newName)
        assertEquals("", UpdateReminderRequest("r-1", title = "").title)
    }

    // ---- The wire ---------------------------------------------------------------------------------------------

    @Test
    fun `a request decoded from the wire is validated too`() {
        rejectedOnTheWire(RemindersCapability.GetReminder, """{"reminderId":" "}""")
        rejectedOnTheWire(RemindersCapability.ListReminders, """{"limit":51}""")
        rejectedOnTheWire(RemindersCapability.ListReminders, """{"listId":"l-1","smartList":"today"}""")
        rejectedOnTheWire(RemindersCapability.CreateReminder, """{"title":"t","dueDate":"10-11-2026"}""")
        rejectedOnTheWire(RemindersCapability.UpdateReminder, """{"reminderId":"r-1","repeatInterval":3}""")
        rejectedOnTheWire(RemindersCapability.CompleteReminder, """{"reminderId":"r-1","dueTime":"5pm"}""")
        rejectedOnTheWire(RemindersCapability.GetOverview, """{"offset":-1}""")
        assertEquals(
            ListRemindersRequest(smartList = SmartList.SCHEDULED, includeCompleted = true, limit = 5, offset = 10),
            RemindersCapability.ListReminders.decodeInput(
                JsonCodec,
                """{"smartList":"scheduled","includeCompleted":true,"limit":5,"offset":10}""".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `the enums travel as snake_case and read constants they do not know as unknown`() {
        fun values(serializer: KSerializer<*>) = WireEnums.allowedValues(serializer.descriptor)

        assertEquals(listOf("red", "orange", "yellow", "green", "blue", "purple", "brown"), values(ListColor.serializer()))
        assertEquals(listOf("none", "low", "medium", "high"), values(ReminderPriority.serializer()))
        assertEquals(listOf("today", "scheduled", "all", "flagged", "completed"), values(SmartList.serializer()))
        assertEquals(listOf("minutes", "hours", "days", "weeks", "months"), values(EarlyAlertUnit.serializer()))
        assertEquals(listOf("in_one_hour", "tomorrow"), values(Snooze.serializer()))
        assertEquals(listOf("none", "hour", "day", "week", "month", "year"), values(RepeatUnit.serializer()))
        assertEquals(
            listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"),
            values(Weekday.serializer()),
        )
        assertEquals(listOf("none", "first", "second", "third", "fourth", "fifth", "last"), values(RepeatOrdinal.serializer()))
        assertEquals(
            listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "day", "weekday", "weekend_day"),
            values(RepeatDayKind.serializer()),
        )
        val future = """{"id":"l-1","name":"Home","color":"teal","openCount":2}"""
        assertEquals(ListColor.UNKNOWN, RemindersCapability.CreateList.decodeOutput(JsonCodec, future.encodeToByteArray()).color)
        val days = """{"title":"t","repeatUnit":"week","repeatWeekdays":["monday","someday"]}"""
        assertEquals(
            listOf(Weekday.MONDAY, Weekday.UNKNOWN),
            RemindersCapability.CreateReminder.decodeInput(JsonCodec, days.encodeToByteArray()).repeatWeekdays,
        )
    }
}
