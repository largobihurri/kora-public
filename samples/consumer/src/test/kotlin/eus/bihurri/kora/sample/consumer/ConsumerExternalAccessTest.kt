package eus.bihurri.kora.sample.consumer

import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.DefaultCallerPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The consumer opens its gate to the adb shell for the read tools it relays to the provider, and nothing else. */
class ConsumerExternalAccessTest {
    private val shell = ConsumerExternalAccess.SHELL_CALLER
    private val denied = mutableListOf<String>()
    private val access = ConsumerExternalAccess(log = { denied += it })
    private val policy = DefaultCallerPolicy(access)

    @Test
    fun `the shell is granted exactly the read scopes of the relayed tools`() {
        assertEquals(
            setOf(MediaLibrary.ReadScope, MediaArtwork.ReadScope, MediaPlayback.ReadScope),
            runBlocking { access.grantedScopes(shell) },
        )
        assertTrue(runBlocking { policy.authorize(shell, MediaArtwork.ReadScope) })
        assertTrue(runBlocking { policy.authorize(shell, MediaLibrary.ReadScope) })
    }

    @Test
    fun `playback control is refused and reported`() {
        assertFalse(runBlocking { policy.authorize(shell, MediaPlayback.ControlScope) })
        assertEquals(1, denied.size)
        assertTrue(denied.single().contains("scope=media.playback.control"), denied.single())
    }

    @Test
    fun `another external caller gets nothing`() {
        val app = Caller(CallerKind.EXTERNAL_APP, "com.example.other")

        assertEquals(emptySet<Any>(), runBlocking { access.grantedScopes(app) })
        assertFalse(runBlocking { policy.authorize(app, MediaArtwork.ReadScope) })
    }
}
