package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityRegistry
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.JsonGate
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private object ToolV1 : Capability(CapabilityId("test.major"), CapabilityVersion(1, 0)) {
    val Read = Scope("test.major.read")

    val GetName: OperationSpec<NoInput, String> =
        operation("get_name", NoInput.serializer(), String.serializer(), Read, "Name as a string.")
}

@Serializable
private data class ToolName(val first: String, val last: String)

private object ToolV2 : Capability(CapabilityId("test.major"), CapabilityVersion(2, 0)) {
    val Read = Scope("test.major.read2")

    val GetName: OperationSpec<NoInput, ToolName> =
        operation("get_name", NoInput.serializer(), ToolName.serializer(), Read, "Name as first and last.")
}

/** Two majors of one capability: tools for both, the first major keeps its names. */
class McpMajorsTest {
    private val registry = CapabilityRegistry(listOf(ToolV1, ToolV2))
    private val tools = McpToolSet(registry)
    private val access = TestAccess().apply { grants[AGENT] = setOf(ToolV1.Read, ToolV2.Read) }

    private fun node(vararg majors: Int) = KoraNode("bridge", policy = DefaultCallerPolicy(access), registry = registry).apply {
        if (1 in majors) provide(ToolV1) { on(ToolV1.GetName) { "ada lovelace" } }
        if (2 in majors) provide(ToolV2) { on(ToolV2.GetName) { ToolName("ada", "lovelace") } }
    }

    private fun session(node: KoraNode) = McpSession(node, AGENT, SERVER_INFO, gate = JsonGate(node, registry = registry), tools = tools)

    private suspend fun McpSession.initialized(): McpSession {
        handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        handle(rpc("notifications/initialized", id = null))
        return this
    }

    private fun names(answer: JsonObject) = answer.resultObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun `the first major keeps the plain name and the second gets a version suffix`() {
        assertEquals(listOf("test_major__get_name", "test_major_v2__get_name"), tools.all.map { it.name })
        assertEquals("test_major__get_name", McpTools.nameOf(ToolV1.GetName))
        assertEquals("test_major_v2__get_name", McpTools.nameOf(ToolV2.GetName))
    }

    @Test
    fun `names do not depend on the order of the registry or on which majors exist`() {
        val reversed = McpToolSet(CapabilityRegistry(listOf(ToolV2, ToolV1)))
        val onlyV2 = McpToolSet(CapabilityRegistry(listOf(ToolV2)))

        assertEquals(tools.all.map { it.name }.toSet(), reversed.all.map { it.name }.toSet())
        assertEquals(listOf("test_major_v2__get_name"), onlyV2.all.map { it.name })
        assertEquals(ToolV1, tools.find("test_major__get_name")?.capability)
        assertEquals(ToolV2, tools.find("test_major_v2__get_name")?.capability)
    }

    @Test
    fun `the built-in tools keep their names`() {
        assertFalse(McpTools.all.any { it.name.contains("_v2") })
        assertEquals("media_library__list_playlists", McpTools.all.first().name)
        assertEquals(eus.bihurri.kora.contract.Capabilities.all.sumOf { it.operations.size }, McpTools.all.size)
    }

    @Test
    fun `tools list offers both majors when the node reaches both`() = runTest {
        val answer = parse(session(node(1, 2)).initialized().handle(rpc("tools/list")))

        assertEquals(listOf("test_major__get_name", "test_major_v2__get_name"), names(answer))
    }

    @Test
    fun `tools list offers only the majors the node reaches`() = runTest {
        assertEquals(listOf("test_major__get_name"), names(parse(session(node(1)).initialized().handle(rpc("tools/list")))))
        assertEquals(listOf("test_major_v2__get_name"), names(parse(session(node(2)).initialized().handle(rpc("tools/list")))))
    }

    @Test
    fun `each tool calls the major it belongs to`() = runTest {
        val session = session(node(1, 2)).initialized()
        fun params(name: String) = parse("""{"name":"$name","arguments":{}}""")

        val v1 = parse(session.handle(rpc("tools/call", params("test_major__get_name")))).resultObject
        val v2 = parse(session.handle(rpc("tools/call", params("test_major_v2__get_name")))).resultObject

        assertEquals(JsonPrimitive("ada lovelace"), v1["structuredContent"]!!.jsonObject["result"])
        assertEquals(JsonPrimitive("ada"), v2["structuredContent"]!!.jsonObject["first"])
        assertTrue(v1["isError"] == JsonPrimitive(false) && v2["isError"] == JsonPrimitive(false))
    }

    @Test
    fun `a grant for the newer major does not open the tool of the older one`() = runTest {
        access.grants[AGENT] = setOf(ToolV2.Read)
        val session = session(node(1, 2)).initialized()

        val denied = parse(session.handle(rpc("tools/call", parse("""{"name":"test_major__get_name","arguments":{}}""")))).resultObject

        assertEquals(JsonPrimitive(true), denied["isError"])
    }
}
