package eus.bihurri.kora.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CertificateDigestTest {
    @Test
    fun `sha256 is lowercase hex`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CertificateDigest.sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `a digest has the shape a Caller accepts`() {
        assertTrue(Regex("[0-9a-f]{64}").matches(CertificateDigest.sha256Hex(ByteArray(300) { it.toByte() })))
    }

    @Test
    fun `a single signer keeps its own fingerprint`() {
        val one = CertificateDigest.sha256Hex(byteArrayOf(1))
        assertEquals(one, CertificateDigest.combine(listOf(one)))
    }

    @Test
    fun `several signers combine independently of their order`() {
        val a = CertificateDigest.sha256Hex(byteArrayOf(1))
        val b = CertificateDigest.sha256Hex(byteArrayOf(2))
        val combined = CertificateDigest.combine(listOf(a, b))

        assertEquals(combined, CertificateDigest.combine(listOf(b, a)))
        assertTrue(Regex("[0-9a-f]{64}").matches(combined!!))
        assertNotEquals(a, combined)
        assertNotEquals(combined, CertificateDigest.combine(listOf(a)))
    }

    @Test
    fun `no signers has no digest`() {
        assertNull(CertificateDigest.combine(emptyList()))
    }
}
