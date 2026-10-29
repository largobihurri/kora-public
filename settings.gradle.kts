pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-provision the JDK requested by the toolchain when it is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kora"

include(":kora-contract")
include(":kora-core")
include(":kora-mcp")
// MCP Streamable HTTP endpoint with pairing tokens (pure JVM; the Android listener that binds it is separate).
include(":kora-mcp-http")
// Android listener that serves a node over MCP HTTP on the Tailscale address only (foreground service, auto-off, pairings).
include(":kora-tailnet")
include(":kora-android")
// Phone <-> watch relay over the Wear OS Data Layer: pure protocol (JVM) and the Android glue.
include(":kora-wear-protocol")
include(":kora-wear")
// PC-side command line tool: stdio MCP server that talks to a phone app through adb.
include(":kora-bridge")

// Sample apps that exercise the Binder transport on a device; never published.
include(":samples:provider")
include(":samples:consumer")
// One APK installed unchanged on a phone and a watch to check the kora-wear relay on real devices.
include(":samples:wearcheck")
