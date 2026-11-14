package eus.bihurri.kora.contract.ai

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope

/**
 * AI work done by the provider app for other apps: transcribe a recording, summarize a meeting, answer a question
 * over documents, with an engine the caller picks from the ones the provider offers (models on the phone, or APIs
 * whose keys stay in the provider). The caller never holds a model or a key. [AiEngine.tasks] says which engines
 * transcribe, summarize or answer.
 *
 * **Jobs.** Loading a model and transcribing or summarizing a meeting on a phone takes minutes, far beyond the time
 * limit of one Kora call (5 seconds by default), so the work is a job:
 * 1. [Transcribe], [SummarizeMeeting] or [Ask] answers at once with a new [AiJob] in [JobState.QUEUED]. The provider
 *    only checks and accepts the request within the call; it never waits for the engine.
 * 2. The provider does the work in its own process, in a foreground service, so the caller may close and the work
 *    goes on. Kora reaches a provider app that is not running, so the provider need not be open either.
 * 3. The caller collects the result later, either way:
 *    - [GetJob] answers the job as it is now, with the result once it [JobState.SUCCEEDED]; the passages of a
 *      transcription are read in pages with [GetTranscriptPassages], also while it runs and after it failed;
 *    - [JobChanged] pushes the jobs of the subscribing caller: first every job of that caller the provider keeps,
 *      then each change (state, progress), until the subscription ends.
 *    A caller that was closed meanwhile asks [GetJob] for the jobs it started when it runs again.
 * 4. [CancelJob] stops a job that has not finished.
 *
 * Binding rules for every provider:
 * - **Engines.** [ListEngines] answers from what the provider knows, without loading a model. An engine id is stable
 *   across restarts. A request without `engineId` runs on the engine that [AiEngine.isDefault] for its task.
 * - **Accepting.** A malformed request, an engine id the provider does not offer (or that does not run the task) and
 *   a file id it does not hold are `invalid_payload` at once, and no job starts. An engine that exists but cannot run
 *   ([AiEngine.available] false, or found out later) fails the job with [FailureReason.ENGINE_UNAVAILABLE]; a staged
 *   recording or text the engine cannot read (a format it does not take, a damaged file) fails it with
 *   [FailureReason.UNSUPPORTED_INPUT].
 * - **Ownership.** A job belongs to the caller that started it. From any other caller its id is unknown
 *   (`invalid_payload`), and [JobChanged] never shows it.
 * - **Keeping.** The provider keeps an unfinished job until it finishes, and a finished one, with its result, at
 *   least [RETENTION_HOURS] hours after it finished. After that its id is unknown (`invalid_payload`). A job left
 *   unfinished when the provider stopped ends [JobState.FAILED] with [FailureReason.INTERRUPTED], unless the
 *   provider resumes it.
 * - **Repeating a request.** A request with a `requestKey` ([TranscribeRequest], [SummarizeMeetingRequest],
 *   [AskRequest]) that
 *   the same caller already used for a job the provider keeps answers that job instead of starting another, unless
 *   that job failed or was cancelled, in which case a new job starts. The provider does not compare the rest of the
 *   two requests. A caller that gets `timeout` for a request with a key sends it again and gets the job, whether or
 *   not the first one arrived.
 * - **Cancelling.** [CancelJob] moves a queued or running job to [JobState.CANCELLED] and answers it; a finished job
 *   is answered unchanged.
 * - **Recordings.** A recording is staged with `files.upload` and named by [TranscribeRequest.audioFileId]: an hour of
 *   16 kHz mono 16-bit PCM is about 115 MB, so it always travels as a stream, never in a payload. Every provider of
 *   [Transcribe] takes `audio/wav` in that format up to at least [MIN_AUDIO_BYTES] bytes.
 * - **Passages.** A transcription keeps its passages in order with the job. [AiJob.transcript] counts them; a caller
 *   reads them with [GetTranscriptPassages] from `offset` 0 on, in pages, while the job runs (the passages so far),
 *   once it succeeded (all of them) or after it failed (those kept before it failed). An id of a job that is not a
 *   transcription is `invalid_payload`.
 * - **Sizes.** Inline texts hold at most [MAX_INLINE_CHARS] characters per request; longer texts are staged with
 *   `files.upload` ([TextInput]). A result, and a page of passages, holds at most [MAX_RESULT_CHARS] characters. Both
 *   fit the 256 KiB a Kora message carries on every transport, including an MCP answer that repeats the text.
 * - **Effects.** [ListEngines], [GetJob] and [GetTranscriptPassages] are [OperationEffect.READ]. [Transcribe],
 *   [SummarizeMeeting], [Ask] and [CancelJob] are [OperationEffect.WRITE]: they start or stop work, which may cost
 *   money on an API engine.
 * - **Who offers it.** A provider that offers `ai` also offers `files`, for staged recordings and texts.
 * - **Transports.** Every operation and the event work on every transport; only staging a recording or a text as a
 *   file needs one that carries streams (Binder between apps on one phone).
 *
 * Reads ([ListEngines], [GetJob], [GetTranscriptPassages]) and the event need [ReadScope]; starting and cancelling
 * jobs need [RunScope].
 *
 * A later minor version adds tasks as new operations that answer the same [AiJob], with a new [AiTask] constant
 * (older readers see [AiTask.UNKNOWN]) and a new optional result field.
 *
 * Version history:
 * - 1.0: [ListEngines], [Transcribe], [SummarizeMeeting], [Ask], [GetJob], [GetTranscriptPassages], [CancelJob],
 *   [JobChanged].
 */
public object AiCapability : Capability(CapabilityId("ai"), CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("ai.read")
    public val RunScope: Scope = Scope("ai.run")

    /**
     * Largest inline text of one request, in characters: 40 000. In scripts that take three bytes a character that
     * is about 117 KiB, so a request carrying it fits the 256 KiB of one message with room for the rest.
     */
    public const val MAX_INLINE_CHARS: Int = 40_000

    /**
     * Largest result, in characters: 20 000, about 59 KiB in three-byte scripts. An MCP answer holds it twice (the
     * text block and `structuredContent`), about 118 KiB.
     */
    public const val MAX_RESULT_CHARS: Int = 20_000

    /** Largest [AskRequest.question], in characters. */
    public const val MAX_QUESTION_CHARS: Int = 2_000

    /** Largest number of [AskRequest.documents]. */
    public const val MAX_DOCUMENTS: Int = 100

    /** Largest [SummarizeMeetingRequest.requestKey] and [AskRequest.requestKey], in characters. */
    public const val MAX_REQUEST_KEY_LENGTH: Int = 128

    /** Largest `language` tag of a request, in characters (the longest BCP 47 tags in use are far shorter). */
    public const val MAX_LANGUAGE_LENGTH: Int = 35

    /** Largest `limit` of [GetTranscriptPassages]. */
    public const val MAX_PASSAGES_LIMIT: Int = 200

    /** `limit` of [GetTranscriptPassages] when the caller does not choose. */
    public const val DEFAULT_PASSAGES_LIMIT: Int = 50

    /**
     * Smallest size of a staged recording every provider of [Transcribe] takes: 512 MiB, about 4.5 hours of 16 kHz
     * mono 16-bit PCM WAV.
     */
    public const val MIN_AUDIO_BYTES: Long = 512L * 1024 * 1024

    /** Hours a finished job and its result are kept, at least. */
    public const val RETENTION_HOURS: Int = 24

    public val ListEngines: OperationSpec<NoInput, EngineList> = operation(
        name = "list_engines",
        input = NoInput.serializer(),
        output = EngineList.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "List the engines this app offers for AI work: id, display name, kind (on_device or api), the " +
            "tasks each runs (transcribe, summarize_meeting, ask), whether it can run now and whether it is the " +
            "default for its tasks.",
    )

    public val Transcribe: OperationSpec<TranscribeRequest, AiJob> = operation(
        name = "transcribe",
        input = TranscribeRequest.serializer(),
        output = AiJob.serializer(),
        scope = RunScope,
        effect = OperationEffect.WRITE,
        description = "Start a job that turns a recording staged with files.upload (audioFileId; audio/wav, 16-bit " +
            "PCM, mono, 16 kHz) into passages with their start and end offsets, with an optional language (BCP 47) " +
            "and engineId (omit for the default engine); fromMs skips the start of the recording. Answers the " +
            "queued job at once; read the passages later with get_transcript_passages.",
    )

    public val SummarizeMeeting: OperationSpec<SummarizeMeetingRequest, AiJob> = operation(
        name = "summarize_meeting",
        input = SummarizeMeetingRequest.serializer(),
        output = AiJob.serializer(),
        scope = RunScope,
        effect = OperationEffect.WRITE,
        description = "Start a job that summarizes a meeting transcript (inline text, or the fileId of a " +
            "staged UTF-8 text file) into an overview, decisions and action items, with engineId (omit for the " +
            "default engine). Answers the queued job at once; read the result later with get_job. A repeated " +
            "requestKey answers the job it started instead of starting another.",
    )

    public val Ask: OperationSpec<AskRequest, AiJob> = operation(
        name = "ask",
        input = AskRequest.serializer(),
        output = AiJob.serializer(),
        scope = RunScope,
        effect = OperationEffect.WRITE,
        description = "Start a job that answers a question from the given documents only, such as the transcripts " +
            "of earlier meetings, with engineId (omit for the default engine). Answers the queued job at once; read " +
            "the answer later with get_job. A repeated requestKey answers the job it started instead of starting " +
            "another.",
    )

    public val GetJob: OperationSpec<JobIdRequest, AiJob> = operation(
        name = "get_job",
        input = JobIdRequest.serializer(),
        output = AiJob.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Get a job you started: its state, progress and, once it succeeded, its result (summary or " +
            "answer), or why it failed. Finished jobs are kept at least 24 hours.",
    )

    public val GetTranscriptPassages: OperationSpec<TranscriptPassagesRequest, TranscriptPage> = operation(
        name = "get_transcript_passages",
        input = TranscriptPassagesRequest.serializer(),
        output = TranscriptPage.serializer(),
        scope = ReadScope,
        effect = OperationEffect.READ,
        description = "Read the passages of a transcription job you started, in order, from offset on (at most " +
            "limit, fewer when their text passes 20000 characters): each with startMs, endMs and text, and the " +
            "total so far. Works while the job runs, once it succeeded and after it failed.",
    )

    public val CancelJob: OperationSpec<JobIdRequest, AiJob> = operation(
        name = "cancel_job",
        input = JobIdRequest.serializer(),
        output = AiJob.serializer(),
        scope = RunScope,
        effect = OperationEffect.WRITE,
        description = "Cancel a job you started that has not finished; a finished job is answered unchanged.",
    )

    public val JobChanged: EventSpec<AiJob> = event(
        name = "job_changed",
        serializer = AiJob.serializer(),
        scope = ReadScope,
        description = "A job of yours changed (state, progress or result); a new subscriber first receives every " +
            "job of its own the provider keeps.",
    )
}
