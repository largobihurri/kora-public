package eus.bihurri.kora.tailnet

import eus.bihurri.kora.mcp.http.Pairing
import eus.bihurri.kora.mcp.http.PairingStore
import eus.bihurri.kora.mcp.http.PairingTokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * The pairings of an app, kept in one JSON file (the app's `noBackupFilesDir`, so they are neither backed up nor
 * restored onto another device: a token must not travel with a backup).
 *
 * - **Hashes only.** The file holds each pairing's id, name, creation time and the SHA-256 *hash* of its token. The
 *   token itself is never written anywhere; it exists only in the [IssuedPairing] handed to the caller of `pair`.
 * - **Atomic.** Every change is written to a temporary file next to the target, synced, and moved over the target in
 *   one step, so a crash leaves either the old file or the new one, never a mix.
 * - **Tolerant.** Unknown keys are ignored (a newer library may add fields). One malformed entry is skipped without
 *   losing the others. A file that is not valid JSON at all is moved aside to `<name>.corrupt` and the store starts
 *   empty: nobody is authenticated by a file that cannot be trusted, and the owner pairs again.
 * - **Ids are never reused.** Revoked ids are kept in the file too, so a pairing created later cannot take over the
 *   grants, audit trail or session that belonged to a revoked one (the id is part of the caller identity). Same
 *   guarantee as [eus.bihurri.kora.mcp.http.InMemoryPairingStore].
 *
 * Reads ([find], [pairings]) never touch the disk and take no lock: the state is an immutable snapshot swapped in
 * whole. [add] writes first and publishes after, so a failed write leaves the store as it was; [revoke] publishes
 * first (a token must stop working even if the disk is full) and then writes.
 *
 * **Fail closed after a failed revocation write.** The revoked token is refused in this process, but the file still
 * lists the pairing, so after a restart it would work again. The store therefore remembers that the file is behind
 * ([unsaved]) and retries the write before it does anything else that matters: [add] and [ensurePersisted] (which the
 * listener calls before it starts) throw while it still cannot be written, so nothing new is paired and nothing is
 * served on top of a revocation that is not on disk. What cannot be covered: if the process dies while the disk is still
 * unwritable, the file is all that is left; the [IOException] of [revoke] is the app's cue to tell the user.
 */
internal class FilePairingStore(
    private val file: File,
    private val log: (String) -> Unit = {},
    private val move: (Path, Path) -> Unit = ::atomicMove,
) : PairingStore {
    private class Entry(val pairing: Pairing, val tokenHash: ByteArray)

    private class Snapshot(val entries: List<Entry>, val revoked: Set<String>)

    @Serializable
    private class StoredPairing(
        val id: String,
        val name: String = "",
        val createdAtEpochMs: Long = 0,
        val tokenHash: String,
    )

    @Serializable
    private class StoredFile(
        val version: Int = FORMAT_VERSION,
        val pairings: List<StoredPairing>,
        val revoked: List<String>,
    )

    private val lock = Any()

    @Volatile
    private var snapshot: Snapshot = load()

    /** True while a revocation (or anything else) is in memory but not yet in the file. */
    @Volatile
    var unsaved: Boolean = false
        private set

    /** The pairings that exist now, oldest first. */
    fun pairings(): List<Pairing> = snapshot.entries.map { it.pairing }

    override fun find(tokenHash: ByteArray): Pairing? {
        var found: Pairing? = null
        // Visit every entry: how long the lookup takes must not depend on which entry matched.
        for (entry in snapshot.entries) {
            if (PairingTokens.sameHash(entry.tokenHash, tokenHash)) found = entry.pairing
        }
        return found
    }

    /**
     * Stores [pairing] as the holder of the token with hash [tokenHash] and persists it.
     *
     * @throws IllegalArgumentException when the id exists or was revoked, or the hash has the wrong size.
     * @throws IOException when the file cannot be written; nothing was added then.
     */
    fun add(pairing: Pairing, tokenHash: ByteArray) {
        require(tokenHash.size == PairingTokens.HASH_BYTES) { "A token hash is ${PairingTokens.HASH_BYTES} bytes" }
        synchronized(lock) {
            ensurePersisted()
            val current = snapshot
            require(pairing.id !in current.revoked) { "Pairing id ${pairing.id} was revoked and cannot be reused" }
            require(current.entries.none { it.pairing.id == pairing.id }) { "Pairing ${pairing.id} already exists" }
            val next = Snapshot(current.entries + Entry(pairing, tokenHash.copyOf()), current.revoked)
            write(next)
            snapshot = next
        }
    }

    /**
     * Removes the pairing and remembers its id as revoked. Its token stops working on the next request, even if
     * writing the file fails (the [IOException] is then thrown after the revocation took effect in memory; it would
     * come back after a restart until the next successful write, so the caller must report it).
     *
     * @return whether the pairing existed.
     */
    fun revoke(pairingId: String): Boolean {
        synchronized(lock) {
            val current = snapshot
            if (current.entries.none { it.pairing.id == pairingId }) return false
            val next = Snapshot(current.entries.filter { it.pairing.id != pairingId }, current.revoked + pairingId)
            snapshot = next
            unsaved = true
            write(next)
            unsaved = false
            return true
        }
    }

    /**
     * Writes the file again if a revocation could not be saved earlier; does nothing otherwise.
     *
     * @throws IOException while the file still cannot be written.
     */
    fun ensurePersisted() {
        synchronized(lock) {
            if (!unsaved) return
            write(snapshot)
            unsaved = false
        }
    }

    // ---- Disk ----------------------------------------------------------------------------------------------------

    private fun load(): Snapshot {
        // A leftover of a write that never finished: the target is still the last complete state.
        temporary().delete()
        if (!file.exists()) return Snapshot(emptyList(), emptySet())
        val root = try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            quarantine(e)
            return Snapshot(emptyList(), emptySet())
        }
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (element in root["pairings"] as? JsonArray ?: JsonArray(emptyList())) {
            val entry = try {
                decode(element as JsonObject)
            } catch (e: Exception) {
                log("Skipped an unreadable pairing entry (${e.javaClass.simpleName})")
                null
            } ?: continue
            if (seen.add(entry.pairing.id)) entries += entry else log("Skipped a duplicate pairing entry")
        }
        val revoked = (root["revoked"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .toMutableSet()
        // An id that is both live and revoked is revoked: the safe reading of an inconsistent file.
        entries.removeAll { it.pairing.id in revoked }
        return Snapshot(entries, revoked)
    }

    private fun decode(element: JsonObject): Entry? {
        val stored = JSON.decodeFromJsonElement(StoredPairing.serializer(), element)
        val hash = Base64.getUrlDecoder().decode(stored.tokenHash)
        if (hash.size != PairingTokens.HASH_BYTES) return null
        // The id is validated by Pairing's own rule; an invalid one throws and the entry is skipped.
        return Entry(Pairing(stored.id, stored.name, stored.createdAtEpochMs), hash)
    }

    private fun write(state: Snapshot) {
        val stored = StoredFile(
            pairings = state.entries.map {
                StoredPairing(it.pairing.id, it.pairing.name, it.pairing.createdAtEpochMs, ENCODER.encodeToString(it.tokenHash))
            },
            revoked = state.revoked.sorted(),
        )
        val bytes = JSON.encodeToString(StoredFile.serializer(), stored).toByteArray(Charsets.UTF_8)
        val temporary = temporary()
        try {
            file.absoluteFile.parentFile?.mkdirs()
            java.io.FileOutputStream(temporary).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            move(temporary.toPath(), file.toPath())
        } catch (e: Exception) {
            temporary.delete()
            throw e as? IOException ?: IOException("Could not write the pairings file", e)
        }
    }

    private fun temporary() = File(file.absoluteFile.parentFile, file.name + ".tmp")

    private fun quarantine(cause: Exception) {
        val aside = File(file.absoluteFile.parentFile, file.name + ".corrupt")
        val moved = try {
            Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        }
        log("The pairings file is unreadable (${cause.javaClass.simpleName}); starting empty" + if (moved) ", kept as ${aside.name}" else "")
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    }
}

internal fun atomicMove(from: Path, to: Path) {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
