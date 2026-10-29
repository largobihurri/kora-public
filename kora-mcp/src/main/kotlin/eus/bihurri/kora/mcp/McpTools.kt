package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.Capabilities
import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityRegistry
import eus.bihurri.kora.contract.OperationSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One MCP tool: one operation of a built-in capability.
 *
 * @property name `<capability with dots as underscores>__<operation>`, e.g. `media_library__list_playlists`. A
 *   capability at major 2 or later has `_v<major>` after the capability, e.g. `media_library_v2__list_playlists`
 *   (see [McpTools.nameOf]). It matches `^[a-zA-Z0-9_-]{1,64}$`, the strictest tool-name rule of the MCP revisions this module serves.
 * @property description the description of the operation plus the scope a caller needs.
 * @property inputSchema schema of the `arguments` object.
 * @property outputSchema schema of `structuredContent`. See [wrapsOutput].
 * @property wrapsOutput true when the operation's result is not a JSON object (`null`, a primitive or a list), so
 *   `structuredContent` is `{"result": <value>}` and [outputSchema] describes that wrapper.
 * @property content how a successful result's `content` blocks are made ([ToolContent.forOperation]).
 */
public class McpTool internal constructor(
    public val name: String,
    public val title: String,
    public val description: String,
    public val capability: Capability,
    public val operation: OperationSpec<*, *>,
    public val inputSchema: JsonObject,
    public val outputSchema: JsonObject,
    public val wrapsOutput: Boolean,
    internal val content: ToolContent = ToolContent.forOperation(operation),
) {
    /** The tool as it appears in a `tools/list` result. */
    public fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("title", title)
        put("description", description)
        put("inputSchema", inputSchema)
        put("outputSchema", outputSchema)
    }

    override fun toString(): String = "McpTool($name)"
}

/**
 * The MCP tools generated from a [CapabilityRegistry]: every operation of every capability version in it becomes
 * one tool, in the order of the registry. Events are not exposed: MCP tools are request/response, and subscriptions
 * are out of scope for this module.
 *
 * Tools are major-aware. Each tool belongs to one [Capability] (so to one version), and calling it sends that
 * capability's version. Names follow [McpTools.nameOf]: the plain `<capability>__<operation>` for major 0 or 1, and
 * `<capability>_v<major>__<operation>` from major 2, so a second major never changes the names of the first.
 */
public class McpToolSet(
    /** The capability definitions the tools come from. */
    public val registry: CapabilityRegistry,
) {
    public val all: List<McpTool> = registry.all.flatMap { capability ->
        capability.operations.map { buildTool(capability, it) }
    }

    private val byName: Map<String, McpTool> = all.associateBy { it.name }

    init {
        check(byName.size == all.size) { "Two operations map to the same tool name" }
    }

    /** The tool called [name], or null when there is none. */
    public fun find(name: String): McpTool? = byName[name]
}

/**
 * The MCP tools of the built-in capabilities ([Capabilities.registry]); see [McpToolSet] for any other registry.
 *
 * With a single major per id, as today, every name is `<capability with dots as underscores>__<operation>`.
 */
public object McpTools {
    /** The tools of [Capabilities.registry]; what [McpSession] serves by default. */
    public val builtIn: McpToolSet = McpToolSet(Capabilities.registry)

    public val all: List<McpTool> get() = builtIn.all

    /** The tool called [name] among [all], or null when there is none. */
    public fun find(name: String): McpTool? = builtIn.find(name)

    /**
     * The tool name of [spec]: the capability id with dots as underscores, `__` and the operation name. A spec of
     * major 2 or later has `_v<major>` right after the capability (`thing_v2__get_name`), so the names of major 1
     * stay the ones clients already know. The suffix depends only on the spec, never on which other majors exist,
     * so a name does not change when another major is added or dropped.
     */
    public fun nameOf(spec: OperationSpec<*, *>): String = toolName(spec)
}

private const val NAME_SEPARATOR = "__"
private val VALID_NAME = Regex("[a-zA-Z0-9_-]{1,64}")

private fun toolName(spec: OperationSpec<*, *>): String {
    val major = spec.capabilityVersion.major
    val suffix = if (major >= 2) "_v$major" else ""
    return spec.capabilityId.value.replace('.', '_') + suffix + NAME_SEPARATOR + spec.name
}

private fun buildTool(capability: Capability, spec: OperationSpec<*, *>): McpTool {
    val name = toolName(spec)
    check(VALID_NAME.matches(name)) { "Tool name '$name' is not valid for MCP" }
    val input = JsonSchemas.input(spec.inputSerializer.descriptor)
    check(input["type"] == JsonPrimitive("object")) { "Input of '$name' must be a JSON object" }
    val output = JsonSchemas.output(spec.outputSerializer.descriptor)
    val wraps = output["type"] != JsonPrimitive("object")
    return McpTool(
        name = name,
        title = spec.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
        description = "${spec.description} Requires scope ${spec.scope}.",
        capability = capability,
        operation = spec,
        inputSchema = input,
        outputSchema = if (wraps) wrapped(output) else output,
        wrapsOutput = wraps,
    )
}

private fun wrapped(schema: JsonObject): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { put("result", schema) })
    put("required", JsonArray(listOf(JsonPrimitive("result"))))
}
