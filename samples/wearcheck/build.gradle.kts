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
}
