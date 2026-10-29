package eus.bihurri.kora.android

import android.content.pm.PackageInfo
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The PackageManager-backed inspector fails closed. Reading a real certificate cannot be simulated here (Robolectric
 * has no `SigningInfo` shadow), so that path is covered by the digest unit tests and the on-device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidPackageInspectorTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `an unknown package has no digest`() {
        assertNull(AndroidPackageInspector(app).signingCertificateSha256("com.nobody"))
    }

    @Test
    fun `a package without signing information has no digest`() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.unsigned" })
        assertNull(AndroidPackageInspector(app).signingCertificateSha256("com.unsigned"))
    }
}
