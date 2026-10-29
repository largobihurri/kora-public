package eus.bihurri.kora.android

import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerIdentifierTest {
    private class FakeInspector : PackageInspector {
        override val ownUid = 10_100
        val packages = mutableMapOf<Int, List<String>>()
        val signed = mutableSetOf<Int>()
        val digests = mutableMapOf<String, String>()
        val digestLookups = mutableListOf<String>()

        override fun packagesForUid(uid: Int) = packages[uid].orEmpty()

        override fun sharesSignatureWithUs(uid: Int) = uid in signed

        override fun signingCertificateSha256(packageName: String): String? {
            digestLookups += packageName
            return digests[packageName]
        }
    }

    private val inspector = FakeInspector()
    private val identifier = CallerIdentifier(inspector, nodeId = "eus.bihurri.me")

    @Test
    fun `same uid is the app itself even when it has no package listing`() {
        assertEquals(Caller(CallerKind.SELF, "eus.bihurri.me"), identifier.resolve(10_100))
    }

    @Test
    fun `matching signature is a peer identified by package name`() {
        inspector.packages[10_200] = listOf("eus.bihurri.other")
        inspector.signed += 10_200
        assertEquals(Caller(CallerKind.PEER, "eus.bihurri.other"), identifier.resolve(10_200))
    }

    @Test
    fun `mismatching signature is an external app bound to its certificate`() {
        inspector.packages[10_300] = listOf("com.thirdparty")
        inspector.digests["com.thirdparty"] = DIGEST
        assertEquals(Caller(CallerKind.EXTERNAL_APP, "com.thirdparty", DIGEST), identifier.resolve(10_300))
    }

    @Test
    fun `the same package under another certificate is a different caller`() {
        inspector.packages[10_300] = listOf("com.thirdparty")
        inspector.digests["com.thirdparty"] = DIGEST
        val first = identifier.resolve(10_300)
        inspector.digests["com.thirdparty"] = OTHER_DIGEST
        val second = identifier.resolve(10_300)

        assertEquals("com.thirdparty", second?.id)
        assertNotEquals(first, second)
    }

    @Test
    fun `an external app whose certificate cannot be read is rejected`() {
        inspector.packages[10_300] = listOf("com.thirdparty")
        assertNull(identifier.resolve(10_300))
    }

    @Test
    fun `a shared uid of an external app uses the first package and its certificate`() {
        inspector.packages[10_310] = listOf("com.vendor.zeta", "com.vendor.alpha")
        inspector.digests["com.vendor.alpha"] = DIGEST
        assertEquals(Caller(CallerKind.EXTERNAL_APP, "com.vendor.alpha", DIGEST), identifier.resolve(10_310))
    }

    @Test
    fun `the adb shell and root are shell callers`() {
        assertEquals(Caller(CallerKind.SHELL, "shell"), identifier.resolve(2_000))
        assertEquals(Caller(CallerKind.SHELL, "root"), identifier.resolve(0))
        assertTrue(inspector.digestLookups.isEmpty(), "no package lookups for shell and root")
    }

    @Test
    fun `shell is decided by the uid, not by a package that claims it`() {
        inspector.packages[2_000] = listOf("eus.bihurri.other")
        inspector.signed += 2_000
        assertEquals(CallerKind.SHELL, identifier.resolve(2_000)?.kind)
    }

    @Test
    fun `a signed peer does not get a certificate digest`() {
        inspector.packages[10_200] = listOf("eus.bihurri.other")
        inspector.signed += 10_200
        inspector.digests["eus.bihurri.other"] = DIGEST
        assertNull(identifier.resolve(10_200)?.signingCertificateSha256)
    }

    @Test
    fun `unknown uid with no packages is rejected`() {
        assertNull(identifier.resolve(99_999))
    }

    @Test
    fun `signed uid without any package is rejected`() {
        inspector.signed += 10_400
        assertNull(identifier.resolve(10_400))
    }

    @Test
    fun `shared uid uses the first package in alphabetical order`() {
        inspector.packages[10_500] = listOf("eus.bihurri.zeta", "eus.bihurri.alpha")
        inspector.signed += 10_500
        assertEquals(Caller(CallerKind.PEER, "eus.bihurri.alpha"), identifier.resolve(10_500))
    }

    @Test
    fun `a peer package named like our node does not become SELF`() {
        inspector.packages[10_600] = listOf("eus.bihurri.me")
        inspector.signed += 10_600
        assertEquals(CallerKind.PEER, identifier.resolve(10_600)?.kind)
    }

    private companion object {
        val DIGEST = "ab".repeat(32)
        val OTHER_DIGEST = "cd".repeat(32)
    }
}
