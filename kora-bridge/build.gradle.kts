plugins {
    // A JVM module with the library conventions (toolchain, bytecode level, JUnit Platform); the application plugin
    // adds the installable launcher script, `./gradlew :kora-bridge:installDist`.
    id("kora.jvm.library")
    application
}

application {
    mainClass = "eus.bihurri.kora.bridge.MainKt"
    applicationName = "kora-bridge"
}

dependencies {
    // The bridge only needs to read the id of a JSON-RPC message and write error responses; it shares no code with
    // the Kora modules, so the distribution stays small.
    implementation(libs.kotlinx.serialization.json)
}
