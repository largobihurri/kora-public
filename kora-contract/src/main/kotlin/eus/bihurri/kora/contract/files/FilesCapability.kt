package eus.bihurri.kora.contract.files

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.StreamDirection
import kotlinx.serialization.builtins.serializer

/**
 * Handing a file from one app to another: the caller stages a file with the provider ([Upload]) or reads one the
 * provider holds ([Download]), and the bytes travel as a stream beside the call ([StreamDirection]), never inside a
 * payload. A file is named by a [FileHandle], whose `fileId` is opaque: never a path or a URI.
 *
 * - **Upload.** The request declares the name, the MIME type and the exact size, and the bytes follow as the stream.
 *   A size over the provider's own limit is `payload_too_large` before a byte is read. The answer is the handle of a
 *   **staged** file, which waits to be used once, by an `add` of an attachments capability of the same provider
 *   ([AttachmentsCapability.Add]) or, for a UTF-8 text, by a request of the `ai` capability that names it
 *   (`eus.bihurri.kora.contract.ai.TextInput`).
 * - **Download.** [Download] reads the file of any handle the provider gave the caller, today the one `open` of an
 *   attachments capability answers ([AttachmentsCapability.Open]): it answers the handle's description with the
 *   bytes as the stream. A handle can be downloaded any number of times. A later operation that answers a handle
 *   (an export, for example) is read the same way.
 * - **Handles.** A caller relies on three guarantees and no more: a staged file is used by one `add` (or one `ai`
 *   request), and one that fails leaves it usable; a handle stays valid at least 10 minutes after its last use while the file exists
 *   (an attachment removed, or an item deleted for good, takes its handles with it); a handle belongs to the caller
 *   it was given to, and from anyone else it is an unknown id (`invalid_payload`). [Discard] drops one at once. How
 *   the provider keeps and cleans staged files is its own business.
 * - **Effects.** [Download] is [OperationEffect.READ]; [Upload] and [Discard] are [OperationEffect.WRITE]: discarding
 *   drops a handle and a staged copy of a file the caller holds, never an attachment.
 * - **Who offers it.** A provider that offers an attachments capability or `ai` also offers `files`.
 * - **Transports.** Only a transport that carries streams serves [Upload] and [Download] (Binder between apps on one
 *   phone); through the JSON gate and the relay to a watch they fail with `provider_unavailable`, and MCP does not
 *   offer them as tools. [Discard] carries no stream and works on every transport.
 * - **Not for a model.** These operations are not tools for a model, which cannot hold bytes: an assistant's own
 *   code uploads the file the user gave it with Kora's typed client and hands the model the `fileId`. MCP leaves out
 *   the two stream operations; [Discard] stays a tool.
 * - **Failures.** A malformed request and an id the provider does not have (or that belongs to another caller) are
 *   `invalid_payload`; a file over the provider's size limit is `payload_too_large`.
 *
 * Version history:
 * - 1.0: [Upload], [Download], [Discard].
 */
public object FilesCapability : Capability(CapabilityId("files"), CapabilityVersion(1, 0)) {
    /** Scope of [Download]. */
    public val ReadScope: Scope = Scope("files.read")

    /** Scope of [Upload] and [Discard]. */
    public val WriteScope: Scope = Scope("files.write")

    /** Stages a file: the request declares it and the bytes travel from the caller as the stream. */
    public val Upload: OperationSpec<UploadFileRequest, FileHandle> = operation(
        name = "upload",
        input = UploadFileRequest.serializer(),
        output = FileHandle.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Stage a file for one add of an attachments capability of this provider: name, mimeType and " +
            "the exact sizeBytes, with the bytes as a stream beside the call. A size over the provider's limit is " +
            "payload_too_large before a byte is read. Answers the handle of the staged file.",
        stream = StreamDirection.UPLOAD,
    )

    /** Reads a file: the answer describes it and the bytes travel to the caller as the stream. */
    public val Download: OperationSpec<FileIdRequest, FileHandle> = operation(
        name = "download",
        input = FileIdRequest.serializer(),
        output = FileHandle.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Read the file of a handle this provider gave you, such as the one open of an attachments " +
            "capability answers: its description, with the bytes as a stream beside the answer. A handle can be " +
            "downloaded any number of times.",
        stream = StreamDirection.DOWNLOAD,
    )

    /** Drops a handle at once; afterwards its id is unknown. No stream. */
    public val Discard: OperationSpec<FileIdRequest, Unit> = operation(
        name = "discard",
        input = FileIdRequest.serializer(),
        output = Unit.serializer(),
        scope = WriteScope,
        effect = OperationEffect.WRITE,
        description = "Drop a file handle at once, staged or opened; afterwards its fileId is unknown.",
    )
}
