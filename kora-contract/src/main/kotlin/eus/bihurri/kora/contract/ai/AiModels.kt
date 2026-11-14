package eus.bihurri.kora.contract.ai

import eus.bihurri.kora.contract.IntegerRange
import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import kotlinx.serialization.Serializable

/** Where an engine runs: on the phone, or behind an API the provider reaches with its own keys. */
@Serializable(with = EngineKind.Serializer::class)
public enum class EngineKind(override val wireName: String) : WireEnum {
    /** A model that runs on the device, such as a small language model loaded by the provider. */
    ON_DEVICE("on_device"),

    /** A model behind a remote API (Claude, for example), reached with keys that stay in the provider. */
    API("api"),

    /** A kind added in a later version. */
    UNKNOWN("unknown");

    internal object Serializer : TolerantEnumSerializer<EngineKind>(
        "eus.bihurri.kora.contract.ai.EngineKind",
        entries,
        UNKNOWN,
    )
}

/**
 * A kind of work an engine can do, and the kind of an [AiJob]. Each constant is named after the operation of
 * [AiCapability] that starts it.
 */
@Serializable(with = AiTask.Serializer::class)
public enum class AiTask(override val wireName: String) : WireEnum {
    /** Speech to text: the passages said in a recording, [AiCapability.Transcribe]. */
    TRANSCRIBE("transcribe"),

    /** Summary of a meeting transcript: [AiCapability.SummarizeMeeting]. */
    SUMMARIZE_MEETING("summarize_meeting"),

    /** Answer to a question over a set of documents: [AiCapability.Ask]. */
    ASK("ask"),

    /** A task added in a later version. */
    UNKNOWN("unknown");

    internal object Serializer : TolerantEnumSerializer<AiTask>(
        "eus.bihurri.kora.contract.ai.AiTask",
        entries,
        UNKNOWN,
    )
}

/**
 * One engine the provider offers: [id] names it in requests, [name] is what a user picks from, [kind] says where it
 * runs and [tasks] what it can do.
 *
 * [available] is false when the engine is known but cannot run now (its model is not downloaded, its API has no key);
 * a caller may still show it, greyed out. [isDefault] marks the engine the provider uses for its [tasks] when a
 * request names none; at most one engine is the default of each task.
 *
 * [id] is opaque and stable across restarts of the provider, so a caller can keep it in its settings. It is never a
 * path or a model file name a caller could act on.
 */
@Serializable
public data class AiEngine(
    public val id: String,
    public val name: String,
    public val kind: EngineKind,
    public val tasks: List<AiTask>,
    public val available: Boolean = true,
    public val isDefault: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
    }
}

/** Answer of [AiCapability.ListEngines]: every engine the provider offers, in the order it shows them. */
@Serializable
public data class EngineList(public val engines: List<AiEngine>)

/** Where an [AiJob] stands. A job moves from [QUEUED] through [RUNNING] to one of the three finished states. */
@Serializable(with = JobState.Serializer::class)
public enum class JobState(override val wireName: String) : WireEnum {
    /** Accepted and waiting for its engine. */
    QUEUED("queued"),

    /** Its engine is working on it: loading a model, reading the input or writing the result. */
    RUNNING("running"),

    /** Finished with a result: the result field of its [AiJob.task] is set. */
    SUCCEEDED("succeeded"),

    /** Finished without a result: [AiJob.failure] says why. */
    FAILED("failed"),

    /** Stopped by [AiCapability.CancelJob] before it finished. */
    CANCELLED("cancelled"),

    /** A state added in a later version. */
    UNKNOWN("unknown");

    /** True for [SUCCEEDED], [FAILED] and [CANCELLED]: the job will not change any more. */
    public val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    internal object Serializer : TolerantEnumSerializer<JobState>(
        "eus.bihurri.kora.contract.ai.JobState",
        entries,
        UNKNOWN,
    )
}

/** Why a job [JobState.FAILED]. */
@Serializable(with = FailureReason.Serializer::class)
public enum class FailureReason(override val wireName: String) : WireEnum {
    /** The engine cannot run: its model is missing or could not be loaded, its API has no key, or it was removed. */
    ENGINE_UNAVAILABLE("engine_unavailable"),

    /** A staged recording or text the engine cannot read: a format it does not take, or a damaged file. */
    UNSUPPORTED_INPUT("unsupported_input"),

    /** The input is more than the engine can take, even in parts. */
    INPUT_TOO_LARGE("input_too_large"),

    /** An API engine could not be reached, or refused the request for its quota. */
    NETWORK("network"),

    /** The engine ran and gave no usable result. */
    ENGINE_FAILED("engine_failed"),

    /** The provider stopped before the job finished (the phone restarted, the system ended it). */
    INTERRUPTED("interrupted"),

    /** A reason added in a later version. */
    UNKNOWN("unknown");

    internal object Serializer : TolerantEnumSerializer<FailureReason>(
        "eus.bihurri.kora.contract.ai.FailureReason",
        entries,
        UNKNOWN,
    )
}

/** Why a job failed: a [reason] a caller acts on, and a [message] for logs, which callers never parse. */
@Serializable
public data class JobFailure(
    public val reason: FailureReason,
    public val message: String = "",
)

/**
 * Result of [AiCapability.SummarizeMeeting]: an [overview] of what the meeting was about (a few paragraphs), what was
 * decided ([decisions]) and what someone has to do ([actionItems]), each item one sentence as said in the meeting.
 *
 * All the text together holds at most [AiCapability.MAX_RESULT_CHARS] characters.
 */
@Serializable
public data class MeetingSummary(
    public val overview: String,
    public val decisions: List<String> = emptyList(),
    public val actionItems: List<String> = emptyList(),
) {
    init {
        val chars = overview.length + decisions.sumOf { it.length } + actionItems.sumOf { it.length }
        require(chars <= AiCapability.MAX_RESULT_CHARS) {
            "A summary holds at most ${AiCapability.MAX_RESULT_CHARS} characters: $chars"
        }
    }
}

/**
 * One job of the provider, as [AiCapability.SummarizeMeeting], [AiCapability.Ask], [AiCapability.GetJob],
 * [AiCapability.CancelJob] and [AiCapability.JobChanged] answer it.
 *
 * - [jobId] is opaque and belongs to the caller that started the job.
 * - [task] is the kind of work; [engineId] the engine that runs it, the provider's default when the request named
 *   none.
 * - [progressPercent] is within `0..100` when the provider can tell, null when it cannot.
 * - [createdAtEpochMs] is when the provider accepted the job, [updatedAtEpochMs] when it last changed.
 * - [requestKey] repeats the key of the request that started it, if it had one.
 * - The result is in the field of its task once the job [JobState.SUCCEEDED]: [transcript] for
 *   [AiTask.TRANSCRIBE] (its passages are read with [AiCapability.GetTranscriptPassages]), [summary] for
 *   [AiTask.SUMMARIZE_MEETING], [answer] for [AiTask.ASK]. A later version adds one optional field per new task.
 *   [transcript] may also be set while a transcription runs or after it failed, counting the passages so far.
 * - [failure] is set once the job [JobState.FAILED].
 *
 * [answer] holds at most [AiCapability.MAX_RESULT_CHARS] characters.
 */
@Serializable
public data class AiJob(
    public val jobId: String,
    public val task: AiTask,
    public val state: JobState,
    public val engineId: String? = null,
    @IntegerRange(min = 0, max = 100)
    public val progressPercent: Int? = null,
    public val createdAtEpochMs: Long,
    public val updatedAtEpochMs: Long,
    public val requestKey: String? = null,
    public val transcript: TranscriptInfo? = null,
    public val summary: MeetingSummary? = null,
    public val answer: String? = null,
    public val failure: JobFailure? = null,
) {
    init {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        require(progressPercent == null || progressPercent in 0..100) {
            "progressPercent must be within 0..100: $progressPercent"
        }
        require(answer == null || answer.length <= AiCapability.MAX_RESULT_CHARS) {
            "An answer holds at most ${AiCapability.MAX_RESULT_CHARS} characters: ${answer?.length}"
        }
    }
}

/**
 * One passage said in a recording: its [text], from [startMs] to [endMs] after the start of the recording (also when
 * the transcription started later, at [TranscribeRequest.fromMs]). `0 <= startMs <= endMs`; [text] holds at most
 * [AiCapability.MAX_RESULT_CHARS] characters.
 */
@Serializable
public data class TranscriptPassage(
    @IntegerRange(min = 0, max = Long.MAX_VALUE)
    public val startMs: Long,
    @IntegerRange(min = 0, max = Long.MAX_VALUE)
    public val endMs: Long,
    public val text: String,
) {
    init {
        require(startMs >= 0) { "startMs must not be negative: $startMs" }
        require(endMs >= startMs) { "endMs must not be before startMs: $endMs < $startMs" }
        require(text.length <= AiCapability.MAX_RESULT_CHARS) {
            "A passage holds at most ${AiCapability.MAX_RESULT_CHARS} characters: ${text.length}"
        }
    }
}

/**
 * What a transcription has produced, in [AiJob.transcript]: [passageCount] passages so far (all of them once the job
 * [JobState.SUCCEEDED]), read in pages with [AiCapability.GetTranscriptPassages], and the [language] the engine heard
 * (a BCP 47 tag), when it can tell. A recording of an hour is far more text than one message carries, so the passages
 * are never inside the job.
 */
@Serializable
public data class TranscriptInfo(
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val passageCount: Int,
    public val language: String? = null,
) {
    init {
        require(passageCount >= 0) { "passageCount must not be negative: $passageCount" }
    }
}

/**
 * Answer of [AiCapability.GetTranscriptPassages]: [passages] from [offset] on, of [total] passages so far.
 *
 * The page holds at most the `limit` asked for, and fewer when their text together would pass
 * [AiCapability.MAX_RESULT_CHARS] characters (always at least one passage while one remains), so a caller reads on
 * from `offset + passages.size` until it reaches [total].
 */
@Serializable
public data class TranscriptPage(
    public val passages: List<TranscriptPassage>,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val total: Int,
    @IntegerRange(min = 0, max = Int.MAX_VALUE.toLong())
    public val offset: Int,
) {
    init {
        require(total >= 0 && offset >= 0) { "total and offset must not be negative: $total, $offset" }
        val chars = passages.sumOf { it.text.length }
        require(passages.size <= 1 || chars <= AiCapability.MAX_RESULT_CHARS) {
            "A page of passages holds at most ${AiCapability.MAX_RESULT_CHARS} characters: $chars"
        }
    }
}
