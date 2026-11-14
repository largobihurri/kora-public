plugins {
    id("kora.android.application")
}

android {
    namespace = "eus.bihurri.kora.sample.wearcheck"

    defaultConfig {
        applicationId = "eus.bihurri.kora.sample.wearcheck"
    }
}

dependencies {
    // Brings kora-android and kora-wear-protocol (api) along. The same APK goes on the phone and on the watch.
    implementation(project(":kora-wear"))

    // Plain JVM tests of the sample's pure logic; no Android runtime.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
