package eus.bihurri.kora.tailnet

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.mcp.http.DenialReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

/** The public API of an app wiring: switch, pairings, grants, and the pieces it leaves to the service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KoraTailnetTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val source = FakeAddressSource()
    private val access = TestAccess()
    private val loopback = address("127.0.0.1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val port = freePort()

    private fun config() = TailnetConfig(port = port, idleTimeout = 30.minutes, notification = TailnetNotification(smallIcon = android.R.drawable.stat_notify_sync))

    private fun install(serviceContext: Context = app) {
        KoraTailnet.install(app, testNode(access), config(), source, scope, allowNonTailnetAddress = true, serviceContext = serviceContext)
    }

    @Before
    fun setUp() {
        KoraTailnet.uninstall()
    }

    @After
    fun tearDown() {
        KoraTailnet.uninstall()
        scope.cancel()
    }

    @Test
    fun `before install every call says so`() {
        assertThrows(IllegalStateException::class.java) { KoraTailnet.start() }
        assertThrows(IllegalStateException::class.java) { KoraTailnet.pair("x") }
        assertThrows(IllegalStateException::class.java) { KoraTailnet.status }
    }

    @Test
    fun `installing twice is refused`() {
        install()

        assertThrows(IllegalStateException::class.java) { install() }
    }

    @Test
    fun `a config needs a fixed port, a positive idle period and a valid path`() {
        val notification = TailnetNotification(smallIcon = 1)

        assertThrows(IllegalArgumentException::class.java) { TailnetConfig(port = 0, idleTimeout = 1.minutes, notification = notification) }
        assertThrows(IllegalArgumentException::class.java) { TailnetConfig(port = 70000, idleTimeout = 1.minutes, notification = notification) }
        assertThrows(IllegalArgumentException::class.java) { TailnetConfig(port = 80, idleTimeout = kotlin.time.Duration.ZERO, notification = notification) }
        assertThrows(IllegalArgumentException::class.java) { TailnetConfig(port = 80, idleTimeout = 1.minutes, notification = notification, path = "mcp") }
    }

    @Test
    fun `start launches the foreground service and waits for the tailnet, stop stops both`() {
        install()
        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)

        KoraTailnet.start()

        assertEquals(TailnetStatus.WaitingForTailnet, KoraTailnet.status.value)
        val started = shadowOf(app).nextStartedService
        assertEquals(TailnetService::class.java.name, started.component?.className)

        KoraTailnet.stop()

        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)
        assertEquals(TailnetService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
    }

    @Test
    fun `a foreground service that may not start is an error status, not a crash`() {
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?) = throw IllegalStateException("background start not allowed")
        }
        install(serviceContext = refusing)

        KoraTailnet.start()

        val status = KoraTailnet.status.value
        assertTrue(status.toString(), status is TailnetStatus.Error && "background start not allowed" in status.reason)
        assertFalse(source.started)
    }

    @Test
    fun `an agent with a token reaches the node only with the grants the app gave to its caller`() {
        install()
        source.initial = loopback
        KoraTailnet.start()
        val issued = KoraTailnet.pair("Claude on my-desktop")
        val listening = KoraTailnet.status.value as TailnetStatus.Listening
        assertEquals("http://127.0.0.1:$port/mcp", listening.url)

        // Paired but not granted: the node's policy refuses, and the app hears who asked for what.
        val refused = post("127.0.0.1", port, LIST_PLAYLISTS_CALL, issued.token, LIST_PLAYLISTS_HEADERS)
        assertEquals(200, refused.status)
        assertTrue(refused.body, "\"isError\":true" in refused.body)
        assertEquals(listOf(Caller(CallerKind.REMOTE_AGENT, "tailnet:${issued.pairing.id}") to MediaLibrary.ReadScope), access.refused)
        assertEquals(KoraTailnet.caller(issued.pairing), access.refused.single().first)

        access.grants[issued.caller] = setOf(MediaLibrary.ReadScope)
        val allowed = post("127.0.0.1", port, LIST_PLAYLISTS_CALL, issued.token, LIST_PLAYLISTS_HEADERS)
        assertEquals(200, allowed.status)
        assertTrue(allowed.body, "\"isError\":true" !in allowed.body && "pl-0" in allowed.body)
    }

    @Test
    fun `each pairing is its own caller and revoking one leaves the other working`() {
        install()
        source.initial = loopback
        KoraTailnet.start()
        val first = KoraTailnet.pair("first")
        val second = KoraTailnet.pair("second")
        assertNotEquals(first.caller, second.caller)
        access.grants[first.caller] = setOf(MediaLibrary.ReadScope)
        access.grants[second.caller] = setOf(MediaLibrary.ReadScope)
        assertNotEquals(401, post("127.0.0.1", port, PING, first.token).status)

        assertTrue(KoraTailnet.revoke(first.pairing.id))

        assertEquals(401, post("127.0.0.1", port, PING, first.token).status)
        assertNotEquals(401, post("127.0.0.1", port, PING, second.token).status)
        assertEquals(listOf(second.pairing), KoraTailnet.pairings())
        assertFalse(KoraTailnet.revoke(first.pairing.id))
    }

    @Test
    fun `refused requests are published for the app to show`() = runBlocking {
        install()
        source.initial = loopback
        KoraTailnet.start()
        val seen = scope.launchFirst(KoraTailnet.denied)

        post("127.0.0.1", port, PING, "not-a-token")

        val attempt = withTimeout(5_000) { seen.await() }
        assertEquals(DenialReason.INVALID_TOKEN, attempt.reason)
        assertEquals("127.0.0.1", attempt.remoteAddress)
    }

    @Test
    fun `a pairing needs a sensible name`() {
        install()

        for (bad in listOf("", "   ", "x".repeat(81), "line\nbreak", "bell\u0007")) {
            assertThrows(bad, IllegalArgumentException::class.java) { KoraTailnet.pair(bad) }
        }
        assertTrue(KoraTailnet.pairings().isEmpty())
        assertEquals("trimmed", KoraTailnet.pair("  trimmed ").pairing.name)
    }

    @Test
    fun `the token is shown once, not in its text form and not on disk`() {
        install()

        val issued = KoraTailnet.pair("Claude")

        assertFalse(issued.token in issued.toString())
        assertTrue("redacted" in issued.toString())
        assertTrue(issued.token.length >= 43)
        val files = app.noBackupFilesDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { assertFalse(issued.token in it.readText()) }
        assertNull(files.firstOrNull { it.name.endsWith(".tmp") })
    }

    @Test
    fun `pairings survive a restart of the app, revoked ids stay revoked`() {
        install()
        val kept = KoraTailnet.pair("kept")
        val gone = KoraTailnet.pair("gone")
        KoraTailnet.revoke(gone.pairing.id)
        KoraTailnet.uninstall()

        install()

        assertEquals(listOf(kept.pairing), KoraTailnet.pairings())
        assertTrue(File(app.noBackupFilesDir, "kora-tailnet-pairings.json").exists())
    }

    @Test
    fun `the caller of a pairing is the REMOTE_AGENT tailnet id and the name plays no part`() {
        install()
        val issued = KoraTailnet.pair("any name")

        assertEquals(Caller(CallerKind.REMOTE_AGENT, "tailnet:${issued.pairing.id}"), KoraTailnet.caller(issued.pairing))
        assertEquals(KoraTailnet.caller(issued.pairing), KoraTailnet.caller(issued.pairing.id))
    }

    @Test
    fun `start fails closed while a revocation could not be saved, and works once it can`() {
        var failing = false
        KoraTailnet.install(
            app, testNode(access), config(), source, scope, allowNonTailnetAddress = true,
            pairingsMove = { from, to -> if (failing) throw IOException("storage full") else atomicMove(from, to) },
        )
        val issued = KoraTailnet.pair("agent")
        failing = true
        assertThrows(IOException::class.java) { KoraTailnet.revoke(issued.pairing.id) }

        KoraTailnet.start()

        val status = KoraTailnet.status.value
        assertTrue(status.toString(), status is TailnetStatus.Error && "could not be saved" in status.reason)
        assertFalse(source.started)
        assertNull(shadowOf(app).nextStartedService)
        assertThrows(IOException::class.java) { KoraTailnet.pair("another") }

        failing = false
        KoraTailnet.start()

        assertEquals(TailnetStatus.WaitingForTailnet, KoraTailnet.status.value)
        assertEquals(TailnetService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }
}
