package eus.bihurri.kora.contract.files

import kotlinx.serialization.Serializable

/**
 * A file a provider holds for a caller, named by the opaque [fileId]: never a path or a URI, so a caller can neither
 * guess nor open it outside Kora. [name] is its display name, [mimeType] its MIME type and [sizeBytes] its exact size.
 *
 * `open` of an attachments capability ([AttachmentsCapability.Open]) answers one, and `files.download` of the same
 * provider turns any handle it gave into bytes. A handle belongs to the caller it was given to and stays valid at
 * least 10 minutes after its last use while the file exists ([FilesCapability]).
 */
@Serializable
public data class FileHandle(
    public val fileId: String,
    public val name: String,
    public val mimeType: String,
    public val sizeBytes: Long,
)
