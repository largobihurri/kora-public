package eus.bihurri.kora.tailnet

import android.app.AlarmManager
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

/** The idle deadline as a real alarm: which kind, when, and what happens when it fires. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TailnetIdleAlarmTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(AlarmManager::class.java)
    private val now = AtomicLong(1_000_000_000L) // elapsed-realtime nanoseconds, moved by the test
    private val source = FakeAddressSource()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        KoraTailnet.uninstall()
        KoraTailnet.install(
            app, testNode(),
            TailnetConfig(port = freePort(), idleTimeout = 30.minutes, notification = TailnetNotification(smallIcon = android.R.drawable.stat_notify_sync)),
            source, scope, clock = { now.get() },
        )
    }

    @After
    fun tearDown() {
        KoraTailnet.uninstall()
        scope.cancel()
    }

    private val alarms get() = shadowOf(manager).scheduledAlarms

    @Test
    fun `start arms one wake-up alarm that is allowed in Doze at the idle deadline, stop removes it`() {
        KoraTailnet.start()

        val alarm = alarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType())
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals((now.get() + 30.minutes.inWholeNanoseconds) / 1_000_000, alarm.triggerAtMs)

        KoraTailnet.stop()

        assertTrue(alarms.isEmpty())
    }

    @Test
    fun `the alarm of an idle session ends it, even though no coroutine woke up`() {
        KoraTailnet.start()
        val alarm = alarms.single()

        // The alarm's broadcast goes to the (private) receiver of this library.
        val target = shadowOf(alarm.operation).savedIntents.single()
        assertEquals(TailnetIdleReceiver::class.java.name, target.component?.className)

        // The device slept through the deadline; only the alarm runs.
        now.addAndGet(31.minutes.inWholeNanoseconds)
        TailnetIdleReceiver().onReceive(app, target)

        assertEquals(TailnetStatus.Off, KoraTailnet.status.value)
        assertTrue(alarms.isEmpty())
    }

    @Test
    fun `an alarm that fires before the deadline arms the same deadline again instead of ending the session`() {
        KoraTailnet.start()
        val deadline = alarms.single().triggerAtMs

        now.addAndGet(10.minutes.inWholeNanoseconds)
        TailnetIdleReceiver().onReceive(app, Intent(AndroidDeadlineAlarm.ACTION_IDLE))

        assertEquals(TailnetStatus.WaitingForTailnet, KoraTailnet.status.value)
        assertEquals(deadline, alarms.single().triggerAtMs)
    }

    @Test
    fun `the receiver does nothing when no listener is installed`() {
        KoraTailnet.uninstall()

        TailnetIdleReceiver().onReceive(app, Intent(AndroidDeadlineAlarm.ACTION_IDLE))
    }

    @Test
    fun `the alarm is rounded up to the next millisecond`() {
        AndroidDeadlineAlarm(app).scheduleAt(5_000_001L)

        assertEquals(5L + 1, alarms.single().triggerAtMs)
    }
}
