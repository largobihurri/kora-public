import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Common setup for the sample apps under `samples/`. They are never published; they exist to exercise the
 * library on a device. They are signed with the default debug key, so two samples built on one machine share
 * a signing certificate and recognise each other as peers.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk {
                version = release(catalogVersion("compileSdk").toInt())
            }

            defaultConfig {
                minSdk = catalogVersion("minSdk").toInt()
                targetSdk = catalogVersion("compileSdk").toInt()
                versionCode = 1
                versionName = "0.1.0"
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
    }
}
