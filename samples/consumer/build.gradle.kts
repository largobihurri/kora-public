plugins {
    id("kora.android.application")
}

android {
    namespace = "eus.bihurri.kora.sample.consumer"

    defaultConfig {
        applicationId = "eus.bihurri.kora.sample.consumer"
    }
}

dependencies {
    implementation(project(":kora-android"))

    // Plain JVM tests of the sample's pure logic; no Android runtime.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
