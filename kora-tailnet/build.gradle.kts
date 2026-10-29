plugins {
    id("kora.android.library")
    id("kora.publishing")
}

description = "Kora tailnet listener for Android: serves a node over MCP HTTP on the Tailscale address only, behind a foreground service with auto-off."

android {
    namespace = "eus.bihurri.kora.tailnet"

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // The listener is an McpHttpServer; apps pass a KoraNode and receive Pairing and DeniedAttempt, so they need its
    // API (and through it kora-mcp, kora-core and coroutines). Nothing from kora-android is needed: the node is
    // handed in, so an app can use this module whatever transports it installed.
    api(project(":kora-mcp-http"))
    testImplementation(libs.kotlinx.coroutines.test)

    // The foreground service needs a real Service and the address source a ConnectivityManager. Robolectric is
    // JUnit 4, so the vintage engine runs it on the JUnit Platform next to the Jupiter tests.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
