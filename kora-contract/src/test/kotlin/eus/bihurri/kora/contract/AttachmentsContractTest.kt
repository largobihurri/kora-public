package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.files.AddAttachmentRequest
import eus.bihurri.kora.contract.files.AttachmentInfo
import eus.bihurri.kora.contract.files.AttachmentRequest
import eus.bihurri.kora.contract.files.AttachmentsCapability
import eus.bihurri.kora.contract.files.FileHandle
import eus.bihurri.kora.contract.notes.NoteAttachments
import eus.bihurri.kora.contract.reminders.ReminderAttachments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The attachments capabilities of 1.3.0: one definition, instantiated for reminders and notes. The calendar's is
 * designed but not in this release (`docs/organizer-vocabulary.md`, section 8).
 */
class AttachmentsContractTest {
    private val all: List<AttachmentsCapability> = listOf(ReminderAttachments, NoteAttachments)

    private fun rejected(build: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun `the two capabilities have the same three operations at version 1_0 and no event`() {
        assertEquals(
            listOf("reminders.attachments", "notes.attachments"),
            all.map { it.id.value },
        )
        for (capability in all) {
            assertEquals(CapabilityVersion(1, 0), capability.version, capability.id.value)
            assertEquals(listOf("add", "remove", "open"), capability.operations.map { it.name }, capability.id.value)
            assertEquals(emptyList<String>(), capability.events.map { it.name }, capability.id.value)
            assertSame(capability.Add, capability.findOperation("add"))
            assertSame(capability.Remove, capability.findOperation("remove"))
            assertSame(capability.Open, capability.findOperation("open"))
            assertEquals(capability.id, capability.Add.capabilityId)
        }
        assertNotSame(ReminderAttachments.Add, NoteAttachments.Add)
    }

    @Test
    fun `each has its own read and write scopes, and only open is a read`() {
        assertEquals(Scope("reminders.attachments.read"), ReminderAttachments.ReadScope)
        assertEquals(Scope("reminders.attachments.write"), ReminderAttachments.WriteScope)
        assertEquals(Scope("notes.attachments.read"), NoteAttachments.ReadScope)
        assertEquals(Scope("notes.attachments.write"), NoteAttachments.WriteScope)
        for (capability in all) {
            assertEquals(capability.WriteScope, capability.Add.scope)
            assertEquals(capability.WriteScope, capability.Remove.scope)
            assertEquals(capability.ReadScope, capability.Open.scope)
        }
    }

    @Test
    fun `the descriptions say which id itemId takes`() {
        assertTrue("reminderId" in ReminderAttachments.Add.description, ReminderAttachments.Add.description)
        assertTrue("noteId" in NoteAttachments.Remove.description, NoteAttachments.Remove.description)
        assertTrue("noteId" in NoteAttachments.Open.description, NoteAttachments.Open.description)
    }

    @Test
    fun `there is no attachments capability for the calendar in this release`() {
        assertEquals(null, Capabilities.find("calendar.attachments"))
        assertEquals(
            listOf("reminders.attachments", "notes.attachments"),
            Capabilities.all.filterIsInstance<AttachmentsCapability>().map { it.id.value },
        )
    }

    @Test
    fun `adding needs an item and a staged file, and the name is optional`() {
        assertEquals(AddAttachmentRequest("r-1", "file-1", name = ""), AddAttachmentRequest("r-1", "file-1"))
        assertEquals("receipt.pdf", AddAttachmentRequest("r-1", "file-1", "receipt.pdf").name)
        rejected { AddAttachmentRequest("", "file-1") }
        rejected { AddAttachmentRequest("  ", "file-1") }
        rejected { AddAttachmentRequest("r-1", "") }
        rejected { AddAttachmentRequest("r-1", " ", "receipt.pdf") }
    }

    @Test
    fun `removing and opening need an item and an attachment`() {
        assertEquals("a-1", AttachmentRequest("r-1", "a-1").attachmentId)
        rejected { AttachmentRequest("", "a-1") }
        rejected { AttachmentRequest(" ", "a-1") }
        rejected { AttachmentRequest("r-1", "") }
        rejected { AttachmentRequest("r-1", "  ") }
    }

    @Test
    fun `a request decoded from the wire is validated too`() {
        for (capability in all) {
            rejectedOnTheWire(capability.Add, """{"itemId":"r-1","fileId":" "}""")
            rejectedOnTheWire(capability.Open, """{"itemId":"","attachmentId":"a-1"}""")
            assertEquals(
                AddAttachmentRequest("r-1", "file-1"),
                capability.Add.decodeInput(JsonCodec, """{"itemId":"r-1","fileId":"file-1"}""".encodeToByteArray()),
            )
        }
    }

    @Test
    fun `open answers a file handle and add the attachment, without bytes or checks`() {
        val handle = FileHandle("file-9", "receipt.pdf", "application/pdf", 5_000_000_000)
        val info = AttachmentInfo("a-1", "receipt.pdf", "application/pdf", 5_000_000_000)

        for (codec in listOf(CborCodec, JsonCodec)) {
            assertEquals(handle, NoteAttachments.Open.decodeOutput(codec, NoteAttachments.Open.encodeOutput(codec, handle)))
            assertEquals(info, NoteAttachments.Add.decodeOutput(codec, NoteAttachments.Add.encodeOutput(codec, info)))
        }
        assertEquals(
            """{"fileId":"file-9","name":"receipt.pdf","mimeType":"application/pdf","sizeBytes":5000000000}""",
            NoteAttachments.Open.encodeOutput(JsonCodec, handle).decodeToString(),
        )
        val odd = """{"fileId":"","name":"","mimeType":"","sizeBytes":-1}"""
        assertEquals(FileHandle("", "", "", -1), ReminderAttachments.Open.decodeOutput(JsonCodec, odd.encodeToByteArray()))
    }
}
