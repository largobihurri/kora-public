package eus.bihurri.kora.contract.fixtures

/**
 * Checks the wire fixtures of a module against its [cases] (the sample values of the **current** code).
 *
 * - [verifyStoredFixturesDecode]: every stored fixture of every version still decodes, in both formats, to the same
 *   value. Old fixtures must keep decoding forever, so this is what protects peers built with older Kora versions.
 * - [verifyCurrentEncodingMatches]: the current code encodes each case exactly as the fixture of its version says,
 *   which detects a wire change nobody meant. Missing fixtures fail, or are recorded when the store is recording.
 */
public class WireFixtureVerifier(
    private val cases: List<FixtureCase<*>>,
    private val store: FixtureStore = FixtureStore.forTests(),
) {
    init {
        val duplicated = cases.groupBy { it.group to it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Samples defined twice: $duplicated" }
    }

    public fun verifyStoredFixturesDecode() {
        val byKey = cases.associateBy { it.group to it.name }
        val problems = mutableListOf<String>()
        val stored = store.stored()
        if (stored.isEmpty() && !store.recording) {
            problems += "no stored fixtures were found, so nothing was checked (is the 'wire' test resource on disk?)"
        }
        val decoded = mutableMapOf<Triple<String, String, String>, MutableMap<FixtureFormat, String>>()
        for (fixture in stored) {
            val case = byKey[fixture.group to fixture.name]
            if (case == null) {
                problems += "$fixture has no sample in the code: members of a released version can never be removed"
                continue
            }
            try {
                decoded.getOrPut(Triple(fixture.group, fixture.version, fixture.name)) { mutableMapOf() }[fixture.format] =
                    case.decodeToJson(fixture.format, fixture.text.trim())
            } catch (e: Exception) {
                problems += "$fixture no longer decodes: ${e.message?.lineSequence()?.firstOrNull()}"
            }
        }
        for ((key, formats) in stored.groupBy({ Triple(it.group, it.version, it.name) }, { it.format })) {
            val missing = FixtureFormat.entries - formats.toSet()
            if (missing.isNotEmpty()) {
                problems += "${key.first}/${key.second}/${key.third} lacks its ${missing.joinToString { it.extension }} file"
            }
        }
        for ((key, byFormat) in decoded) {
            if (byFormat.values.distinct().size > 1) {
                problems += "${key.first}/${key.second}/${key.third} decodes to different values from its two files: $byFormat"
            }
        }
        fail(problems)
    }

    public fun verifyCurrentEncodingMatches() {
        val problems = mutableListOf<String>()
        val recorded = mutableListOf<String>()
        for (case in cases) {
            for (format in FixtureFormat.entries) {
                val expected = case.encode(format)
                val stored = store.read(case.group, case.version, case.name, format)?.trim()
                when {
                    stored == null && store.recording -> {
                        store.record(case.group, case.version, case.name, format, expected)
                        recorded += "$case.${format.extension}"
                    }

                    stored == null -> problems +=
                        "$case.${format.extension} is missing; record it with -Pkora.recordFixtures=true"

                    stored != expected -> problems +=
                        "$case.${format.extension} changed on the wire:\n     stored  $stored\n     current $expected\n" +
                            "     A released version must not change. If this version is unreleased, delete the fixture and re-record."
                }
            }
        }
        if (recorded.isNotEmpty()) {
            println("Recorded ${recorded.size} wire fixture(s); review and commit them:\n  " + recorded.joinToString("\n  "))
        }
        fail(problems)
    }

    private fun fail(problems: List<String>) {
        if (problems.isNotEmpty()) {
            throw AssertionError("${problems.size} wire fixture problem(s):\n - " + problems.joinToString("\n - "))
        }
    }
}
