package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JsonGateTest {
    private val audit = RecordingAudit()
    private val access = FakeExternalAccess(enabled = true)
    private val playlists = List(3) { PlaylistSummary("pl-$it", "Playlist $it", it + 1) }
    private var pages = mutableListOf<eus.bihurri.kora.contract.media.PageRequest>()
    private var played = 0
    private var hang = false

    private val node = KoraNode(
        nodeId = "sonora",
        displayName = "Sonora",
        policy = DefaultCallerPolicy(access),
        audit = audit,
        clock = FakeClock(),
    ).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page ->
                pages += page
                if (hang) awaitCancellation()
                Page(playlists.drop(page.offset).take(page.limit), total = playlists.size, offset = page.offset)
            }
        }
        provideStubbed(MediaPlayback) {
            on(MediaPlayback.Play) { played++ }
            on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.IDLE) }
        }
    }

    private val gate = JsonGate(node, timeout = 1.seconds, maxRequestBytes = 2_000, maxResponseBytes = 2_000)

    private fun granted(scope: eus.bihurri.kora.contract.Scope) {
        access.grants[SHELL] = setOf(scope)
    }

    private suspend fun call(argument: String, caller: Caller = SELF): JsonObject =
        Json.parseToJsonElement(gate.call(caller, argument)).jsonObject

    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun assertError(expected: ErrorCode, answer: JsonObject) {
        assertEquals(false, answer["ok"]!!.jsonPrimitive.boolean, answer.toString())
        assertEquals(expected.wireName, answer.errorCode(), answer.toString())
    }

    private val list = """{"capability":"media.library","version":"1.0","operation":"list_playlists","input":{"limit":2,"offset":1}}"""

    @Test
    fun `list playlists round trips as typed nested JSON`() = runTest {
        val answer = call(list)

        assertEquals(true, answer["ok"]!!.jsonPrimitive.boolean)
        val output = answer["output"]!!.jsonObject
        assertEquals(3, output["total"]!!.jsonPrimitive.int)
        assertEquals(1, output["offset"]!!.jsonPrimitive.int)
        val items = output["items"]!!.jsonArray
        assertEquals(listOf("pl-1", "pl-2"), items.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(3, items[1].jsonObject["trackCount"]!!.jsonPrimitive.int)
        assertEquals(listOf(eus.bihurri.kora.contract.media.PageRequest(2, 1)), pages)
    }

    @Test
    fun `version and input are optional`() = runTest {
        val answer = call("""{"capability":"media.library","operation":"list_playlists"}""")

        assertEquals(true, answer["ok"]!!.jsonPrimitive.boolean)
        assertEquals(3, answer["output"]!!.jsonObject["items"]!!.jsonArray.size)
        assertEquals(listOf(eus.bihurri.kora.contract.media.PageRequest()), pages)
    }

    @Test
    fun `unknown keys are ignored in the request and in the input`() = runTest {
        val answer = call(
            """{"capability":"media.library","operation":"list_playlists","note":"x","input":{"limit":1,"future":true}}""",
        )
        assertEquals(1, answer["output"]!!.jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun `an operation that returns nothing answers an empty object`() = runTest {
        val answer = call("""{"capability":"media.playback","operation":"play"}""")
        assertEquals(JsonObject(emptyMap()), answer["output"])
        assertEquals(1, played)
    }

    @Test
    fun `an operation without input accepts an object or nothing`() = runTest {
        val answer = call("""{"capability":"media.playback","operation":"get_state","input":{}}""")
        assertEquals("idle", answer["output"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an incompatible version is reported by the node`() = runTest {
        assertError(ErrorCode.INCOMPATIBLE_VERSION, call("""{"capability":"media.library","version":"2.0","operation":"list_playlists"}"""))
        assertError(ErrorCode.INCOMPATIBLE_VERSION, call("""{"capability":"media.library","version":"1.9","operation":"list_playlists"}"""))
    }

    @Test
    fun `unknown capability and operation use the usual codes`() = runTest {
        assertError(ErrorCode.UNKNOWN_CAPABILITY, call("""{"capability":"nope.nothing","operation":"x"}"""))
        assertError(ErrorCode.UNKNOWN_CAPABILITY, call("""{"capability":"Not A Capability","operation":"x"}"""))
        assertError(ErrorCode.UNKNOWN_CAPABILITY, call("""{"capability":"fitness.session","operation":"get_session"}"""))
        assertError(ErrorCode.UNKNOWN_OPERATION, call("""{"capability":"media.library","operation":"nope"}"""))
    }

    @Test
    fun `malformed requests are invalid payload`() = runTest {
        for (bad in listOf(
            "not json",
            "[]",
            "\"text\"",
            "{}",
            """{"capability":"media.library"}""",
            """{"operation":"list_playlists"}""",
            """{"capability":1,"operation":"list_playlists"}""",
            """{"capability":"media.library","operation":"list_playlists","version":"one"}""",
            """{"capability":"media.library","operation":"list_playlists","version":"1"}""",
            """{"capability":"media.library","operation":"list_playlists","version":1.0}""",
            """{"capability":"media.library","operation":"list_playlists","version":"-1.0"}""",
        )) {
            assertError(ErrorCode.INVALID_PAYLOAD, call(bad))
        }
    }

    @Test
    fun `an input that does not fit the operation is invalid payload`() = runTest {
        for (input in listOf("""{"limit":"ten"}""", """{"limit":0}""", """{"offset":-1}""", "[1,2]", "3", "null", """{"limit":1.5}""")) {
            assertError(
                ErrorCode.INVALID_PAYLOAD,
                call("""{"capability":"media.library","operation":"list_playlists","input":$input}"""),
            )
        }
        assertTrue(pages.isEmpty())
    }

    @Test
    fun `a shell caller without a grant is refused and audited`() = runTest {
        val answer = call(list, SHELL)

        assertError(ErrorCode.UNAUTHORIZED, answer)
        assertTrue(pages.isEmpty())
        assertEquals(listOf(SHELL to MediaLibrary.ReadScope), access.denied)
        val entry = audit.entries.single()
        assertEquals(SHELL, entry.caller)
        assertEquals(eus.bihurri.kora.core.AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), entry.outcome)
    }

    @Test
    fun `a shell caller with the grant is served and the scope is exact`() = runTest {
        granted(MediaLibrary.ReadScope)
        assertEquals(true, call(list, SHELL)["ok"]!!.jsonPrimitive.boolean)
        assertError(ErrorCode.UNAUTHORIZED, call("""{"capability":"media.playback","operation":"get_state"}""", SHELL))
        assertEquals(listOf(SHELL to eus.bihurri.kora.contract.media.MediaPlayback.ReadScope), access.denied)
    }

    @Test
    fun `a refused caller learns nothing from malformed input`() = runTest {
        val bad = """{"capability":"media.library","operation":"list_playlists","input":{"limit":"x"}}"""
        assertError(ErrorCode.UNAUTHORIZED, call(bad, SHELL))
        assertEquals(1, access.denied.size)

        granted(MediaLibrary.ReadScope)
        assertError(ErrorCode.INVALID_PAYLOAD, call(bad, SHELL))
        assertEquals(2, audit.entries.size)
    }

    @Test
    fun `a request above the limit is payload too large`() = runTest {
        val big = """{"capability":"media.library","operation":"list_playlists","note":"${"x".repeat(2_000)}"}"""
        assertError(ErrorCode.PAYLOAD_TOO_LARGE, call(big))
        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `a response above the limit is payload too large`() = runTest {
        val small = JsonGate(node, maxResponseBytes = 60)
        val answer = Json.parseToJsonElement(small.call(SELF, list)).jsonObject
        assertError(ErrorCode.PAYLOAD_TOO_LARGE, answer)
    }

    @Test
    fun `a handler that never answers times out`() = runTest {
        hang = true
        assertError(ErrorCode.TIMEOUT, call(list))
    }

    @Test
    fun `cancelling the caller propagates instead of answering`() = runTest(UnconfinedTestDispatcher()) {
        hang = true
        var answer: String? = null
        val job = launch { answer = gate.call(SELF, list) }
        assertEquals(1, pages.size, "the handler was reached")

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(answer)
        assertTrue(audit.entries.isEmpty(), "cancelled calls are not audited")
    }

    @Test
    fun `the descriptor is JSON`() {
        val descriptor = Json.parseToJsonElement(gate.describe()).jsonObject
        assertEquals("sonora", descriptor["nodeId"]!!.jsonPrimitive.content)
        assertEquals("Sonora", descriptor["displayName"]!!.jsonPrimitive.content)
        val capabilities = assertInstanceOf(JsonArray::class.java, descriptor["capabilities"])
        assertEquals(listOf("media.library", "media.playback"), capabilities.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive(1), capabilities[0].jsonObject["version"]!!.jsonObject["major"])
    }

    @Test
    fun `limits must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { JsonGate(node, maxRequestBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { JsonGate(node, timeout = 0.seconds) }
    }
}
