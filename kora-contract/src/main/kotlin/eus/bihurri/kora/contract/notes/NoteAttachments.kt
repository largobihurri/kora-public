package eus.bihurri.kora.contract.notes

import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.files.AttachmentsCapability

/**
 * The files attached to a note: the shared attachments definition ([AttachmentsCapability]) for [NotesCapability],
 * where `itemId` is a `noteId`.
 *
 * The attachments of a note are listed in [NoteDetail.attachments]. A provider of this capability also provides
 * `notes` and `files`.
 *
 * Version history:
 * - 1.0: `add`, `remove`, `open`.
 */
public object NoteAttachments : AttachmentsCapability(
    id = CapabilityId("notes.attachments"),
    item = "note",
    itemIdName = "noteId",
)
