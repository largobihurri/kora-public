// Plugins are applied by the convention plugins in build-logic; they are declared here so that
// all modules load them from a single, shared classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "eus.bihurri.kora"
version = "1.2.1"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

// Prints the library version for scripts (the release workflow compares it with the tag): ./gradlew -q printVersion
tasks.register("printVersion") {
    val libraryVersion = version.toString()
    doLast { println(libraryVersion) }
}
