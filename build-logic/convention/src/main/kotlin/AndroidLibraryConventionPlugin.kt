import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Common setup for Android library modules (code that needs Android APIs but is not an app).
 *
 * Kotlin support is built into AGP 9, so the Kotlin Android plugin is intentionally not applied.
 * Unit tests run on the host JVM with JUnit on the JUnit Platform, like the pure JVM modules.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<LibraryExtension> {
            compileSdk {
                version = release(catalogVersion("compileSdk").toInt())
            }

            defaultConfig {
                minSdk = catalogVersion("minSdk").toInt()
            }

            testOptions {
                // Android classes are stubs on the host JVM; let them return defaults so that code that only
                // touches them incidentally (Log, Binder constructors) can be unit tested without Robolectric.
                unitTests.isReturnDefaultValues = true
            }

            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
                targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(jdkVersion)
            compilerOptions {
                jvmTarget.set(kotlinJvmTarget)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        dependencies {
            add("testImplementation", catalogLibrary("junit-jupiter"))
            add("testRuntimeOnly", catalogLibrary("junit-platform-launcher"))
        }
    }
}
