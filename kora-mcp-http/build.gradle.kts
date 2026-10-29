plugins {
    id("kora.jvm.library")
    id("kora.publishing")
}

description = "Kora MCP over HTTP: Streamable HTTP endpoint with pairing-token authentication in front of McpSession."

kotlin {
    explicitApi()
}

dependencies {
    // The server takes a KoraNode and McpServerInfo, so consumers need the MCP module (and through it core).
    api(project(":kora-mcp"))
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
