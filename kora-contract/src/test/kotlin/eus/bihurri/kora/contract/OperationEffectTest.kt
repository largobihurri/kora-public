package eus.bihurri.kora.contract

import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The effect every operation declares ([OperationSpec.effect]): explicit on every built-in, never on the wire. */
class OperationEffectTest {
    private val builtIns = Capabilities.all.flatMap { capability -> capability.operations.map { capability to it } }

    private fun label(capability: Capability, spec: OperationSpec<*, *>) = "${capability.id}.${spec.name}"

    @Test
    fun `every built-in operation declares its effect explicitly`() {
        val undeclared = builtIns.filterNot { (_, spec) -> spec.effectDeclared }.map { (capability, spec) -> label(capability, spec) }

        assertEquals(emptyList<String>(), undeclared)
    }

    @Test
    fun `an operation with a read scope is a read, and one with any other scope is not`() {
        val wrong = builtIns.filter { (_, spec) ->
            spec.scope.value.endsWith(".read") != (spec.effect == OperationEffect.READ)
        }.map { (capability, spec) -> "${label(capability, spec)} (${spec.scope}, ${spec.effect})" }

        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `exactly these built-in operations lose something for good`() {
        assertEquals(
            listOf(
                "reminders.delete_reminder", "reminders.delete_list", "reminders.delete_tag",
                "calendar.delete_event", "calendar.delete_calendar",
                "notes.delete_note_permanently", "notes.delete_folder",
                "reminders.attachments.remove", "notes.attachments.remove",
            ),
            builtIns.filter { (_, spec) -> spec.effect == OperationEffect.DESTRUCTIVE }.map { (capability, spec) -> label(capability, spec) },
        )
    }

    private object Legacy : Capability(CapabilityId("test.legacy"), CapabilityVersion(1, 0)) {
        private val Read = Scope("test.legacy.read")
        val Plain: OperationSpec<NoInput, Unit> = operation("plain", NoInput.serializer(), Unit.serializer(), Read, "Plain.")
        val Streamed: OperationSpec<NoInput, Unit> =
            operation("streamed", NoInput.serializer(), Unit.serializer(), Read, "Streamed.", StreamDirection.DOWNLOAD)
        val Declared: OperationSpec<NoInput, Unit> =
            operation("declared", NoInput.serializer(), Unit.serializer(), Read, "Declared.", OperationEffect.READ)
    }

    @Test
    fun `the overloads without an effect stay and default to destructive, which a capability can override`() {
        for (spec in listOf(Legacy.Plain, Legacy.Streamed)) {
            assertEquals(OperationEffect.DESTRUCTIVE, spec.effect, spec.name)
            assertFalse(spec.effectDeclared, spec.name)
        }
        assertEquals(StreamDirection.DOWNLOAD, Legacy.Streamed.stream)
        assertEquals(OperationEffect.READ, Legacy.Declared.effect)
        assertEquals(StreamDirection.NONE, Legacy.Declared.stream)
        assertTrue(Legacy.Declared.effectDeclared)
    }

    @Test
    fun `the effect is not on the wire`() {
        val descriptor = CapabilityDescriptor("test.legacy", CapabilityVersion(1, 0))
        assertEquals(descriptor, Legacy.descriptor())
        assertEquals("""{"id":"test.legacy","version":{"major":1,"minor":0}}""", JsonCodec.encode(CapabilityDescriptor.serializer(), descriptor).decodeToString())
    }
}
