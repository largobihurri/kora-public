import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get

/**
 * Publishes a library module as `eus.bihurri.kora:<module name>:<version>` (docs/releasing.md).
 *
 * Applied by the library modules only; the bridge (shipped as a zip) and the samples never publish. Works on
 * top of `kora.jvm.library` (a `java` component with a sources jar) or `kora.android.library` (the `release`
 * variant as an AAR with a sources jar), whichever the module applied first.
 *
 * Repositories:
 * - `GitHubPackages`: the private package registry of the repository. Credentials come from `GITHUB_ACTOR` and
 *   `GITHUB_TOKEN`, or the Gradle properties `gpr.user` and `gpr.key`. They are read lazily, so a build that does
 *   not publish never needs them.
 * - `staging`: `build/staging-repo` of the root project, to inspect what a release would contain.
 */
class PublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")

        // Must run while the Android DSL is still open, before the project is evaluated.
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> {
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                    }
                }
            }
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            extensions.configure<JavaPluginExtension> {
                withSourcesJar()
            }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/largobihurri/kora")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR")
                            .orElse(providers.gradleProperty("gpr.user")).orNull
                        password = providers.environmentVariable("GITHUB_TOKEN")
                            .orElse(providers.gradleProperty("gpr.key")).orNull
                    }
                }
                maven {
                    name = "staging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        artifactId = project.name
                        from(publishedComponent())
                        pom {
                            name.set(project.name)
                            description.set(project.description ?: "Kora library module ${project.name}.")
                            url.set("https://github.com/largobihurri/kora-public")
                            licenses {
                                license {
                                    name.set("The Apache License, Version 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                    distribution.set("repo")
                                }
                            }
                            scm {
                                url.set("https://github.com/largobihurri/kora-public")
                                connection.set("scm:git:https://github.com/largobihurri/kora-public.git")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Project.publishedComponent() = when {
        pluginManager.hasPlugin("com.android.library") -> components["release"]
        else -> components["java"].also { skipTestFixtures(it as AdhocComponentWithVariants) }
    }

    /** `java-test-fixtures` adds its variants to the `java` component; they are for this build's tests only. */
    private fun Project.skipTestFixtures(component: AdhocComponentWithVariants) {
        for (name in listOf("testFixturesApiElements", "testFixturesRuntimeElements")) {
            configurations.findByName(name)?.let { component.withVariantsFromConfiguration(it) { skip() } }
        }
    }
}
