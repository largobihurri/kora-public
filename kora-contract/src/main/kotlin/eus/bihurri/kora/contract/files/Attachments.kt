package eus.bihurri.kora.contract.files

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/**
 * One file attached to a reminder or a note: the opaque [id] of the attachment within its item, its display [name],
 * its [mimeType] and its size, [sizeBytes].
 *
 * The attachments of an item are listed in the answer that describes the item (`get_reminder`, `get_note`);
 * [AttachmentsCapability.Open] turns one into a [FileHandle].
 */
@Serializable
public data class AttachmentInfo(
    public val id: String,
    public val name: String,
    public val mimeType: String,
    public val sizeBytes: Long,
)

/**
 * Request of [AttachmentsCapability.Add]: attach the staged file [fileId] to the item [itemId].
 *
 * [itemId] is a `reminderId` or a `noteId`, depending on the capability. [fileId] is the handle of a file the caller
 * staged with `files.upload` of the same provider; attaching uses it up. [name], when not empty, replaces the name the
 * file was uploaded with. Neither id may be blank. No request carries bytes.
 *
 * Attaching can copy the file, which takes time. An `add` that fails leaves the staged file as it was, usable by
 * another `add`. After a `timeout` the caller cannot tell whether the file was attached: it reads the item again
 * (`get_reminder`, `get_note`) to see whether the attachment is there before it tries again.
 */
@Serializable
public data class AddAttachmentRequest(
    public val itemId: String,
    public val fileId: String,
    public val name: String = "",
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank" }
        require(fileId.isNotBlank()) { "fileId must not be blank" }
    }
}

/**
 * Request of [AttachmentsCapability.Remove] and [AttachmentsCapability.Open]: the attachment [attachmentId]
 * ([AttachmentInfo.id]) of the item [itemId]. Neither id may be blank.
 */
@Serializable
public data class AttachmentRequest(
    public val itemId: String,
    public val attachmentId: String,
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank" }
        require(attachmentId.isNotBlank()) { "attachmentId must not be blank" }
    }
}

/**
 * The one definition of the attachments capabilities: the same three operations and payloads for the files attached
 * to a reminder and to a note. Each section has its own capability (`reminders.attachments`, `notes.attachments`),
 * separate from the section itself, so a provider can offer a section before its attachments. The same definition
 * will serve the files attached to an event (`calendar.attachments`), which is designed but not in this release: it
 * arrives later as an additive capability, once a provider has a store for the attachments of events.
 *
 * - `itemId` names the item that carries the attachment, with the id the section's own capability uses.
 * - [Add] attaches a file the caller staged with `files.upload`; a failed [Add] leaves the staged file usable, and
 *   after a `timeout` the caller reads the item to see whether the attachment is there ([AddAttachmentRequest]).
 *   [Open] answers a [FileHandle] whose bytes `files.download` reads. A provider of an attachments capability
 *   therefore also provides `files` and the section's capability.
 * - Effects: [Open] is [OperationEffect.READ], [Add] [OperationEffect.WRITE] and [Remove], which loses the file for
 *   good, [OperationEffect.DESTRUCTIVE].
 * - Listing is not an operation: the attachments of an item come in the answer that describes it.
 * - An item or an attachment the provider does not have fails with `invalid_payload`; a file over the provider's
 *   size limit fails with `payload_too_large`.
 *
 * Version history:
 * - 1.0: [Add], [Remove], [Open].
 *
 * @param item what the items are called in the operation descriptions, e.g. `reminder`.
 * @param itemIdName the name of the id `itemId` takes, e.g. `reminderId`.
 */
public abstract class AttachmentsCapability(
    id: CapabilityId,
    item: String,
    itemIdName: String,
) : Capability(id, CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("${id.value}.read")

    public val WriteScope: Scope = Scope("${id.value}.write")

    public val Add: OperationSpec<AddAttachmentRequest, AttachmentInfo> = operation(
        name = "add",
        input = AddAttachmentRequest.serializer(),
        output = AttachmentInfo.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Attach a file staged with files.upload (fileId) to a $item; itemId is its $itemIdName. " +
            "The staged file is used up, and name, when given, replaces the name it was uploaded with. If add fails " +
            "the staged file stays usable; after a timeout, read the $item to see whether it was attached.",
    )

    public val Remove: OperationSpec<AttachmentRequest, Unit> = operation(
        name = "remove",
        input = AttachmentRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.DESTRUCTIVE,
        description = "Remove an attachment from a $item for good; itemId is its $itemIdName.",
    )

    public val Open: OperationSpec<AttachmentRequest, FileHandle> = operation(
        name = "open",
        input = AttachmentRequest.serializer(),
        output = FileHandle.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get a file handle to read an attachment of a $item with files.download; itemId is its " +
            "$itemIdName.",
    )
}
