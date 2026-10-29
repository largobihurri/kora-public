package eus.bihurri.kora.contract.fixtures

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** A fixture file found on disk. */
public class StoredFixture(
    public val group: String,
    public val version: String,
    public val name: String,
    public val format: FixtureFormat,
    public val text: String,
) {
    override fun toString(): String = "$group/$version/$name.${format.extension}"
}

/**
 * The wire fixtures of one module, read from the test resources and, when [recording], written to the source tree.
 *
 * Recording only ever **adds** files: a fixture that exists is the contract of a released version and is never
 * overwritten (see `docs/compatibility.md`).
 */
public class FixtureStore(
    private val readRoot: Path?,
    private val writeRoot: Path?,
    public val recording: Boolean,
) {
    /** Every fixture currently stored, in a stable order. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    public fun stored(): List<StoredFixture> {
        val root = readRoot?.takeIf { it.exists() } ?: return emptyList()
        return root.walk().filter { it.isRegularFile() }.mapNotNull { file ->
            val parts = file.relativeTo(root).map { it.name }
            val (name, format) = FixtureFormat.split(parts.last()) ?: return@mapNotNull null
            if (parts.size != 3) return@mapNotNull null
            StoredFixture(parts[0], parts[1], name, format, file.readText())
        }.sortedBy { it.toString() }.toList()
    }

    /** The stored text of one fixture, or null when it does not exist. */
    public fun read(group: String, version: String, name: String, format: FixtureFormat): String? =
        readRoot?.resolve(group)?.resolve(version)?.resolve("$name.${format.extension}")?.takeIf { it.isRegularFile() }?.readText()

    /** Adds a fixture to the source tree. Refuses to replace an existing one. */
    public fun record(group: String, version: String, name: String, format: FixtureFormat, text: String) {
        check(recording) { "Fixtures are only recorded with -Pkora.recordFixtures=true" }
        val file = checkNotNull(writeRoot) { "No source directory to record fixtures into" }
            .resolve(group).resolve(version).resolve("$name.${format.extension}")
        check(!file.exists()) { "Refusing to overwrite the stored fixture $file" }
        Files.createDirectories(file.parent)
        Files.writeString(file, text + "\n")
    }

    public companion object {
        /**
         * The store of the module under test: reads `wire/` from the test classpath, writes to
         * `src/test/resources/wire` relative to the working directory (the module directory under Gradle), and
         * records when the build runs with `-Pkora.recordFixtures=true`.
         */
        public fun forTests(): FixtureStore {
            val resource = FixtureStore::class.java.classLoader.getResource("wire")?.takeIf { it.protocol == "file" }
            return FixtureStore(
                readRoot = resource?.let { Path.of(it.toURI()) },
                writeRoot = Path.of("src", "test", "resources", "wire"),
                recording = System.getProperty("kora.recordFixtures") == "true",
            )
        }
    }
}
