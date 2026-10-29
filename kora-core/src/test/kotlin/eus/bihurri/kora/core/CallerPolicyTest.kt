package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerPolicyTest {
    private val access = FakeExternalAccess()
    private val policy = DefaultCallerPolicy(access)
    private val outsiders = listOf(EXTERNAL, SHELL, AGENT)
    private val allScopes = listOf(MediaPlayback.ReadScope, MediaPlayback.ControlScope, MediaLibrary.ReadScope, Scope("location.read"))

    @Test
    fun `self and peer are allowed every scope without any grant`() = runTest {
        for (caller in listOf(SELF, PEER)) {
            for (scope in allScopes) assertTrue(policy.authorize(caller, scope), "$caller $scope")
        }
    }

    @Test
    fun `self and peer ignore the master switch`() = runTest {
        access.enabled = false
        assertTrue(policy.authorize(SELF, MediaPlayback.ControlScope))
        assertTrue(policy.authorize(PEER, MediaPlayback.ControlScope))
    }

    @Test
    fun `outsiders are denied while the master switch is off even with grants`() = runTest {
        for (caller in outsiders) access.grants[caller] = allScopes.toSet()
        access.enabled = false
        for (caller in outsiders) {
            for (scope in allScopes) assertFalse(policy.authorize(caller, scope), "$caller $scope")
        }
    }

    @Test
    fun `outsiders are denied when the switch is on but no scope was granted`() = runTest {
        access.enabled = true
        for (caller in outsiders) {
            for (scope in allScopes) assertFalse(policy.authorize(caller, scope), "$caller $scope")
        }
    }

    @Test
    fun `outsiders get exactly the scopes granted to them`() = runTest {
        access.enabled = true
        for (caller in outsiders) access.grants[caller] = setOf(MediaLibrary.ReadScope)
        for (caller in outsiders) {
            assertTrue(policy.authorize(caller, MediaLibrary.ReadScope), "$caller")
            assertFalse(policy.authorize(caller, MediaPlayback.ReadScope), "$caller")
        }
    }

    @Test
    fun `control does not imply read`() = runTest {
        access.enabled = true
        access.grants[EXTERNAL] = setOf(MediaPlayback.ControlScope)
        assertTrue(policy.authorize(EXTERNAL, MediaPlayback.ControlScope))
        assertFalse(policy.authorize(EXTERNAL, MediaPlayback.ReadScope))
    }

    @Test
    fun `grants are per caller id`() = runTest {
        access.enabled = true
        access.grants[EXTERNAL] = setOf(MediaPlayback.ReadScope)
        assertFalse(policy.authorize(Caller(CallerKind.EXTERNAL_APP, "com.other.app"), MediaPlayback.ReadScope))
        assertFalse(policy.authorize(Caller(CallerKind.SHELL, EXTERNAL.id), MediaPlayback.ReadScope), "grant is also per kind")
    }

    @Test
    fun `default policy without external access denies outsiders`() = runTest {
        val closed = DefaultCallerPolicy()
        assertFalse(closed.authorize(EXTERNAL, MediaPlayback.ReadScope))
        assertTrue(closed.authorize(PEER, MediaPlayback.ReadScope))
    }

    // ---- certificate binding -----------------------------------------------------------------------------------

    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    @Test
    fun `a grant is bound to the signing certificate of the app`() = runTest {
        val original = Caller(CallerKind.EXTERNAL_APP, "com.thirdparty.app", digestA)
        val reinstalled = Caller(CallerKind.EXTERNAL_APP, "com.thirdparty.app", digestB)
        access.enabled = true
        access.grants[original] = setOf(MediaLibrary.ReadScope)

        assertTrue(policy.authorize(original, MediaLibrary.ReadScope))
        assertFalse(policy.authorize(reinstalled, MediaLibrary.ReadScope), "same package, other key")
        assertFalse(policy.authorize(EXTERNAL, MediaLibrary.ReadScope), "no digest is not the same identity")
    }

    @Test
    fun `a digest must be 64 lowercase hex characters`() {
        for (bad in listOf("", "abc", "A".repeat(64), "g".repeat(64), "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { Caller(CallerKind.EXTERNAL_APP, "x", bad) }
        }
        assertEquals("EXTERNAL_APP:x", Caller(CallerKind.EXTERNAL_APP, "x", digestA).toString())
    }

    // ---- onDenied --------------------------------------------------------------------------------------------

    @Test
    fun `onDenied is reported for every refused outsider with the reason hidden from the decision`() = runTest {
        for (caller in outsiders) {
            access.enabled = false
            assertFalse(policy.authorize(caller, MediaLibrary.ReadScope))
            access.enabled = true
            assertFalse(policy.authorize(caller, MediaPlayback.ReadScope))
        }
        assertEquals(
            outsiders.flatMap { listOf(it to MediaLibrary.ReadScope, it to MediaPlayback.ReadScope) },
            access.denied,
        )
    }

    @Test
    fun `onDenied is not called when the outsider is allowed`() = runTest {
        access.enabled = true
        access.grants[SHELL] = setOf(MediaLibrary.ReadScope)
        assertTrue(policy.authorize(SHELL, MediaLibrary.ReadScope))
        assertTrue(access.denied.isEmpty())
    }

    @Test
    fun `onDenied is never called for self or peer`() = runTest {
        access.enabled = false
        for (scope in allScopes) {
            assertTrue(policy.authorize(SELF, scope))
            assertTrue(policy.authorize(PEER, scope))
        }
        assertTrue(access.denied.isEmpty())
    }

    @Test
    fun `a failing onDenied does not change the refusal`() = runTest {
        access.deniedFailure = IllegalStateException("disk full")
        assertFalse(policy.authorize(EXTERNAL, MediaLibrary.ReadScope))
        assertEquals(1, access.denied.size)
    }

    @Test
    fun `cancellation inside onDenied propagates`() = runTest {
        access.deniedFailure = CancellationException("cancelled")
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { policy.authorize(EXTERNAL, MediaLibrary.ReadScope) }
        }
    }
}
