import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM library (no Android dependencies) tested with JUnit on the JUnit Platform.
 *
 * The build runs on the catalog's `jdk` toolchain but emits bytecode for `jvmTarget`, the
 * compatibility floor of every consumer.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(jdkVersion)
            compilerOptions {
                jvmTarget.set(kotlinJvmTarget)
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(jvmTargetVersion)
        }

        // `./gradlew test -Pkora.recordFixtures=true` lets the wire-fixture tests add missing fixtures (docs/compatibility.md).
        val recordFixtures = providers.gradleProperty("kora.recordFixtures")
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            if (recordFixtures.isPresent) systemProperty("kora.recordFixtures", recordFixtures.get())
        }

        dependencies {
            add("testImplementation", catalogLibrary("junit-jupiter"))
            add("testRuntimeOnly", catalogLibrary("junit-platform-launcher"))
        }
    }
}
