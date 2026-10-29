plugins {
    id("kora.jvm.library")
    id("kora.publishing")
}

description = "Kora node: provider registry, router, caller policy, audit and event subscriptions."

kotlin {
    explicitApi()
}

dependencies {
    api(project(":kora-contract"))
    // Flow is part of the public API (event subscriptions), so consumers need coroutines too.
    api(libs.kotlinx.coroutines.core)
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
