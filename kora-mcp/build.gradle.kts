plugins {
    id("kora.jvm.library")
    id("kora.publishing")
}

description = "Kora MCP server: exposes any Kora node as MCP tools generated from the capability contract."

kotlin {
    explicitApi()
}

dependencies {
    // The session takes a KoraNode and a Caller, and exposes Flow, so consumers need core (and through it the
    // contract, serialization and coroutines).
    api(project(":kora-core"))
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
