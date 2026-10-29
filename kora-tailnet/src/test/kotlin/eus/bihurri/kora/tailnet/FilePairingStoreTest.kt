package eus.bihurri.kora.tailnet

import eus.bihurri.kora.mcp.http.Pairing
import eus.bihurri.kora.mcp.http.PairingTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** The pairing file: hashes only, atomic, tolerant, ids never reused. */
class FilePairingStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "pairings.json")
    private val logs = mutableListOf<String>()

    private fun store(move: (java.nio.file.Path, java.nio.file.Path) -> Unit = { from, to ->
        java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }) = FilePairingStore(file, { logs += it }, move)

    private fun add(store: FilePairingStore, name: String = "claude", id: String = PairingTokens.generateId()): Pair<Pairing, eus.bihurri.kora.mcp.http.IssuedToken> {
        val token = PairingTokens.generate()
        val pairing = Pairing(id, name, 1_000)
        store.add(pairing, token.hash)
        return pairing to token
    }

    private fun hashOf(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun `a pairing is found by its token hash and survives a restart`() {
        val first = store()
        val (pairing, token) = add(first, "Claude on my-desktop")

        assertEquals(pairing, first.find(token.hash))
        val reloaded = store()
        assertEquals(listOf(pairing), reloaded.pairings())
        assertEquals(pairing, reloaded.find(token.hash))
        assertNull(reloaded.find(PairingTokens.generate().hash))
    }

    @Test
    fun `neither the token nor anything but its hash is on disk`() {
        val s = store()
        val (_, token) = add(s)
        add(s)

        dir.walkTopDown().filter { it.isFile }.forEach {
            val text = it.readText()
            assertFalse(token.token in text, "the plain token must never be written (${it.name})")
        }
        val stored = Json.parseToJsonElement(file.readText()).jsonObject["pairings"]!!.jsonArray.first().jsonObject
        assertEquals(hashOf(token.hash), stored["tokenHash"]!!.jsonPrimitive.content)
        assertEquals(setOf("id", "name", "createdAtEpochMs", "tokenHash"), stored.keys)
    }

    @Test
    fun `the file has a version, and names keep their characters`() {
        val s = store()
        add(s, "Agente de Ñandú")

        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("1", root["version"]!!.jsonPrimitive.content)
        assertEquals("Agente de Ñandú", store().pairings().single().name)
    }

    @Test
    fun `a revoked pairing stops matching and its id can never be added again, even after a restart`() {
        val s = store()
        val (pairing, token) = add(s)

        assertTrue(s.revoke(pairing.id))

        assertNull(s.find(token.hash))
        assertFalse(s.revoke(pairing.id))
        for (candidate in listOf(s, store())) {
            assertNull(candidate.find(token.hash))
            assertTrue(candidate.pairings().isEmpty())
            val error = assertThrows(IllegalArgumentException::class.java) { candidate.add(pairing, PairingTokens.generate().hash) }
            assertTrue("revoked" in error.message!!)
        }
    }

    @Test
    fun `an existing id and a wrong sized hash are refused`() {
        val s = store()
        val (pairing, _) = add(s)

        assertThrows(IllegalArgumentException::class.java) { s.add(pairing, PairingTokens.generate().hash) }
        assertThrows(IllegalArgumentException::class.java) { s.add(Pairing("other", "x"), ByteArray(5)) }
        assertEquals(1, s.pairings().size)
    }

    @Test
    fun `ids are validated like Pairing validates them`() {
        assertThrows(IllegalArgumentException::class.java) { Pairing("has space", "x") }
        assertThrows(IllegalArgumentException::class.java) { Pairing("", "x") }
        assertThrows(IllegalArgumentException::class.java) { Pairing("a".repeat(65), "x") }
    }

    @Test
    fun `a failed write leaves the file as it was, no temporary file, and nothing added`() {
        val ok = store()
        add(ok, "first")
        val before = file.readText()
        val failing = store(move = { _, _ -> throw IOException("disk full") })

        assertThrows(IOException::class.java) { add(failing, "second") }

        assertEquals(before, file.readText())
        assertFalse(File(dir, "pairings.json.tmp").exists())
        assertEquals(listOf("first"), failing.pairings().map { it.name })
        assertEquals(listOf("first"), store().pairings().map { it.name })
    }

    @Test
    fun `the new file is moved over the old one in one step`() {
        val moves = mutableListOf<Pair<String, String>>()
        val s = store(move = { from, to ->
            // At the moment of the move the complete new content already sits in the temporary file.
            assertTrue(Json.parseToJsonElement(from.toFile().readText()).jsonObject["pairings"]!!.jsonArray.size == 1)
            moves += from.fileName.toString() to to.fileName.toString()
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        })

        add(s)

        assertEquals(listOf("pairings.json.tmp" to "pairings.json"), moves)
        assertFalse(File(dir, "pairings.json.tmp").exists())
    }

    /** A writer that fails while [failing] is set, like a full disk that is later cleared. */
    private inner class FlakyDisk {
        @Volatile
        var failing = false
        val store = store(move = { from, to ->
            if (failing) throw IOException("disk full")
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        })
    }

    @Test
    fun `a revocation takes effect even when the write fails, and the failure is reported`() {
        val disk = FlakyDisk()
        val (pairing, token) = add(disk.store)
        disk.failing = true

        assertThrows(IOException::class.java) { disk.store.revoke(pairing.id) }

        assertNull(disk.store.find(token.hash))
        assertTrue(disk.store.unsaved)
        assertThrows(IOException::class.java) { disk.store.add(pairing, token.hash) }
    }

    @Test
    fun `while a revocation is not on disk nothing is added and nothing may start, and the write is retried`() {
        val disk = FlakyDisk()
        val (pairing, token) = add(disk.store)
        disk.failing = true
        assertThrows(IOException::class.java) { disk.store.revoke(pairing.id) }

        assertThrows(IOException::class.java) { disk.store.ensurePersisted() }
        assertThrows(IOException::class.java) { add(disk.store, "new agent") }
        assertTrue(disk.store.pairings().isEmpty(), "the failed add left nothing behind")
        // The file still lists the revoked pairing: a restart now would bring it back.
        assertEquals(listOf(pairing), store().pairings())

        disk.failing = false
        disk.store.ensurePersisted()

        assertFalse(disk.store.unsaved)
        assertTrue(store().pairings().isEmpty(), "the revocation reached the disk")
        assertNull(store().find(token.hash))
        assertThrows(IllegalArgumentException::class.java) { store().add(pairing, PairingTokens.generate().hash) }
    }

    @Test
    fun `an add after the disk recovered writes the pending revocation with it`() {
        val disk = FlakyDisk()
        val (revoked, token) = add(disk.store, "old")
        disk.failing = true
        assertThrows(IOException::class.java) { disk.store.revoke(revoked.id) }
        disk.failing = false

        val (added, _) = add(disk.store, "new")

        assertFalse(disk.store.unsaved)
        val reloaded = store()
        assertEquals(listOf(added), reloaded.pairings())
        assertNull(reloaded.find(token.hash))
    }

    @Test
    fun `a leftover temporary file is discarded and does not change the state`() {
        val s = store()
        val (pairing, _) = add(s)
        File(dir, "pairings.json.tmp").writeText("{ half a write")

        val reloaded = store()

        assertEquals(listOf(pairing), reloaded.pairings())
        assertFalse(File(dir, "pairings.json.tmp").exists())
    }

    @Test
    fun `a file that is not JSON is set aside and the store starts empty and usable`() {
        file.writeText("this is not json \u0000\u0001")

        val s = store()

        assertTrue(s.pairings().isEmpty())
        assertEquals("this is not json \u0000\u0001", File(dir, "pairings.json.corrupt").readText())
        assertTrue(logs.any { "unreadable" in it })
        val (pairing, token) = add(s)
        assertEquals(pairing, store().find(token.hash))
    }

    @Test
    fun `valid JSON of the wrong shape is treated as corrupt, an empty object as empty`() {
        file.writeText("[1,2,3]")
        assertTrue(store().pairings().isEmpty())
        assertTrue(File(dir, "pairings.json.corrupt").exists())

        file.writeText("{}")
        assertTrue(store().pairings().isEmpty())
    }

    @Test
    fun `unknown keys are ignored and one bad entry does not cost the others`() {
        val good = PairingTokens.generate()
        file.writeText(
            """
            {"version":7,"future":{"x":1},"revoked":["gone",5,null],
             "pairings":[
               {"id":"ok-1","name":"keeps","createdAtEpochMs":5,"tokenHash":"${hashOf(good.hash)}","extra":true},
               {"id":"bad id!","name":"x","tokenHash":"${hashOf(good.hash)}"},
               {"id":"short","name":"x","tokenHash":"AAAA"},
               {"id":"nohash","name":"x"},
               {"id":"notbase64","name":"x","tokenHash":"%%%"},
               "just a string",
               {"id":"gone","name":"live and revoked","tokenHash":"${hashOf(good.hash)}"},
               {"id":"ok-1","name":"duplicate","tokenHash":"${hashOf(good.hash)}"}
             ]}
            """.trimIndent(),
        )

        val s = store()

        val kept = s.pairings().single()
        assertEquals(Pairing("ok-1", "keeps", 5), kept)
        assertEquals(kept, s.find(good.hash))
        assertThrows(IllegalArgumentException::class.java) { s.add(Pairing("gone", "again"), PairingTokens.generate().hash) }
    }

    @Test
    fun `lookup is correct among many pairings`() {
        val s = store()
        val all = List(20) { add(s, "agent $it") }

        for ((pairing, token) in all) assertEquals(pairing, s.find(token.hash))
    }

    @Test
    fun `concurrent adds are all kept and all persisted`() {
        val s = store()
        val start = CountDownLatch(1)
        val workers = List(8) { n ->
            thread {
                start.await()
                repeat(5) { add(s, "agent $n-$it") }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(40, s.pairings().size)
        assertEquals(40, store().pairings().size)
    }
}
