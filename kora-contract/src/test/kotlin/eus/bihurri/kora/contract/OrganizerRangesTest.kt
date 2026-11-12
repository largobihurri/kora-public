package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.calendar.CalendarCapability
import eus.bihurri.kora.contract.notes.NotesCapability
import eus.bihurri.kora.contract.reminders.RemindersCapability
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An organizer request that checks an offset declares the bound on the field too, so a schema shows it to a caller. */
@OptIn(ExperimentalSerializationApi::class)
class OrganizerRangesTest {
    private val inputs: List<Pair<String, SerialDescriptor>> =
        listOf(RemindersCapability, CalendarCapability, NotesCapability)
            .flatMap { capability -> capability.operations.map { "${capability.id.value}.${it.name}" to it.inputSerializer.descriptor } }

    private fun rangeOf(descriptor: SerialDescriptor, field: String): IntegerRange? =
        descriptor.getElementAnnotations(descriptor.getElementIndex(field)).filterIsInstance<IntegerRange>().firstOrNull()

    @Test
    fun `every offset of an organizer request declares that it is not negative`() {
        val withOffset = inputs.filter { (_, descriptor) -> "offset" in descriptor.elementNames }

        assertEquals(
            listOf(
                "reminders.get_overview", "reminders.list_reminders", "calendar.get_overview", "calendar.list_events",
                "notes.get_overview", "notes.list_notes", "notes.get_note",
            ),
            withOffset.map { it.first },
        )
        for ((name, descriptor) in withOffset) {
            val range = rangeOf(descriptor, "offset")
            assertEquals(0L, range?.min, "the lower bound of the offset of $name")
            assertEquals(Int.MAX_VALUE.toLong(), range?.max, "the upper bound of the offset of $name")
        }
    }
}
