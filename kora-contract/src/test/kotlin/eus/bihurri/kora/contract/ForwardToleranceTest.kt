package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fixtures.FuturePayloads
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Payloads from a future minor version, simulated by editing the encodings of every sample: the current code must read
 * them exactly like the originals. The hand-written cases (`CompatibilityTest`, `WireEnumTest`) show the mechanism with
 * small shadow classes; these run it over every real type of the contract, in both codecs.
 */
class ForwardToleranceTest {
    @Test
    fun `unknown fields in any object of any payload or envelope are ignored by both codecs`() {
        FuturePayloads.verifyUnknownFieldsAreIgnored(WireSamples.cases)
    }

    @Test
    fun `unknown enum constants read as unknown in every payload and envelope`() {
        val affected = FuturePayloads.verifyUnknownEnumConstantsReadAsUnknown(WireSamples.cases)

        assertTrue(affected > 0, "no sample has an enum-valued field named status, phase or code")
    }

    @Test
    fun `the editing machinery keeps untouched payloads intact`() {
        assertEquals(0, FuturePayloads.verifyUnknownEnumConstantsReadAsUnknown(WireSamples.cases, fields = emptySet()))
    }
}
