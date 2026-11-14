package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.DefaultCallerPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The adb shell's grants and the "Grant artwork to adb shell" switch that adds or removes `media.artwork.read`. */
class SampleExternalAccessTest {
    private val shell = SampleExternalAccess.SHELL_CALLER
    private val denied = mutableListOf<String>()
    private var artworkGranted = true

    private val access = SampleExternalAccess(shellArtworkGranted = { artworkGranted }, log = { denied += it })
    private val policy = DefaultCallerPolicy(access)

    @Test
    fun `with the switch on the shell reads the library and its artwork`() {
        assertEquals(
            setOf(MediaLibrary.ReadScope, MediaArtwork.ReadScope) + SampleExternalAccess.VOLUME_SCOPES,
            SampleExternalAccess.shellScopes(grantArtwork = true),
        )
        assertTrue(runBlocking { policy.authorize(shell, MediaArtwork.ReadScope) })
        assertTrue(runBlocking { policy.authorize(shell, MediaLibrary.ReadScope) })
    }

    @Test
    fun `with the switch off artwork is refused while the library still answers`() {
        artworkGranted = false

        assertEquals(
            setOf(MediaLibrary.ReadScope) + SampleExternalAccess.VOLUME_SCOPES,
            SampleExternalAccess.shellScopes(grantArtwork = false),
        )
        assertFalse(runBlocking { policy.authorize(shell, MediaArtwork.ReadScope) })
        assertTrue(runBlocking { policy.authorize(shell, MediaLibrary.ReadScope) })
        assertTrue(runBlocking { policy.authorize(shell, MediaVolume.ControlScope) })
        assertEquals(1, denied.size)
        assertTrue(denied.single().contains("scope=media.artwork.read"), denied.single())
    }

    @Test
    fun `the switch is read on every call, so flipping it applies at once`() {
        assertTrue(runBlocking { policy.authorize(shell, MediaArtwork.ReadScope) })
        artworkGranted = false
        assertFalse(runBlocking { policy.authorize(shell, MediaArtwork.ReadScope) })
        artworkGranted = true
        assertTrue(runBlocking { policy.authorize(shell, MediaArtwork.ReadScope) })
    }

    @Test
    fun `another external caller gets nothing`() {
        val app = Caller(CallerKind.EXTERNAL_APP, "com.example.other")

        assertEquals(emptySet<Any>(), runBlocking { access.grantedScopes(app) })
        assertFalse(runBlocking { policy.authorize(app, MediaLibrary.ReadScope) })
    }
}
