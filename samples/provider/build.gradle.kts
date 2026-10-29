plugins {
    id("kora.android.application")
}

android {
    namespace = "eus.bihurri.kora.sample.provider"

    defaultConfig {
        applicationId = "eus.bihurri.kora.sample.provider"
    }
}

dependencies {
    implementation(project(":kora-android"))
    // Agent access over Tailscale (the "Tailnet test" in README.md).
    implementation(project(":kora-tailnet"))

    // Plain JVM tests of the sample's pure logic (artwork ids and sizing); no Android runtime.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
