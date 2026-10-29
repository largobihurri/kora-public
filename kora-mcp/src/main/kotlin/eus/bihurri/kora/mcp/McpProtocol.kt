package eus.bihurri.kora.mcp

/**
 * Revisions of the Model Context Protocol this module speaks. Specification:
 * https://modelcontextprotocol.io/specification (revision pages live under `/specification/<revision>/`).
 *
 * Two eras exist, and a [McpSession] serves both at once, the way the specification allows a dual-era server:
 * - **Modern** ([MODERN_VERSIONS]): stateless. Every request carries its protocol version and the client
 *   capabilities in `_meta`; there is no `initialize`.
 * - **Legacy** ([LEGACY_VERSIONS]): a session opened by an `initialize` handshake.
 */
public object McpProtocol {
    /** The newest revision: `2026-07-28`, the latest stable one when this module was written. */
    public const val LATEST_VERSION: String = "2026-07-28"

    /** Revisions served statelessly through per-request `_meta`. */
    public val MODERN_VERSIONS: List<String> = listOf(LATEST_VERSION)

    /** Revisions served through the `initialize` handshake, newest first. */
    public val LEGACY_VERSIONS: List<String> = listOf("2025-11-25", "2025-06-18")

    /** Every revision served, newest first. */
    public val SUPPORTED_VERSIONS: List<String> = MODERN_VERSIONS + LEGACY_VERSIONS
}

/**
 * Identity the server reports to clients. [name] and [version] are required by every revision; [title] is the
 * display name; [instructions] is optional guidance for the model on how to use the tools of this server.
 */
public class McpServerInfo(
    public val name: String,
    public val version: String,
    public val title: String? = null,
    public val instructions: String? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
    }
}
