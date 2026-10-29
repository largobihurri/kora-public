package eus.bihurri.kora.android

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ExternalGateTest {
    private val shell = Caller(CallerKind.SHELL, "shell")
    private val denied = mutableListOf<Pair<Caller, Scope>>()
    private var granted = setOf(MediaLibrary.ReadScope)
    private var hang = false
    private var playlists = 2

    private val access = object : ExternalAccess {
        override suspend fun isEnabled() = true

        override suspend fun grantedScopes(caller: Caller) = granted

        override suspend fun onDenied(caller: Caller, scope: Scope) {
            denied += caller to scope
        }
    }

    private val node = KoraNode("sonora", policy = DefaultCallerPolicy(access)).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page ->
                if (hang) awaitCancellation()
                Page(List(playlists) { PlaylistSummary("pl-$it", "Playlist $it", 1) }.take(page.limit), playlists)
            }
        }
    }

    private val gate = ExternalGate(node, timeout = 1.seconds, maxBytes = 600)

    private fun request(id: String = "r1", limit: Int = 10) = CborCodec.encode(
        KoraRequest.serializer(),
        KoraRequest(id, "media.library", MediaLibrary.version, "list_playlists", MediaLibrary.ListPlaylists.encodeInput(CborCodec, PageRequest(limit, 0))),
    )

    private fun response(bytes: ByteArray) = CborCodec.decode(KoraResponse.serializer(), bytes)

    @Test
    fun `a binary call is answered with the typed output`() = runTest {
        val success = assertInstanceOf(KoraResponse.Success::class.java, response(gate.callBinary(shell, request())))

        assertEquals("r1", success.id)
        assertEquals(2, MediaLibrary.ListPlaylists.decodeOutput(CborCodec, success.payload).total)
    }

    @Test
    fun `a binary call without a grant is a failure, not an exception, and is reported`() = runTest {
        granted = emptySet()
        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(gate.callBinary(shell, request("r9"))))

        assertEquals("r9", failure.id)
        assertEquals(ErrorCode.UNAUTHORIZED, failure.error.code)
        assertEquals(listOf(shell to MediaLibrary.ReadScope), denied)
    }

    @Test
    fun `a malformed binary request is invalid payload`() = runTest {
        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(gate.callBinary(shell, byteArrayOf(1, 2, 3))))
        assertEquals(ErrorCode.INVALID_PAYLOAD, failure.error.code)
    }

    @Test
    fun `a binary request above the limit is payload too large`() = runTest {
        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(gate.callBinary(shell, ByteArray(601))))
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.error.code)
    }

    @Test
    fun `a binary response above the limit is payload too large and keeps the request id`() = runTest {
        playlists = 40
        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(gate.callBinary(shell, request("big", limit = 50))))

        assertEquals("big", failure.id)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, failure.error.code)
    }

    @Test
    fun `a binary handler that never answers times out`() = runTest {
        hang = true
        val failure = assertInstanceOf(KoraResponse.Failure::class.java, response(gate.callBinary(shell, request("slow"))))

        assertEquals("slow", failure.id)
        assertEquals(ErrorCode.TIMEOUT, failure.error.code)
    }

    @Test
    fun `a JSON call goes through the same policy`() = runTest {
        val call = """{"capability":"media.library","operation":"list_playlists"}"""
        val ok = Json.parseToJsonElement(gate.callJson(shell, call)).jsonObject
        assertEquals(2, ok["output"]!!.jsonObject["items"]!!.jsonArray.size)

        granted = emptySet()
        val refused = Json.parseToJsonElement(gate.callJson(shell, call)).jsonObject
        assertEquals("unauthorized", refused["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the gate failures have the documented shapes`() {
        val json = Json.parseToJsonElement(GateFailures.json(ErrorCode.UNAUTHORIZED, "no")).jsonObject
        assertEquals("false", json["ok"]!!.jsonPrimitive.content)
        assertEquals("unauthorized", json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("no", json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)

        val binary = response(GateFailures.binary("", ErrorCode.UNAUTHORIZED, "no")) as KoraResponse.Failure
        assertEquals(ErrorCode.UNAUTHORIZED, binary.error.code)
        assertTrue(binary.id.isEmpty())
    }

    @Test
    fun `the descriptor is JSON`() {
        assertEquals("sonora", Json.parseToJsonElement(gate.describe()).jsonObject["nodeId"]!!.jsonPrimitive.content)
    }
}
