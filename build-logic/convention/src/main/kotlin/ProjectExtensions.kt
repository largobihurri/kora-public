import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** The shared version catalog (`gradle/libs.versions.toml`). */
internal val Project.catalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.catalogVersion(alias: String): String =
    catalog.findVersion(alias).get().requiredVersion

internal fun Project.catalogLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
    catalog.findLibrary(alias).get()

/** JDK that runs the build; used for toolchains. */
internal val Project.jdkVersion: Int
    get() = catalogVersion("jdk").toInt()

/** Bytecode level (compatibility floor) every module is compiled to, whatever the toolchain. */
internal val Project.jvmTargetVersion: Int
    get() = catalogVersion("jvmTarget").toInt()

internal val Project.kotlinJvmTarget: JvmTarget
    get() = JvmTarget.fromTarget(jvmTargetVersion.toString())
