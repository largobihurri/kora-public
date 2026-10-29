package eus.bihurri.kora.mcp.http

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class PairingTokensTest {
    @Test
    fun `a token is 32 random bytes in URL-safe Base64 without padding`() {
        val issued = PairingTokens.generate()

        assertEquals(43, issued.token.length)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(issued.token))
        assertEquals(PairingTokens.TOKEN_BYTES, Base64.getUrlDecoder().decode(issued.token).size)
    }

    @Test
    fun `generated tokens and ids do not repeat`() {
        val tokens = List(2_000) { PairingTokens.generate().token }.toSet()
        val ids = List(2_000) { PairingTokens.generateId() }.toSet()

        assertEquals(2_000, tokens.size)
        assertEquals(2_000, ids.size)
        assertTrue(ids.all { Pairing(it, "x").id == it })
    }

    @Test
    fun `the hash is the SHA-256 of the token and is stable`() {
        val issued = PairingTokens.generate()
        val expected = MessageDigest.getInstance("SHA-256").digest(issued.token.toByteArray(Charsets.US_ASCII))

        assertArrayEquals(expected, issued.hash)
        assertArrayEquals(issued.hash, PairingTokens.hash(issued.token))
        assertEquals(PairingTokens.HASH_BYTES, issued.hash.size)
        assertFalse(PairingTokens.sameHash(issued.hash, PairingTokens.hash(issued.token + "x")))
    }

    @Test
    fun `the generator draws from the random source it is given`() {
        val seeded = { SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7) } }

        assertEquals(PairingTokens.generate(seeded()).token, PairingTokens.generate(seeded()).token)
    }

    @Test
    fun `an issued token does not print its secret`() {
        val issued = PairingTokens.generate()

        assertFalse("$issued".contains(issued.token))
    }

    @Test
    fun `sameHash compares contents, not references or lengths`() {
        val a = PairingTokens.hash("a")

        assertTrue(PairingTokens.sameHash(a, a.copyOf()))
        assertFalse(PairingTokens.sameHash(a, PairingTokens.hash("b")))
        assertFalse(PairingTokens.sameHash(a, a.copyOf(16)))
    }

    @Test
    fun `a pairing id must be safe to log and to embed in a caller id`() {
        listOf("", "a b", "a/b", "tailnet:x", "x".repeat(65), "é").forEach { bad ->
            assertThrows<IllegalArgumentException>("'$bad'") { Pairing(bad, "name") }
        }
        Pairing("Ab9_-", "name")
    }

    @Test
    fun `the in-memory store finds, adds, lists and revokes`() {
        val store = InMemoryPairingStore()
        val one = PairingTokens.generate()
        val two = PairingTokens.generate()
        store.add(Pairing("one", "First"), one.hash)
        store.add(Pairing("two", "Second"), two.hash)

        assertEquals("two", store.find(two.hash)?.id)
        assertEquals("one", store.find(one.hash)?.id)
        assertNull(store.find(PairingTokens.hash("unknown")))
        assertEquals(listOf("one", "two"), store.pairings().map { it.id })

        assertTrue(store.revoke("one"))
        assertFalse(store.revoke("one"))
        assertNull(store.find(one.hash))
        assertNotNull(store.find(two.hash))
    }

    @Test
    fun `the in-memory store refuses a duplicate id and a malformed hash`() {
        val store = InMemoryPairingStore()
        store.add(Pairing("one", "First"), PairingTokens.generate().hash)

        assertThrows<IllegalArgumentException> { store.add(Pairing("one", "Again"), PairingTokens.generate().hash) }
        assertThrows<IllegalArgumentException> { store.add(Pairing("two", "Short"), ByteArray(8)) }
    }

    @Test
    fun `a revoked id is never accepted again, so a new pairing cannot inherit its identity`() {
        val store = InMemoryPairingStore()
        store.add(Pairing("one", "First"), PairingTokens.generate().hash)
        store.revoke("one")

        val error = assertThrows<IllegalArgumentException> { store.add(Pairing("one", "First"), PairingTokens.generate().hash) }

        assertTrue(error.message!!.contains("revoked"))
        store.add(Pairing("two", "First"), PairingTokens.generate().hash)
    }

    @Test
    fun `the store keeps its own copy of the hash it is given`() {
        val store = InMemoryPairingStore()
        val issued = PairingTokens.generate()
        val hash = issued.hash.copyOf()
        store.add(Pairing("one", "First"), hash)
        hash.fill(0)

        assertEquals("one", store.find(issued.hash)?.id)
    }
}
