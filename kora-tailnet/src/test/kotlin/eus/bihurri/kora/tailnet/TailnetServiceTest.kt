package eus.bihurri.kora.tailnet

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

/** The foreground service: notification, stopping with the session, and what the manifest declares. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TailnetServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val source = FakeAddressSource()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val port = freePort()
    private val now = AtomicLong(1_000_000_000L)
    private val id = TailnetNotification.DEFAULT_NOTIFICATION_ID

    @Before
    fun setUp() {
        KoraTailnet.uninstall()
    }

    @After
    fun tearDown() {
        KoraTailnet.uninstall()
        scope.cancel()
    }

    private fun install() {
        val notification = TailnetNotification(
            smallIcon = android.R.drawable.stat_notify_sync,
            text = { NotificationText("Agent access", "state: ${it::class.simpleName}") },
        )
        KoraTailnet.install(
            app, testNode(), TailnetConfig(port = port, idleTimeout = 30.minutes, notification = notification),
            source, scope, clock = { now.get() }, allowNonTailnetAddress = true,
        )
    }

    private fun startService(action: String? = null) =
        Robolectric.buildService(TailnetService::class.java, Intent(app, TailnetService::class.java).setAction(action))

    private fun title(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun text(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `the service becomes a foreground service with the app's wording and a Stop action`() {
        install()
        KoraTailnet.start()

        val controller = startService().create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())

        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertEquals(TailnetNotification.DEFAULT_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        val notification = shadow.lastForegroundNotification
        assertEquals("Agent access", title(notification))
        assertEquals("state: WaitingForTailnet", text(notification))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf<CharSequence>("Stop"), notification.actions.map { it.title })
        val channel = manager.getNotificationChannel(TailnetNotification.DEFAULT_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `the notification follows the status`() {
        install()
        KoraTailnet.start()
        startService().create().startCommand(0, 1)

        source.emit(address("127.0.0.1"))

        val shown = shadowOf(manager).getNotification(TailnetNotification.DEFAULT_NOTIFICATION_ID)
        assertEquals("state: Listening", text(shown))
    }

    @Test
    fun `the service stops itself when the session ends, whoever ends it`() {
        install()
        KoraTailnet.start()
        val controller = startService().create().startCommand(0, 1)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)

        // The same path as an auto-off: the controller ends the session by itself.
        KoraTailnet.current()!!.controller.fail("gone")

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `the Stop action switches the listener off`() {
        install()
        source.initial = address("127.0.0.1")
        KoraTailnet.start()

        val controller = startService(TailnetService.ACTION_STOP).create().startCommand(0, 1)

        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a service that cannot become a foreground service ends the session with the reason`() {
        install()
        KoraTailnet.start()
        val controller = startService().create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed now"))

        controller.startCommand(0, 1)

        val status = KoraTailnet.status.value
        assertTrue(status.toString(), status is TailnetStatus.Error && "not allowed now" in status.reason)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(source.started)
    }

    @Test
    fun `a stale start with the switch already off still promotes, then stops at once`() {
        install()

        val controller = startService().create().startCommand(0, 1)

        // Promoted first (the platform requires it), then released again, notification included.
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertNull(shadowOf(manager).getNotification(id))
    }

    @Test
    fun `without an installed listener the service promotes with a placeholder and stops`() {
        val controller = startService().create().startCommand(0, 7)

        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertNull(shadowOf(manager).getNotification(TailnetNotification.DEFAULT_NOTIFICATION_ID))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("it stops that start request only, so a newer one is not cut short", 7, shadowOf(controller.get()).stopSelfId)
    }

    @Test
    fun `a service destroyed while its session is on takes the session down with it`() {
        install()
        source.initial = address("127.0.0.1")
        KoraTailnet.start()
        val controller = startService().create().startCommand(0, 1)
        assertTrue(KoraTailnet.current()!!.controller.running.value)

        controller.destroy()

        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)
        assertFalse("the listener must not outlive its foreground notification", source.started)
    }

    @Test
    fun `an old service instance destroyed after a quick stop and start leaves the new session alone`() {
        install()
        KoraTailnet.start()
        val old = startService().create().startCommand(0, 1)
        KoraTailnet.stop()
        KoraTailnet.start()
        val session = KoraTailnet.current()!!.controller.epoch

        old.destroy()

        assertTrue(KoraTailnet.current()!!.controller.running.value)
        assertEquals(session, KoraTailnet.current()!!.controller.epoch)
        assertEquals(TailnetStatus.WaitingForTailnet, KoraTailnet.status.value)
    }

    @Test
    fun `a stale service never ends a session of a newer epoch by stopping itself`() {
        install()
        KoraTailnet.start()
        val first = startService().create().startCommand(0, 3)
        val epoch = KoraTailnet.current()!!.controller.epoch

        KoraTailnet.stop()

        // Its own session ended, so it stops itself, naming its own start request.
        assertTrue(shadowOf(first.get()).isStoppedBySelf)
        assertEquals(3, shadowOf(first.get()).stopSelfId)
        assertEquals(epoch, KoraTailnet.current()!!.controller.epoch)
    }

    // ---- Nothing of ours stays in the shade ------------------------------------------------------------------------

    private fun assertNoNotification(service: android.app.Service) {
        assertNull("no notification of ours may stay", shadowOf(manager).getNotification(id))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `after an auto-off the notification is gone and the service is stopped`() {
        install()
        source.initial = address("127.0.0.1")
        KoraTailnet.start()
        val controller = startService().create().startCommand(0, 1)
        // The status moves through Listening first: the notification is updated while the session is on.
        assertNotNull(shadowOf(manager).getNotification(id))

        now.addAndGet(31.minutes.inWholeNanoseconds)
        KoraTailnet.current()!!.controller.checkIdle()

        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)
        assertNoNotification(controller.get())
    }

    @Test
    fun `after stop the notification is gone and the service is stopped`() {
        install()
        source.initial = address("127.0.0.1")
        KoraTailnet.start()
        val controller = startService().create().startCommand(0, 1)

        KoraTailnet.stop()

        assertNoNotification(controller.get())
    }

    @Test
    fun `after the Stop action the notification is gone and the service is stopped`() {
        install()
        source.initial = address("127.0.0.1")
        KoraTailnet.start()
        val controller = startService().create().startCommand(0, 1)

        controller.withIntent(Intent(app, TailnetService::class.java).setAction(TailnetService.ACTION_STOP)).startCommand(0, 2)

        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)
        assertNoNotification(controller.get())
    }

    @Test
    fun `a notification re-posted by a late update is removed when the service is destroyed`() {
        install()
        KoraTailnet.start()
        val controller = startService().create().startCommand(0, 1)
        KoraTailnet.stop()
        // What the old code did: a plain ongoing notification, no longer tied to the foreground service.
        manager.notify(id, TailnetNotifications.build(app, KoraTailnet.current()!!.config.notification, TailnetStatus.Off))
        assertNotNull(shadowOf(manager).getNotification(id))

        controller.destroy()

        assertNull(shadowOf(manager).getNotification(id))
    }

    @Test
    fun `destroying an old service instance leaves the notification of a newer session alone`() {
        install()
        KoraTailnet.start()
        val old = startService().create().startCommand(0, 1)
        KoraTailnet.stop()
        KoraTailnet.start()
        // The new session's instance has posted its notification.
        manager.notify(id, TailnetNotifications.build(app, KoraTailnet.current()!!.config.notification, TailnetStatus.WaitingForTailnet))

        old.destroy()

        assertNotNull(shadowOf(manager).getNotification(id))
        assertTrue(KoraTailnet.current()!!.controller.running.value)
    }

    // ---- Manifest ------------------------------------------------------------------------------------------------

    @Test
    fun `the manifest declares a private specialUse service with its subtype`() {
        val component = ComponentName(app, TailnetService::class.java)
        val info = app.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
    }

    @Test
    fun `the service declares the specialUse subtype property that Play and Android 14 ask for`() {
        // Robolectric's PackageManager does not read <property>, so the library manifest is read directly (the unit
        // test runs in the module directory).
        val manifest = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val service = manifest.getElementsByTagName("service").item(0) as org.w3c.dom.Element
        val property = service.getElementsByTagName("property").item(0) as org.w3c.dom.Element

        assertEquals("specialUse", service.getAttributeNS(android, "foregroundServiceType"))
        assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", property.getAttributeNS(android, "name"))
        assertTrue(property.getAttributeNS(android, "value").length > 40)
    }

    @Test
    fun `the manifest asks for the permissions the listener needs`() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()

        for (permission in listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        )) assertTrue("$permission is declared", permission in requested)
        assertFalse("POST_NOTIFICATIONS is the app's to request", "android.permission.POST_NOTIFICATIONS" in requested)
    }

    @Test
    fun `the default wording covers every status`() {
        val statuses = listOf(
            TailnetStatus.Off,
            TailnetStatus.WaitingForTailnet,
            TailnetStatus.Listening("100.73.42.82", 47831, "http://100.73.42.82:47831/mcp"),
            TailnetStatus.Error("port in use"),
        )

        for (status in statuses) assertTrue(TailnetNotification.defaultText(status).text.isNotBlank())
        assertTrue("100.73.42.82" in TailnetNotification.defaultText(statuses[2]).text)
    }
}
