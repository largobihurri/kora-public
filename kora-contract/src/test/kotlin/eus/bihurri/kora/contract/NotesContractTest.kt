package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.notes.CreateFolderRequest
import eus.bihurri.kora.contract.notes.CreateNoteRequest
import eus.bihurri.kora.contract.notes.FolderIdRequest
import eus.bihurri.kora.contract.notes.GetNoteRequest
import eus.bihurri.kora.contract.notes.ListNotesRequest
import eus.bihurri.kora.contract.notes.NoteIdRequest
import eus.bihurri.kora.contract.notes.NoteSort
import eus.bihurri.kora.contract.notes.NotesCapability
import eus.bihurri.kora.contract.notes.NotesOverviewRequest
import eus.bihurri.kora.contract.notes.NotesView
import eus.bihurri.kora.contract.notes.UpdateFolderRequest
import eus.bihurri.kora.contract.notes.UpdateNoteRequest
import eus.bihurri.kora.contract.notes.UpdateNotesSettingsRequest
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The wire-format checks of the `notes` requests, and what they leave to the provider. */
class NotesContractTest {
    private fun rejected(build: () -> Any) {
        assertThrows(IllegalArgumentException::class.java) { build() }
    }

    // ---- The capability ---------------------------------------------------------------------------------------

    @Test
    fun `notes has twelve operations, no event, and separate read and write scopes`() {
        assertEquals(
            listOf(
                "get_overview", "list_notes", "get_note", "create_note", "update_note", "delete_note",
                "delete_note_permanently", "restore_note", "create_folder", "update_folder", "delete_folder",
                "update_settings",
            ),
            NotesCapability.operations.map { it.name },
        )
        assertEquals(emptyList<String>(), NotesCapability.events.map { it.name })
        assertEquals(CapabilityDescriptor("notes", CapabilityVersion(1, 0)), NotesCapability.descriptor())
        assertEquals(Scope("notes.read"), NotesCapability.ReadScope)
        assertEquals(Scope("notes.write"), NotesCapability.WriteScope)
        assertEquals(
            listOf("get_overview", "list_notes", "get_note"),
            NotesCapability.operations.filter { it.scope == NotesCapability.ReadScope }.map { it.name },
        )
        assertTrue(NotesCapability.operations.all { it.scope in setOf(NotesCapability.ReadScope, NotesCapability.WriteScope) })
    }

    // ---- Ids --------------------------------------------------------------------------------------------------

    @Test
    fun `a request rejects a blank id`() {
        assertEquals("n-1", NoteIdRequest("n-1").noteId)
        rejected { NoteIdRequest("") }
        rejected { NoteIdRequest("  ") }
        rejected { GetNoteRequest(" ") }
        rejected { UpdateNoteRequest("") }
        assertEquals("f-1", FolderIdRequest("f-1").folderId)
        rejected { FolderIdRequest(" ") }
        assertEquals("f-1", UpdateFolderRequest("f-1").folderId)
        rejected { UpdateFolderRequest("") }
    }

    @Test
    fun `an optional id is absent or not blank`() {
        assertEquals("f-1", CreateNoteRequest("Shopping", folderId = "f-1").folderId)
        rejected { CreateNoteRequest("Shopping", folderId = "") }
        assertEquals("f-2", UpdateNoteRequest("n-1", folderId = "f-2").folderId)
        rejected { UpdateNoteRequest("n-1", folderId = " ") }
        assertEquals("f-1", CreateFolderRequest("Work", parentId = "f-1").parentId)
        rejected { CreateFolderRequest("Work", parentId = "") }
        rejected { ListNotesRequest(folderId = " ") }
    }

    @Test
    fun `an empty parent moves a folder to the top level, and whitespace is not an id`() {
        assertEquals(null, UpdateFolderRequest("f-1", name = "Work").parentId)
        assertEquals("", UpdateFolderRequest("f-1", parentId = "").parentId)
        assertEquals("f-2", UpdateFolderRequest("f-1", parentId = "f-2").parentId)
        rejected { UpdateFolderRequest("f-1", parentId = " ") }
    }

    // ---- Listings ---------------------------------------------------------------------------------------------

    @Test
    fun `a listing takes a limit from 1 to 50, 20 by default, and an offset that is not negative`() {
        assertEquals(20, NotesCapability.DEFAULT_LIMIT)
        assertEquals(50, NotesCapability.MAX_LIMIT)
        assertEquals(ListNotesRequest(limit = 20, offset = 0), ListNotesRequest())
        assertEquals(NotesOverviewRequest(20, 0), NotesOverviewRequest())
        assertEquals(1, ListNotesRequest(limit = 1).limit)
        assertEquals(50, NotesOverviewRequest(limit = 50, offset = 9).limit)
        rejected { ListNotesRequest(limit = 0) }
        rejected { ListNotesRequest(limit = 51) }
        rejected { ListNotesRequest(offset = -1) }
        rejected { NotesOverviewRequest(limit = 0) }
        rejected { NotesOverviewRequest(limit = 51) }
        rejected { NotesOverviewRequest(offset = -1) }
    }

    @Test
    fun `a listing takes at most one selector`() {
        assertEquals(ListNotesRequest(null, emptyList(), false, ""), ListNotesRequest())
        assertEquals("f-1", ListNotesRequest(folderId = "f-1").folderId)
        assertEquals(listOf("work"), ListNotesRequest(tags = listOf("work")).tags)
        assertEquals(true, ListNotesRequest(deleted = true).deleted)
        assertEquals("milk", ListNotesRequest(query = "milk").query)
        rejected { ListNotesRequest(folderId = "f-1", query = "milk") }
        rejected { ListNotesRequest(folderId = "f-1", tags = listOf("work")) }
        rejected { ListNotesRequest(tags = listOf("work"), deleted = true) }
        rejected { ListNotesRequest(deleted = true, query = "milk") }
    }

    // ---- Reading in parts -------------------------------------------------------------------------------------

    @Test
    fun `a note is read from the beginning in parts of 4000 characters unless the caller chooses`() {
        assertEquals(500, NotesCapability.MIN_NOTE_CHARS)
        assertEquals(20_000, NotesCapability.MAX_NOTE_CHARS)
        assertEquals(4_000, NotesCapability.DEFAULT_NOTE_CHARS)
        assertEquals(GetNoteRequest("n-1", offset = 0, maxChars = 4_000), GetNoteRequest("n-1"))
        assertEquals(GetNoteRequest("n-1"), NotesCapability.GetNote.decodeInput(JsonCodec, """{"noteId":"n-1"}""".encodeToByteArray()))
        assertEquals(500, GetNoteRequest("n-1", maxChars = 500).maxChars)
        assertEquals(20_000, GetNoteRequest("n-1", offset = 12_000, maxChars = 20_000).maxChars)
        rejected { GetNoteRequest("n-1", maxChars = 499) }
        rejected { GetNoteRequest("n-1", maxChars = 20_001) }
        rejected { GetNoteRequest("n-1", maxChars = 0) }
        rejected { GetNoteRequest("n-1", offset = -1) }
    }

    // ---- Changes ----------------------------------------------------------------------------------------------

    @Test
    fun `a change replaces the content or appends to it, never both`() {
        assertEquals("# Title", UpdateNoteRequest("n-1", markdown = "# Title").markdown)
        assertEquals("- [ ] eggs", UpdateNoteRequest("n-1", append = "- [ ] eggs").append)
        rejected { UpdateNoteRequest("n-1", markdown = "# Title", append = "- [ ] eggs") }
        rejected { UpdateNoteRequest("n-1", markdown = "", append = "") }
    }

    @Test
    fun `a change is a patch in which only the id is required`() {
        val spec = NotesCapability.UpdateNote
        val untouched = spec.decodeInput(JsonCodec, """{"noteId":"n-1"}""".encodeToByteArray())

        assertEquals(UpdateNoteRequest("n-1", null, null, null, null), untouched)
        val pinned = UpdateNoteRequest("n-1", folderId = "f-2", pinned = true)
        assertEquals(pinned, spec.decodeInput(JsonCodec, spec.encodeInput(JsonCodec, pinned)))
        assertEquals(pinned, spec.decodeInput(CborCodec, spec.encodeInput(CborCodec, pinned)))
        assertEquals(UpdateFolderRequest("f-1", null, null), UpdateFolderRequest("f-1"))
        assertEquals(UpdateNotesSettingsRequest(null, null, null), UpdateNotesSettingsRequest())
        assertEquals(
            UpdateNotesSettingsRequest(view = NotesView.GALLERY),
            NotesCapability.UpdateSettings.decodeInput(JsonCodec, """{"view":"gallery"}""".encodeToByteArray()),
        )
    }

    @Test
    fun `deleting a note and deleting it for good are two operations`() {
        val delete = NotesCapability.DeleteNote
        val forGood = NotesCapability.findOperation("delete_note_permanently")!!
        assertEquals(listOf("noteId"), delete.inputSerializer.descriptor.elementNames.toList())
        assertEquals(listOf("noteId"), forGood.inputSerializer.descriptor.elementNames.toList())
        assertEquals(NoteIdRequest("n-1"), delete.decodeInput(JsonCodec, """{"noteId":"n-1"}""".encodeToByteArray()))
        assertEquals(NoteIdRequest("n-1"), forGood.decodeInput(JsonCodec, """{"noteId":"n-1"}""".encodeToByteArray()))
        rejectedOnTheWire(forGood, """{"noteId":" "}""")
        assertEquals(OperationEffect.WRITE, delete.effect)
        assertEquals(OperationEffect.DESTRUCTIVE, forGood.effect)
        assertEquals(NotesCapability.WriteScope, forGood.scope)
        assertEquals("{}", NotesCapability.DeleteNote.encodeOutput(JsonCodec, Unit).decodeToString())
    }

    @Test
    fun `the deletions name what goes with them`() {
        val forGood = NotesCapability.findOperation("delete_note_permanently")!!
        for (spec in listOf(NotesCapability.DeleteNote, forGood)) {
            assertTrue("sub-notes" in spec.description, spec.description)
        }
        assertTrue("cannot be undone" in forGood.description, forGood.description)
        assertTrue("nested" in NotesCapability.DeleteFolder.description, NotesCapability.DeleteFolder.description)
    }

    @Test
    fun `a new note is not pinned unless the caller pins it`() {
        assertEquals(false, CreateNoteRequest("Idea").pinned)
        assertEquals(
            CreateNoteRequest("Idea", pinned = true),
            NotesCapability.CreateNote.decodeInput(JsonCodec, """{"markdown":"Idea","pinned":true}""".encodeToByteArray()),
        )
        assertEquals(
            """{"markdown":"Idea","folderId":null,"pinned":false}""",
            NotesCapability.CreateNote.encodeInput(JsonCodec, CreateNoteRequest("Idea")).decodeToString(),
        )
    }

    @Test
    fun `a query of only whitespace is no search, so it combines with a selector`() {
        assertEquals("f-1", ListNotesRequest(folderId = "f-1", query = " ").folderId)
        assertEquals(true, ListNotesRequest(deleted = true, query = "\t ").deleted)
        rejected { ListNotesRequest(folderId = "f-1", query = " milk ") }
    }

    @Test
    fun `the rules of the domain are left to the provider`() {
        assertEquals("", CreateNoteRequest("").markdown)
        assertEquals("- [ ] ", UpdateNoteRequest("n-1", markdown = "- [ ] ").markdown)
        assertEquals("", CreateFolderRequest("").name)
        assertEquals("f-1", UpdateFolderRequest("f-1", parentId = "f-1").parentId)
        assertEquals(" ", ListNotesRequest(query = " ").query)
    }

    // ---- The wire ---------------------------------------------------------------------------------------------

    @Test
    fun `a request decoded from the wire is validated too`() {
        rejectedOnTheWire(NotesCapability.GetNote, """{"noteId":" "}""")
        rejectedOnTheWire(NotesCapability.GetNote, """{"noteId":"n-1","maxChars":100}""")
        rejectedOnTheWire(NotesCapability.ListNotes, """{"limit":51}""")
        rejectedOnTheWire(NotesCapability.ListNotes, """{"deleted":true,"query":"milk"}""")
        rejectedOnTheWire(NotesCapability.UpdateNote, """{"noteId":"n-1","markdown":"a","append":"b"}""")
        rejectedOnTheWire(NotesCapability.UpdateFolder, """{"folderId":"f-1","parentId":"  "}""")
        rejectedOnTheWire(NotesCapability.GetOverview, """{"limit":0}""")
        assertEquals(
            GetNoteRequest("n-1", 6_000, 1_000),
            NotesCapability.GetNote.decodeInput(JsonCodec, """{"noteId":"n-1","offset":6000,"maxChars":1000}""".encodeToByteArray()),
        )
    }

    @Test
    fun `the enums travel as snake_case and read constants they do not know as unknown`() {
        assertEquals(listOf("date_edited", "date_created", "title"), WireEnums.allowedValues(NoteSort.serializer().descriptor))
        assertEquals(listOf("list", "gallery"), WireEnums.allowedValues(NotesView.serializer().descriptor))
        val future = """{"sort":"size","view":"board","moveTickedItems":true}"""
        val settings = NotesCapability.UpdateSettings.decodeOutput(JsonCodec, future.encodeToByteArray())
        assertEquals(NoteSort.UNKNOWN, settings.sort)
        assertEquals(NotesView.UNKNOWN, settings.view)
    }

    @Test
    fun `a part of a note says where the next one starts and when the note is whole`() {
        val part = """{"id":"n-1","title":"Long","pinned":false,"tags":[],"createdAtEpochMs":1,"editedAtEpochMs":2,""" +
            """"attachmentCount":0,"markdown":"Long\nline","offset":6000,"totalChars":6009}"""
        val detail = NotesCapability.GetNote.decodeOutput(JsonCodec, part.encodeToByteArray())

        assertEquals(detail.totalChars, detail.offset + detail.markdown.length)
        assertEquals(null, detail.folderId)
        assertEquals(null, detail.daysLeft)
        assertEquals(emptyList<Any>(), detail.attachments)
    }
}
