package eus.bihurri.kora.mcp.http

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One paired agent: the owner created it on purpose ("Pair agent") and handed its token to one MCP client.
 *
 * [id] is opaque and safe to log and to embed in a caller id (`tailnet:<id>`): 1 to 64 characters of `A-Z a-z 0-9 _ -`,
 * see [PairingTokens.generateId]. [name] is what the owner called the agent, for display only; it never takes part in
 * identity or authorization. The token is not part of a pairing: only its hash is stored, in a [PairingStore].
 */
public data class Pairing(
    public val id: String,
    public val name: String,
    public val createdAtEpochMs: Long = 0,
) {
    init {
        require(ID_PATTERN.matches(id)) { "A pairing id is 1 to 64 characters of A-Z a-z 0-9 _ -: $id" }
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * Where the server looks a presented token up. The app owns persistence (Keystore-backed storage, a database...);
 * this module only asks "whose token has this hash?".
 *
 * Implementations store the SHA-256 hash of the token ([PairingTokens.hash]), never the token. [find] is called on
 * every request, from several threads, so it must be fast and thread-safe, and it must not stop at the first
 * non-matching entry when it compares many hashes (use [PairingTokens.sameHash], which is constant-time).
 * Returning null for a revoked pairing is what makes revocation take effect on the next request.
 */
public interface PairingStore {
    /** The pairing whose token hashes to [tokenHash], or null when there is none. */
    public fun find(tokenHash: ByteArray): Pairing?
}

/**
 * A [PairingStore] kept in memory: for tests and for simple uses that do not need to survive a restart. Thread-safe.
 */
public class InMemoryPairingStore : PairingStore {
    private class Entry(val pairing: Pairing, val tokenHash: ByteArray)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val revoked = ConcurrentHashMap.newKeySet<String>()

    /**
     * Registers [pairing] as the holder of the token with hash [tokenHash] (see [PairingTokens.generate]).
     * An id that was revoked is never accepted again: the id is part of the caller identity (`tailnet:<id>`), so a
     * new pairing must not inherit the grants, audit trail or session of a revoked one. Generate a new id.
     */
    public fun add(pairing: Pairing, tokenHash: ByteArray) {
        require(tokenHash.size == PairingTokens.HASH_BYTES) { "A token hash is ${PairingTokens.HASH_BYTES} bytes" }
        require(pairing.id !in revoked) { "Pairing id ${pairing.id} was revoked and cannot be reused" }
        require(entries.none { it.pairing.id == pairing.id }) { "Pairing ${pairing.id} already exists" }
        entries += Entry(pairing, tokenHash.copyOf())
    }

    /** Removes the pairing; its token stops working on the next request. Returns whether it existed. */
    public fun revoke(pairingId: String): Boolean {
        val removed = entries.removeIf { it.pairing.id == pairingId }
        if (removed) revoked += pairingId
        return removed
    }

    /** The pairings that exist now, oldest first. */
    public fun pairings(): List<Pairing> = entries.map { it.pairing }

    override fun find(tokenHash: ByteArray): Pairing? {
        var found: Pairing? = null
        // Visit every entry: how long the lookup takes must not depend on which entry matched.
        for (entry in entries) {
            if (PairingTokens.sameHash(entry.tokenHash, tokenHash)) found = entry.pairing
        }
        return found
    }
}

/** A freshly generated token: [token] is what the agent gets, once; [hash] is what the app stores. */
public class IssuedToken internal constructor(
    /** The secret to give to the agent. Never stored and never logged: show it once. */
    public val token: String,
    /** SHA-256 of [token]: what a [PairingStore] keeps. */
    public val hash: ByteArray,
) {
    // A token must not leak through string templates in logs.
    override fun toString(): String = "IssuedToken(redacted)"
}

/** Generation and hashing of pairing tokens. */
public object PairingTokens {
    /** Entropy of a generated token. */
    public const val TOKEN_BYTES: Int = 32

    /** Size of [hash]. */
    public const val HASH_BYTES: Int = 32

    /** Longest token the server hashes; anything longer cannot be one of ours and is refused outright. */
    public const val MAX_TOKEN_CHARS: Int = 512

    private const val ID_BYTES = 9
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val defaultRandom = SecureRandom()

    /**
     * A new token: [TOKEN_BYTES] random bytes from a [SecureRandom], URL-safe Base64 without padding (43 characters,
     * safe in a header), plus its hash.
     */
    public fun generate(random: SecureRandom = defaultRandom): IssuedToken {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = encoder.encodeToString(bytes)
        return IssuedToken(token, hash(token))
    }

    /** A new random pairing id, valid for [Pairing]: 12 URL-safe characters. */
    public fun generateId(random: SecureRandom = defaultRandom): String =
        encoder.encodeToString(ByteArray(ID_BYTES).also(random::nextBytes))

    /** SHA-256 of [token] (as US-ASCII bytes, which is what a Bearer token is). */
    public fun hash(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.ISO_8859_1))

    /** Constant-time comparison of two hashes (`MessageDigest.isEqual`). */
    public fun sameHash(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
