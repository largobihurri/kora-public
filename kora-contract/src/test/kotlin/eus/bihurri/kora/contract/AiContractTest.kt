package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.ai.AiCapability
import eus.bihurri.kora.contract.ai.AiEngine
import eus.bihurri.kora.contract.ai.AiJob
import eus.bihurri.kora.contract.ai.AiTask
import eus.bihurri.kora.contract.ai.AskDocument
import eus.bihurri.kora.contract.ai.AskRequest
import eus.bihurri.kora.contract.ai.EngineKind
import eus.bihurri.kora.contract.ai.JobIdRequest
import eus.bihurri.kora.contract.ai.JobState
import eus.bihurri.kora.contract.ai.MeetingSummary
import eus.bihurri.kora.contract.ai.SummarizeMeetingRequest
import eus.bihurri.kora.contract.ai.TextInput
import eus.bihurri.kora.contract.ai.TranscribeRequest
import eus.bihurri.kora.contract.ai.TranscriptInfo
import eus.bihurri.kora.contract.ai.TranscriptPage
import eus.bihurri.kora.contract.ai.TranscriptPassage
import eus.bihurri.kora.contract.ai.TranscriptPassagesRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiContractTest {
    private fun assertMessage(expected: String, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(error.message.orEmpty().contains(expected), "expected \"$expected\" in: ${error.message}")
    }

    private val inline = TextInput(text = "Ane: we ship on Friday.")

    // ---- Texts ------------------------------------------------------------------------------------------------

    @Test
    fun `a text is given exactly one way, inline or as a staged file`() {
        assertEquals("Ane: we ship on Friday.", inline.text)
        assertEquals("file-3", TextInput(fileId = "file-3").fileId)
        assertMessage("Exactly one of text and fileId must be given") { TextInput() }
        assertMessage("Exactly one of text and fileId must be given") { TextInput("text", "file-3") }
        assertMessage("fileId must not be blank") { TextInput(fileId = " ") }
        rejectedOnTheWire(AiCapability.SummarizeMeeting, """{"transcript":{}}""")
        rejectedOnTheWire(AiCapability.SummarizeMeeting, """{"transcript":{"text":"a","fileId":"file-3"}}""")
    }

    // ---- Transcriptions ---------------------------------------------------------------------------------------

    @Test
    fun `a transcription needs only the staged recording and starts at the beginning`() {
        val request = TranscribeRequest("file-8")
        assertEquals(0L, request.fromMs)
        assertNull(request.engineId)
        assertNull(request.language)
        assertEquals(
            request,
            AiCapability.Transcribe.decodeInput(JsonCodec, """{"audioFileId":"file-8"}""".encodeToByteArray()),
        )
        undecodable(AiCapability.Transcribe, """{"language":"es-ES"}""")
    }

    @Test
    fun `a transcription checks its recording, engine, language, start and key`() {
        assertMessage("audioFileId must not be blank") { TranscribeRequest(" ") }
        assertMessage("engineId must not be blank") { TranscribeRequest("file-8", engineId = "") }
        assertMessage("language must be a BCP 47 tag") { TranscribeRequest("file-8", language = "") }
        assertMessage("fromMs must not be negative: -1") { TranscribeRequest("file-8", fromMs = -1) }
        assertMessage("requestKey must not be blank") { TranscribeRequest("file-8", requestKey = " ") }
        assertEquals("es-ES", TranscribeRequest("file-8", language = "es-ES", fromMs = 600_000).language)
        rejectedOnTheWire(AiCapability.Transcribe, """{"audioFileId":"file-8","fromMs":-5}""")
    }

    @Test
    fun `a passage starts at zero or later and ends at or after its start`() {
        assertEquals(TranscriptPassage(0, 0, ""), TranscriptPassage(0, 0, ""))
        assertMessage("startMs must not be negative: -1") { TranscriptPassage(-1, 10, "a") }
        assertMessage("endMs must not be before startMs: 9 < 10") { TranscriptPassage(10, 9, "a") }
        assertMessage("A passage holds at most 20000 characters: 20001") {
            TranscriptPassage(0, 1, "a".repeat(AiCapability.MAX_RESULT_CHARS + 1))
        }
    }

    @Test
    fun `passages are read from offset zero, fifty at a time unless the caller asks for up to two hundred`() {
        val request = TranscriptPassagesRequest("job-3")
        assertEquals(0, request.offset)
        assertEquals(50, request.limit)
        assertEquals(200, TranscriptPassagesRequest("job-3", limit = 200).limit)
        assertMessage("limit must be within 1..200: 0") { TranscriptPassagesRequest("job-3", limit = 0) }
        assertMessage("limit must be within 1..200: 201") { TranscriptPassagesRequest("job-3", limit = 201) }
        assertMessage("offset must not be negative: -1") { TranscriptPassagesRequest("job-3", offset = -1) }
        assertMessage("jobId must not be blank") { TranscriptPassagesRequest("") }
        rejectedOnTheWire(AiCapability.GetTranscriptPassages, """{"jobId":"job-3","limit":500}""")
    }

    @Test
    fun `a page of passages holds at most the result limit, except a single longer passage`() {
        val half = TranscriptPassage(0, 1, "a".repeat(AiCapability.MAX_RESULT_CHARS / 2))
        assertEquals(2, TranscriptPage(listOf(half, half), total = 3, offset = 0).passages.size)
        assertMessage("A page of passages holds at most 20000 characters: 20001") {
            TranscriptPage(listOf(half, half, TranscriptPassage(1, 2, "a")), total = 3, offset = 0)
        }
        val longest = TranscriptPassage(0, 1, "a".repeat(AiCapability.MAX_RESULT_CHARS))
        assertEquals(1, TranscriptPage(listOf(longest), total = 1, offset = 0).passages.size)
        assertMessage("total and offset must not be negative") { TranscriptPage(emptyList(), total = -1, offset = 0) }
        assertMessage("passageCount must not be negative: -1") { TranscriptInfo(-1) }
    }

    // ---- Summaries --------------------------------------------------------------------------------------------

    @Test
    fun `a summary request needs only the transcript and leaves the engine to the provider`() {
        val request = SummarizeMeetingRequest(inline)
        assertNull(request.engineId)
        assertNull(request.language)
        assertNull(request.requestKey)
        assertEquals(
            request,
            AiCapability.SummarizeMeeting.decodeInput(JsonCodec, """{"transcript":{"text":"Ane: we ship on Friday."}}""".encodeToByteArray()),
        )
        undecodable(AiCapability.SummarizeMeeting, """{"engineId":"gemma-2b"}""")
    }

    @Test
    fun `an inline transcript holds at most the inline limit, a staged one any length`() {
        val longest = "a".repeat(AiCapability.MAX_INLINE_CHARS)
        assertEquals(longest, SummarizeMeetingRequest(TextInput(text = longest)).transcript.text)
        assertMessage("An inline transcript holds at most 40000 characters: 40001") {
            SummarizeMeetingRequest(TextInput(text = longest + "a"))
        }
    }

    @Test
    fun `a summary request checks its engine, duration, language and key`() {
        assertMessage("engineId must not be blank") { SummarizeMeetingRequest(inline, engineId = "") }
        assertMessage("durationMs must not be negative: -1") { SummarizeMeetingRequest(inline, durationMs = -1) }
        assertMessage("language must be a BCP 47 tag") { SummarizeMeetingRequest(inline, language = " ") }
        assertMessage("language must be a BCP 47 tag") { SummarizeMeetingRequest(inline, language = "x".repeat(36)) }
        assertMessage("requestKey must not be blank") { SummarizeMeetingRequest(inline, requestKey = "") }
        assertMessage("requestKey must not be blank") { SummarizeMeetingRequest(inline, requestKey = "k".repeat(129)) }
        assertEquals("k".repeat(128), SummarizeMeetingRequest(inline, requestKey = "k".repeat(128)).requestKey)
        rejectedOnTheWire(AiCapability.SummarizeMeeting, """{"transcript":{"text":"a"},"durationMs":-5}""")
    }

    // ---- Questions --------------------------------------------------------------------------------------------

    @Test
    fun `a question is not blank and fits its limit`() {
        val docs = listOf(AskDocument(inline))
        assertMessage("question must not be blank") { AskRequest(" ", docs) }
        assertMessage("A question holds at most 2000 characters: 2001") { AskRequest("q".repeat(2_001), docs) }
        assertEquals(2_000, AskRequest("q".repeat(2_000), docs).question.length)
    }

    @Test
    fun `a question has between one and the maximum of documents, whose inline texts share the inline limit`() {
        assertMessage("documents must hold between 1 and 100 documents: 0") { AskRequest("When?", emptyList()) }
        assertMessage("documents must hold between 1 and 100 documents: 101") {
            AskRequest("When?", List(101) { AskDocument(TextInput(fileId = "file-$it")) })
        }
        val half = TextInput(text = "a".repeat(AiCapability.MAX_INLINE_CHARS / 2))
        assertEquals(2, AskRequest("When?", listOf(AskDocument(half), AskDocument(half))).documents.size)
        assertMessage("Inline documents hold at most 40000 characters together: 40001") {
            AskRequest("When?", listOf(AskDocument(half), AskDocument(half), AskDocument(TextInput(text = "a"))))
        }
        val staged = List(100) { AskDocument(TextInput(fileId = "file-$it")) }
        assertEquals(100, AskRequest("When?", staged).documents.size)
        rejectedOnTheWire(AiCapability.Ask, """{"question":"When?","documents":[]}""")
    }

    // ---- Engines and jobs -------------------------------------------------------------------------------------

    @Test
    fun `an engine has an id and a name, and is available but not the default unless it says so`() {
        val engine = AiEngine("gemma-2b", "Gemma 2B", EngineKind.ON_DEVICE, listOf(AiTask.SUMMARIZE_MEETING))
        assertTrue(engine.available)
        assertFalse(engine.isDefault)
        assertMessage("id must not be blank") { AiEngine(" ", "Gemma", EngineKind.ON_DEVICE, emptyList()) }
        assertMessage("name must not be blank") { AiEngine("gemma", "", EngineKind.ON_DEVICE, emptyList()) }
    }

    @Test
    fun `a job id is not blank and progress is a percentage`() {
        assertMessage("jobId must not be blank") { JobIdRequest("") }
        assertMessage("jobId must not be blank") { AiJob(" ", AiTask.ASK, JobState.QUEUED, createdAtEpochMs = 0, updatedAtEpochMs = 0) }
        assertMessage("progressPercent must be within 0..100: 101") {
            AiJob("job-1", AiTask.ASK, JobState.RUNNING, progressPercent = 101, createdAtEpochMs = 0, updatedAtEpochMs = 0)
        }
        assertMessage("progressPercent must be within 0..100: -1") {
            AiJob("job-1", AiTask.ASK, JobState.RUNNING, progressPercent = -1, createdAtEpochMs = 0, updatedAtEpochMs = 0)
        }
    }

    @Test
    fun `only succeeded, failed and cancelled jobs are finished`() {
        assertEquals(
            listOf(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED),
            JobState.entries.filter { it.isFinished },
        )
    }

    @Test
    fun `results hold at most the result limit`() {
        val limit = AiCapability.MAX_RESULT_CHARS
        assertEquals(limit, MeetingSummary("o".repeat(limit - 2), listOf("d"), listOf("a")).overview.length + 2)
        assertMessage("A summary holds at most 20000 characters: 20001") {
            MeetingSummary("o".repeat(limit - 1), listOf("d"), listOf("a"))
        }
        assertMessage("An answer holds at most 20000 characters: 20001") {
            AiJob("job-1", AiTask.ASK, JobState.SUCCEEDED, answer = "a".repeat(limit + 1), createdAtEpochMs = 0, updatedAtEpochMs = 0)
        }
    }

    @Test
    fun `a reader keeps working on a task, state, kind and failure reason from a later version`() {
        val job = AiCapability.GetJob.decodeOutput(
            JsonCodec,
            """{"jobId":"job-9","task":"translate","state":"paused","createdAtEpochMs":1,"updatedAtEpochMs":2,
               "failure":{"reason":"overheated"},"diarization":{"speakers":2}}""".encodeToByteArray(),
        )
        assertEquals(AiTask.UNKNOWN, job.task)
        assertEquals(JobState.UNKNOWN, job.state)
        assertFalse(job.state.isFinished)
        assertEquals("unknown", job.failure!!.reason.wireName)
        val engines = AiCapability.ListEngines.decodeOutput(
            JsonCodec,
            """{"engines":[{"id":"w","name":"Whisper","kind":"cloud_gpu","tasks":["translate"]}]}""".encodeToByteArray(),
        )
        assertEquals(EngineKind.UNKNOWN, engines.engines.single().kind)
        assertEquals(listOf(AiTask.UNKNOWN), engines.engines.single().tasks)
    }
}
