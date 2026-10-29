package eus.bihurri.kora.tailnet

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The thin adapter over `ConnectivityManager`: what it asks for, and when a network counts as the tailnet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTailnetAddressSourceTest {
    private val reported = mutableListOf<InetAddress?>()
    private lateinit var manager: ConnectivityManager
    private var scanned: List<NetworkCandidate> = emptyList()

    @Volatile
    private var scanHook: (() -> Unit)? = null
    private lateinit var source: AndroidTailnetAddressSource

    @Before
    fun setUp() {
        manager = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        source = AndroidTailnetAddressSource(RuntimeEnvironment.getApplication(), interfaces = { scanHook?.invoke(); scanned })
    }

    @After
    fun tearDown() = source.stop()

    private val callback get() = (Shadow.extract<ShadowConnectivityManager>(manager)).networkCallbacks.single()

    private fun network(id: Int): Network = ShadowNetwork.newInstance(id)

    private fun capabilities(vararg transports: Int): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        val shadow = Shadow.extract<ShadowNetworkCapabilities>(capabilities)
        transports.forEach(shadow::addTransportType)
        return capabilities
    }

    private fun link(name: String, vararg addresses: String): LinkProperties {
        val properties = LinkProperties()
        properties.interfaceName = name
        // The public API cannot build these; the runtime class can.
        val make = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
        val add = LinkProperties::class.java.getMethod("addLinkAddress", LinkAddress::class.java)
        addresses.forEach { add.invoke(properties, make.newInstance(InetAddress.getByName(it), 32)) }
        return properties
    }

    private fun start() = source.start { reported += it }

    @Test
    fun `the request asks for VPN networks and clears NOT_VPN, which the platform sets by default`() {
        val request = AndroidTailnetAddressSource.vpnRequest()

        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test
    fun `a VPN network with a Tailscale address is reported once both its capabilities and links are known`() {
        start()
        assertEquals(listOf<InetAddress?>(null), reported)
        val tailscale = network(1)

        callback.onLinkPropertiesChanged(tailscale, link("tun0", "100.73.42.82", "fd7a:115c:a1e0::1234"))
        assertNull(reported.last())
        callback.onCapabilitiesChanged(tailscale, capabilities(NetworkCapabilities.TRANSPORT_VPN))

        assertEquals("100.73.42.82", reported.last()?.hostAddress)
    }

    @Test
    fun `a network without the VPN transport is ignored even with a Tailscale-range address`() {
        start()
        val carrier = network(2)

        callback.onCapabilitiesChanged(carrier, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        callback.onLinkPropertiesChanged(carrier, link("rmnet_data4", "100.109.122.102"))

        assertNull(reported.last())
    }

    @Test
    fun `the carrier address never wins over the VPN one`() {
        start()
        val carrier = network(2)
        val tailscale = network(1)
        callback.onCapabilitiesChanged(carrier, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        callback.onLinkPropertiesChanged(carrier, link("rmnet_data4", "100.109.122.102"))

        callback.onCapabilitiesChanged(tailscale, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        callback.onLinkPropertiesChanged(tailscale, link("tun0", "100.73.42.82", "fd7a:115c:a1e0::1234"))

        assertEquals("100.73.42.82", reported.last()?.hostAddress)
    }

    @Test
    fun `losing the VPN network reports no address, a changed link reports the new one`() {
        start()
        val tailscale = network(1)
        callback.onCapabilitiesChanged(tailscale, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        callback.onLinkPropertiesChanged(tailscale, link("tun0", "100.73.42.82", "fd7a:115c:a1e0::1234"))

        callback.onLinkPropertiesChanged(tailscale, link("tun0", "100.99.1.1", "fd7a:115c:a1e0::1234"))
        assertEquals("100.99.1.1", reported.last()?.hostAddress)

        callback.onLost(tailscale)
        assertNull(reported.last())
    }

    @Test
    fun `the interface fallback accepts a tunnel device with a Tailscale address when no network was reported`() {
        scanned = listOf(
            NetworkCandidate(false, "rmnet_data4", listOf(InetAddress.getByName("100.109.122.102"))),
            NetworkCandidate(true, "tun0", listOf(InetAddress.getByName("100.73.42.82"), InetAddress.getByName("fd7a:115c:a1e0::1234"))),
        )

        start()

        assertEquals("100.73.42.82", reported.last()?.hostAddress)
    }

    @Test
    fun `another VPN on 100 x without the Tailscale IPv6 address is not the tailnet`() {
        start()
        val warp = network(3)

        callback.onCapabilitiesChanged(warp, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        callback.onLinkPropertiesChanged(warp, link("tun0", "100.96.1.2"))

        assertNull(reported.last())
    }

    @Test
    fun `the interface fallback never accepts the carrier interface`() {
        scanned = listOf(NetworkCandidate(false, "rmnet_data4", listOf(InetAddress.getByName("100.109.122.102"))))

        start()

        assertNull(reported.last())
    }

    @Test
    fun `the real interface scan only marks tunnel devices as VPN`() {
        val candidates = AndroidTailnetAddressSource.scanInterfaces()

        candidates.forEach { assertEquals(it.interfaceName, looksLikeTunnelInterface(it.interfaceName!!), it.isVpn) }
    }

    @Test
    fun `reports from two threads reach the listener in the order the state changed`() {
        start()
        val tailscale = network(1)
        callback.onCapabilitiesChanged(tailscale, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The first event stalls while it computes its report; the second arrives meanwhile.
        scanHook = {
            scanHook = null
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val first = thread { callback.onLinkPropertiesChanged(tailscale, link("tun0", "100.64.0.1", "fd7a:115c:a1e0::1234")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = thread { callback.onLinkPropertiesChanged(tailscale, link("tun0", "100.64.0.2", "fd7a:115c:a1e0::1234")) }
        Thread.sleep(200)

        release.countDown()
        first.join(5_000)
        second.join(5_000)

        val seen = reported.mapNotNull { it?.hostAddress }
        assertEquals(listOf("100.64.0.1", "100.64.0.2"), seen)
    }

    @Test
    fun `stop unregisters the callback and later events reach nobody`() {
        start()
        val registered = callback
        val count = reported.size

        source.stop()

        assertTrue(Shadow.extract<ShadowConnectivityManager>(manager).networkCallbacks.isEmpty())
        registered.onLost(network(1))
        assertEquals(count, reported.size)
    }

    @Test
    fun `starting twice without stopping is a mistake`() {
        start()

        assertThrows(IllegalStateException::class.java) { start() }
    }
}
