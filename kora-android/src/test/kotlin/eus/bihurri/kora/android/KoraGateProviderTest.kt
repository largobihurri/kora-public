package eus.bihurri.kora.android

import android.net.Uri
import android.os.Bundle
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** The thin adapter: manifest wiring, thread rule, caller uid, and the shape of the returned bundles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KoraGateProviderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val uri get() = Uri.parse("content://${KoraGate.authority(app.packageName)}")
    private val denied = mutableListOf<Pair<Caller, Scope>>()
    private var shellGranted = true

    private val access = object : ExternalAccess {
        override suspend fun isEnabled() = true

        override suspend fun grantedScopes(caller: Caller): Set<Scope> =
            if (caller.kind == CallerKind.SHELL && shellGranted) setOf(MediaLibrary.ReadScope) else emptySet()

        override suspend fun onDenied(caller: Caller, scope: Scope) {
            denied += caller to scope
        }
    }

    @After
    fun tearDown() {
        KoraAndroid.uninstall()
        ShadowBinder.reset()
    }

    private fun install() {
        KoraAndroid.install(app, policy = DefaultCallerPolicy(access)).provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { Page(listOf(PlaylistSummary("pl-1", "One", 3)), 1) }
        }
    }

    /** Calls the gate from a background thread, as Binder threads do; the test thread is the main thread. */
    private fun gate(method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit(Callable { app.contentResolver.call(uri, method, arg, extras) }).get()!!
        } finally {
            executor.shutdown()
        }
    }

    private val list = """{"capability":"media.library","operation":"list_playlists","input":{"limit":5,"offset":0}}"""

    private fun json(bundle: Bundle) = Json.parseToJsonElement(bundle.getString(KoraGate.KEY_JSON)!!).jsonObject

    @Test
    fun `the library manifest exports the gate at applicationId dot kora without a permission`() {
        val provider = app.packageManager.resolveContentProvider(KoraGate.authority(app.packageName), 0)
        assertNotNull(provider)
        assertEquals(KoraGateProvider::class.java.name, provider!!.name)
        assertTrue(provider.exported)
        assertNull(provider.readPermission)
        assertNull(provider.writePermission)
    }

    @Test
    fun `the main thread is refused`() {
        install()
        val result = app.contentResolver.call(uri, KoraGate.METHOD_DESCRIBE, null, null)!!

        assertEquals("provider_unavailable", result.getString(KoraGate.KEY_ERROR_CODE))
        assertEquals("false", json(result)["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without an installed node the gate is unavailable`() {
        val result = gate(KoraGate.METHOD_CALL_JSON, list)

        assertEquals("provider_unavailable", result.getString(KoraGate.KEY_ERROR_CODE))
        assertEquals("provider_unavailable", json(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `describe returns the descriptor`() {
        install()
        val result = gate(KoraGate.METHOD_DESCRIBE)

        assertNull(result.getString(KoraGate.KEY_ERROR_CODE))
        assertEquals(app.packageName, json(result)["nodeId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the shell is served the scopes it was granted`() {
        install()
        ShadowBinder.setCallingUid(2_000)

        val result = json(gate(KoraGate.METHOD_CALL_JSON, list))

        assertEquals("true", result["ok"]!!.jsonPrimitive.content)
        assertEquals("pl-1", result["output"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the shell without a grant is refused and reported`() {
        install()
        shellGranted = false
        ShadowBinder.setCallingUid(2_000)

        val bundle = gate(KoraGate.METHOD_CALL_JSON, list)

        assertNull("a refusal by the policy is an answer, not a gate error", bundle.getString(KoraGate.KEY_ERROR_CODE))
        assertEquals("unauthorized", json(bundle)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(listOf(Caller(CallerKind.SHELL, "shell") to MediaLibrary.ReadScope), denied)
    }

    @Test
    fun `an unidentifiable caller is refused by the gate`() {
        install()
        ShadowBinder.setCallingUid(app.applicationInfo.uid + 4_321)

        val bundle = gate(KoraGate.METHOD_CALL_JSON, list)

        assertEquals("unauthorized", bundle.getString(KoraGate.KEY_ERROR_CODE))
        assertTrue(denied.isEmpty())
    }

    @Test
    fun `a binary call returns a CBOR response`() {
        install()
        val request = CborCodec.encode(
            KoraRequest.serializer(),
            KoraRequest("r1", "media.library", MediaLibrary.version, "list_playlists", MediaLibrary.ListPlaylists.encodeInput(CborCodec, PageRequest())),
        )

        val bundle = gate(KoraGate.METHOD_CALL, extras = Bundle().apply { putByteArray(KoraGate.EXTRA_REQUEST, request) })

        val success = CborCodec.decode(KoraResponse.serializer(), bundle.getByteArray(KoraGate.KEY_RESPONSE)!!) as KoraResponse.Success
        assertEquals("r1", success.id)
        assertEquals(1, MediaLibrary.ListPlaylists.decodeOutput(CborCodec, success.payload).items.size)
    }

    @Test
    fun `missing inputs and unknown methods are gate errors`() {
        install()

        assertEquals("invalid_payload", gate(KoraGate.METHOD_CALL_JSON).getString(KoraGate.KEY_ERROR_CODE))
        val binary = gate(KoraGate.METHOD_CALL)
        assertEquals("invalid_payload", binary.getString(KoraGate.KEY_ERROR_CODE))
        assertTrue(CborCodec.decode(KoraResponse.serializer(), binary.getByteArray(KoraGate.KEY_RESPONSE)!!) is KoraResponse.Failure)
        assertEquals("unknown_operation", gate("kora.nope").getString(KoraGate.KEY_ERROR_CODE))
    }

    private fun mcp(message: String, encoded: Boolean = false): Bundle =
        gate(KoraGate.METHOD_MCP, if (encoded) KoraGate.PREFIX_BASE64 + b64(message) else message)

    private fun b64(text: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())

    private fun mcpJson(bundle: Bundle) = Json.parseToJsonElement(bundle.getString(KoraGate.KEY_JSON)!!).jsonObject

    @Test
    fun `mcp keeps the shell session between calls and serves the shell grants`() {
        install()
        ShadowBinder.setCallingUid(2_000)

        val init = mcpJson(mcp("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}"""))
        val initialized = mcp("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val tools = mcpJson(mcp("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
        val called = mcpJson(
            mcp("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"media_library__list_playlists","arguments":{}}}"""),
        )

        assertEquals("2025-11-25", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("a notification has no answer", initialized.keySet().isEmpty())
        assertTrue(tools["result"]!!.jsonObject["tools"]!!.jsonArray.isNotEmpty())
        assertEquals("false", called["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)
        assertTrue("granted calls are not reported", denied.isEmpty())
    }

    @Test
    fun `mcp tool calls without a grant are tool errors and reported`() {
        install()
        shellGranted = false
        ShadowBinder.setCallingUid(2_000)
        mcp("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")

        val bundle = mcp("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"media_library__list_playlists","arguments":{}}}""", encoded = true)
        val called = Json.parseToJsonElement(
            java.util.Base64.getUrlDecoder().decode(bundle.getString(KoraGate.KEY_JSON_BASE64)!!).decodeToString(),
        ).jsonObject

        assertFalse("an encoded request is answered encoded only", bundle.containsKey(KoraGate.KEY_JSON))
        assertEquals(listOf(Caller(CallerKind.SHELL, "shell") to MediaLibrary.ReadScope), denied)
        assertEquals("true", called["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mcp gate refusals carry error keys and no json`() {
        install()
        ShadowBinder.setCallingUid(2_000)
        val missing = gate(KoraGate.METHOD_MCP)
        val badBase64 = gate(KoraGate.METHOD_MCP, "b64:***")
        ShadowBinder.setCallingUid(app.applicationInfo.uid + 4_321)
        val stranger = mcp("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")

        assertEquals("invalid_payload", missing.getString(KoraGate.KEY_ERROR_CODE))
        assertEquals("invalid_payload", badBase64.getString(KoraGate.KEY_ERROR_CODE))
        assertEquals("unauthorized", stranger.getString(KoraGate.KEY_ERROR_CODE))
        for (bundle in listOf(missing, badBase64, stranger)) {
            assertFalse(bundle.containsKey(KoraGate.KEY_JSON))
            assertFalse(bundle.containsKey(KoraGate.KEY_JSON_BASE64))
        }
    }

    @Test
    fun `mcp from the main thread is refused`() {
        install()
        val result = app.contentResolver.call(uri, KoraGate.METHOD_MCP, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", null)!!

        assertEquals("provider_unavailable", result.getString(KoraGate.KEY_ERROR_CODE))
        assertFalse(result.containsKey(KoraGate.KEY_JSON))
    }

    @Test
    fun `the provider exposes no data`() {
        install()
        assertNull(app.contentResolver.getType(uri))
        assertFalse(runCatching { app.contentResolver.insert(uri, android.content.ContentValues()) }.isSuccess)
    }
}
