package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `location` as 1.0 shipped it: what a provider app built before 1.1 registers. */
private object LocationV10 : Capability(CapabilityId("location"), CapabilityVersion(1, 0)) {
    val GetLastFix: OperationSpec<NoInput, LocationFix?> = operation(
        "get_last_fix", NoInput.serializer(), LocationFix.serializer().nullable, LocationCapability.ReadScope, "last fix",
    )
    val FixChanged: EventSpec<LocationFix> = event("fix_changed", LocationFix.serializer(), LocationCapability.ReadScope, "fix")
}

/** The built-in tools (generated from `location` at 1.1) against a node whose only provider is at 1.0. */
class McpMemberVersionTest {
    private val access = TestAccess().apply { grants[AGENT] = setOf(LocationCapability.ReadScope) }
    private var lastFixCalls = 0
    private val node = KoraNode("kardio", policy = DefaultCallerPolicy(access)).apply {
        provide(LocationV10) {
            on(LocationV10.GetLastFix) {
                lastFixCalls++
                lastFix
            }
            emits(LocationV10.FixChanged) { emptyFlow() }
        }
    }
    private val session = McpSession(node, AGENT, SERVER_INFO)

    private suspend fun request(method: String, params: String = "{}"): JsonObject {
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        return parse(session.handle(rpc(method, parse(params)))).resultObject
    }

    @Test
    fun `a tool of a 1_0 member is offered and callable when the only provider is at 1_0`() = runTest {
        val listed = request("tools/list")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val result = request("tools/call", """{"name":"location__get_last_fix","arguments":{}}""")

        assertEquals(listOf("location__get_last_fix"), listed)
        assertEquals(JsonPrimitive(false), result["isError"], result.toString())
        val fix = result["structuredContent"]!!.jsonObject["result"]!!
        assertEquals(lastFix, Json.decodeFromJsonElement(LocationFix.serializer(), fix))
        assertEquals(1, lastFixCalls)
    }

    @Test
    fun `a tool of a 1_1 member still asks for 1_1 and is refused by a provider at 1_0`() = runTest {
        val result = request("tools/call", """{"name":"location__configure_stream","arguments":{}}""")

        assertEquals(JsonPrimitive(true), result["isError"], result.toString())
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("incompatible_version", text.substringBefore(':'))
        assertEquals(0, lastFixCalls)
    }
}
