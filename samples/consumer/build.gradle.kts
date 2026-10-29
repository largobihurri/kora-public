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
}
