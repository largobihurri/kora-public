package eus.bihurri.kora.contract.ai

import eus.bihurri.kora.contract.IntegerRange
import kotlinx.serialization.Serializable

/*
 * Request types of the ai capability. A request checks what the wire format can: blank ids, lengths and counts, and
 * that a text is given exactly one way. Such a request fails with `invalid_payload` before it reaches the provider.
 * The rest is the provider's: an engine id it does not offer and a file id it does not hold are `invalid_payload`
 * too, and an engine that cannot run fails the job ([FailureReason.ENGINE_UNAVAILABLE]).
 */

private fun requireKey(requestKey: String?) {
    val valid = requestKey == null ||
        (requestKey.isNotBlank() && requestKey.length <= AiCapability.MAX_REQUEST_KEY_LENGTH)
    require(valid) {
        "requestKey must not be blank and holds at most ${AiCapability.MAX_REQUEST_KEY_LENGTH} characters"
    }
}

private fun requireOptionalNotBlank(name: String, value: String?) {
    require(value == null || value.isNotBlank()) { "$name must not be blank" }
}

private fun requireLanguage(language: String?) {
    require(language == null || (language.isNotBlank() && language.length <= AiCapability.MAX_LANGUAGE_LENGTH)) {
        "language must be a BCP 47 tag of at most ${AiCapability.MAX_LANGUAGE_LENGTH} characters"
    }
}

/**
 * A text handed to an engine, given exactly one way:
 * - [text] inline, for a text of at most [AiCapability.MAX_INLINE_CHARS] characters in the whole request. It works on
 *   every transport, MCP included.
 * - [fileId] of a file staged with `files.upload` of the same provider (`eus.bihurri.kora.contract.files`), as UTF-8
 *   `text/plain`, for a text of any length the provider takes. Only a transport that carries streams can stage one
 *   (Binder between apps on one phone). The request that names it uses the staged file; a request that fails before
 *   a job starts leaves it usable.
 */
@Serializable
public data class TextInput(
    public val text: String? = null,
    public val fileId: String? = null,
) {
    init {
        require((text == null) != (fileId == null)) { "Exactly one of text and fileId must be given" }
        requireOptionalNotBlank("fileId", fileId)
    }

    /** Characters given inline, 0 for a staged file. */
    internal val inlineChars: Int get() = text?.length ?: 0
}

/**
 * Request of [AiCapability.SummarizeMeeting]: summarize the meeting whose [transcript] is given.
 *
 * - [engineId] is an [AiEngine.id] from [AiCapability.ListEngines] that runs [AiTask.SUMMARIZE_MEETING]; null for the
 *   provider's default engine.
 * - [title], [startedAtEpochMs] and [durationMs] describe the meeting, for the engine's context; all optional.
 * - [language] is the BCP 47 tag of the language to write the summary in (`eu`, `es`); null for the language of the
 *   transcript.
 * - [requestKey], when given, makes the request safe to repeat: see [AiCapability] ("Repeating a request").
 *
 * An inline transcript holds at most [AiCapability.MAX_INLINE_CHARS] characters; a longer one is staged as a file.
 */
@Serializable
public data class SummarizeMeetingRequest(
    public val transcript: TextInput,
    public val engineId: String? = null,
    public val title: String? = null,
    public val startedAtEpochMs: Long? = null,
    @IntegerRange(min = 0, max = Long.MAX_VALUE)
    public val durationMs: Long? = null,
    public val language: String? = null,
    public val requestKey: String? = null,
) {
    init {
        require(transcript.inlineChars <= AiCapability.MAX_INLINE_CHARS) {
            "An inline transcript holds at most ${AiCapability.MAX_INLINE_CHARS} characters: ${transcript.inlineChars}"
        }
        requireOptionalNotBlank("engineId", engineId)
        require(durationMs == null || durationMs >= 0) { "durationMs must not be negative: $durationMs" }
        requireLanguage(language)
        requireKey(requestKey)
    }
}

/**
 * One document of an [AskRequest], such as the transcript of an earlier meeting: its [text], and an optional [title]
 * and [startedAtEpochMs] the engine may cite.
 */
@Serializable
public data class AskDocument(
    public val text: TextInput,
    public val title: String? = null,
    public val startedAtEpochMs: Long? = null,
)

/**
 * Request of [AiCapability.Ask]: answer [question] from [documents] only.
 *
 * - [question] is not blank and holds at most [AiCapability.MAX_QUESTION_CHARS] characters.
 * - [documents] holds between 1 and [AiCapability.MAX_DOCUMENTS] documents, whose inline texts together hold at most
 *   [AiCapability.MAX_INLINE_CHARS] characters; longer texts are staged as files.
 * - [engineId], [language] and [requestKey] as in [SummarizeMeetingRequest]; [language] null answers in the language
 *   of the question.
 */
@Serializable
public data class AskRequest(
    public val question: String,
    public val documents: List<AskDocument>,
    public val engineId: String? = null,
    public val language: String? = null,
    public val requestKey: String? = null,
) {
    init {
        require(question.isNotBlank()) { "question must not be blank" }
        require(question.length <= AiCapability.MAX_QUESTION_CHARS) {
            "A question holds at most ${AiCapability.MAX_QUESTION_CHARS} characters: ${question.length}"
        }
        require(documents.size in 1..AiCapability.MAX_DOCUMENTS) {
            "documents must hold between 1 and ${AiCapability.MAX_DOCUMENTS} documents: ${documents.size}"
        }
        val inline = documents.sumOf { it.text.inlineChars }
        require(inline <= AiCapability.MAX_INLINE_CHARS) {
            "Inline documents hold at most ${AiCapability.MAX_INLINE_CHARS} characters together: $inline"
        }
        requireOptionalNotBlank("engineId", engineId)
        requireLanguage(language)
        requireKey(requestKey)
    }
}

/**
 * Request of [AiCapability.Transcribe]: the passages said in the recording staged as [audioFileId].
 *
 * - [audioFileId] is the `fileId` of a recording staged with `files.upload` of the same provider. Every provider of
 *   [AiCapability.Transcribe] takes `audio/wav` with 16-bit PCM, mono, at 16 kHz, of at least
 *   [AiCapability.MIN_AUDIO_BYTES] bytes; other formats are its own choice. The request uses the staged file; a
 *   request that fails before a job starts leaves it usable.
 * - [engineId] is an engine that runs [AiTask.TRANSCRIBE]; null for the provider's default.
 * - [language] is the BCP 47 tag of the language spoken (`es-ES`, `eu-ES`); null lets the engine tell.
 * - [fromMs] skips the first milliseconds of the recording, to go on after the passages a caller already has; the
 *   passages' offsets still count from the start of the recording.
 * - [requestKey] as in [SummarizeMeetingRequest].
 */
@Serializable
public data class TranscribeRequest(
    public val audioFileId: String,
    public val engineId: String? = null,
    public val language: String? = null,
    @IntegerRange(min = 0, max = Long.MAX_VALUE)
    public val fromMs: Long = 0,
    public val requestKey: String? = null,
) {
    init {
        require(audioFileId.isNotBlank()) { "audioFileId must not be blank" }
        requireOptionalNotBlank("engineId", engineId)
        requireLanguage(language)
        require(fromMs >= 0) { "fromMs must not be negative: $fromMs" }
        requireKey(requestKey)
    }
}

/**
 * Request of [AiCapability.GetTranscriptPassages]: the passages of the transcription [jobId] from [offset] on, at most
 * [limit] of them (`1..`[AiCapability.MAX_PASSAGES_LIMIT]).
 */
@Serializable
public data class TranscriptPassagesRequest(
    public val jobId: String,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int = 0,
    @IntegerRange(min = 1, max = AiCapability.MAX_PASSAGES_LIMIT.toLong())
    public val limit: Int = AiCapability.DEFAULT_PASSAGES_LIMIT,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        require(offset >= 0) { "offset must not be negative: $offset" }
        require(limit in 1..AiCapability.MAX_PASSAGES_LIMIT) {
            "limit must be within 1..${AiCapability.MAX_PASSAGES_LIMIT}: $limit"
        }
    }
}

/** Names one job by its opaque [jobId] ([AiJob.jobId]), which must not be blank. */
@Serializable
public data class JobIdRequest(public val jobId: String) {
    init {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
    }
}
