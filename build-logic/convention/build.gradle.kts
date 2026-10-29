plugins {
    `kotlin-dsl`
}

group = "eus.bihurri.kora.buildlogic"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.serialization.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kora.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "kora.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("publishing") {
            id = "kora.publishing"
            implementationClass = "PublishingConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kora.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
