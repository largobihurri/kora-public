plugins {
    id("kora.android.library")
    id("kora.publishing")
}

description = "Kora for Wear OS: Data Layer link, counterpart discovery and listener service."

android {
    namespace = "eus.bihurri.kora.wear"

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
    api(project(":kora-android"))
    api(project(":kora-wear-protocol"))
    // The Data Layer clients (MessageClient, CapabilityClient) and WearableListenerService. Not part of the API.
    implementation(libs.play.services.wearable)
    testImplementation(libs.kotlinx.coroutines.test)

    // Only the manifest and resource checks need Android; Robolectric is JUnit 4, hence the vintage engine.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
