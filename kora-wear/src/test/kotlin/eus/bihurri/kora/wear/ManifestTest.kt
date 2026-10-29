package eus.bihurri.kora.wear

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What only the merged manifest and resources can show: the service filter and the advertised capability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManifestTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun messageIntent(path: String) =
        Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED", Uri.parse("wear://some-node-id$path"))

    @Test
    fun `relay messages are routed to the listener service`() {
        val resolved = app.packageManager.queryIntentServices(messageIntent(WearProtocol.PATH_FRAME), 0)

        assertEquals(listOf(KoraWearListenerService::class.java.name), resolved.map { it.serviceInfo.name })
        assertTrue(resolved.single().serviceInfo.exported)
    }

    @Test
    fun `messages on other paths do not wake the app`() {
        assertTrue(app.packageManager.queryIntentServices(messageIntent("/other/path"), 0).isEmpty())
        assertTrue(app.packageManager.queryIntentServices(messageIntent("/kora/v2/frame"), 0).isEmpty())
    }

    @Test
    fun `capability changes and data items do not wake the app`() {
        for (action in listOf("com.google.android.gms.wearable.CAPABILITY_CHANGED", "com.google.android.gms.wearable.DATA_CHANGED")) {
            val intent = Intent(action, Uri.parse("wear://some-node-id${WearProtocol.PATH_FRAME}"))
            assertTrue(action, app.packageManager.queryIntentServices(intent, 0).isEmpty())
        }
    }

    @Test
    fun `the capability the library advertises matches the protocol constant`() {
        val id = app.resources.getIdentifier("android_wear_capabilities", "array", app.packageName)
        assertTrue("android_wear_capabilities is not defined", id != 0)

        assertEquals(listOf(WearProtocol.CAPABILITY), app.resources.getStringArray(id).toList())
    }
}
