package eus.bihurri.kora.contract.files

import eus.bihurri.kora.contract.IntegerRange
import kotlinx.serialization.Serializable

/*
 * Request types of the files capability. Every request is flat (scalars only), and none carries bytes: the bytes of
 * a file travel as the stream beside the call ([FilesCapability.Upload], [FilesCapability.Download]). A request
 * checks the wire format only: a blank id and a negative size. Such a request fails with `invalid_payload` before it
 * reaches the provider. The rest is the provider's: a blank name or MIME type, a MIME type it does not take, and its
 * size limit, which it answers with `payload_too_large` before a byte is read.
 */

/**
 * Request of [FilesCapability.Upload]: stage a file whose display [name], [mimeType] and exact size, [sizeBytes],
 * the caller declares; the bytes follow as the stream. [sizeBytes] is not negative, and the stream must carry exactly
 * that many bytes. A blank [name] or [mimeType], and a size over the provider's limit, are the provider's to reject.
 */
@Serializable
public data class UploadFileRequest(
    public val name: String,
    public val mimeType: String,
    @IntegerRange(min = 0, max = Long.MAX_VALUE)
    public val sizeBytes: Long,
) {
    init {
        require(sizeBytes >= 0) { "sizeBytes must not be negative: $sizeBytes" }
    }
}

/**
 * Names one file by its opaque [fileId] ([FileHandle.fileId]), which must not be blank. Request of
 * [FilesCapability.Download] and [FilesCapability.Discard].
 */
@Serializable
public data class FileIdRequest(public val fileId: String) {
    init {
        require(fileId.isNotBlank()) { "fileId must not be blank" }
    }
}
