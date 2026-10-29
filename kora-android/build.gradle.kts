plugins {
    id("kora.android.library")
    id("kora.publishing")
}

description = "Kora for Android: Binder peer transport, discovery, caller identification and the external gate."

android {
    namespace = "eus.bihurri.kora.android"

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's SDK 36 runtime reaches into FileDescriptor internals, closed by default since JDK 17.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    buildFeatures {
        // The Binder interface between peers (src/main/aidl) is generated at build time.
        aidl = true
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":kora-core"))
    // The gate method kora.mcp hosts McpSession; consumers of the gate never see MCP types.
    implementation(project(":kora-mcp"))
    testImplementation(libs.kotlinx.coroutines.test)

    // Only the service plumbing needs a real Binder; Robolectric is JUnit 4, so the vintage engine runs it on
    // the JUnit Platform next to the Jupiter tests.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
