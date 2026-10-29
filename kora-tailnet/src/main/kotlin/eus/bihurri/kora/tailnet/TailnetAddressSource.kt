package eus.bihurri.kora.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Where the Tailscale address comes from. Reports the current address (null when there is none) as soon as it is
 * started, and again whenever it may have changed; the callback runs on any thread and must not block.
 */
internal interface TailnetAddressSource {
    fun start(onAddress: (InetAddress?) -> Unit)

    fun stop()
}

/**
 * The Android source: a `ConnectivityManager` callback for VPN networks, with the interface list as a fallback.
 *
 * - **Primary.** A network callback whose request asks for `TRANSPORT_VPN` and clears `NET_CAPABILITY_NOT_VPN` (the
 *   platform sets it by default and says it must be cleared to see VPN networks). On Android 12+ it also includes
 *   networks that do not apply to this app's uid, so Tailscale stays visible even if the user excluded this app from
 *   it. Each network's capabilities and link properties are tracked, and a network counts as a VPN only once its
 *   capabilities were seen to carry `TRANSPORT_VPN`.
 * - **Fallback.** On every update the interface list is scanned too, and an interface counts as a VPN only if it is
 *   named like a tunnel device (`tun<N>`, [looksLikeTunnelInterface]). A carrier interface (`rmnet_data4` holds a CGNAT
 *   100.x address on the owner's phone) never does. The fallback only runs when the callback fires or at start, so it
 *   covers a VPN that the callback cannot see at start, not one that comes up later without any callback.
 *
 * Either way a VPN is not enough: [selectTailnetAddress] also requires the Tailscale IPv6 address on it, so another
 * VPN that happens to use 100.64.0.0/10 is not mistaken for the tailnet.
 *
 * Reports are delivered under the same lock that guards the tracked networks, so they reach [start]'s callback in the
 * order the state changed even when the system calls from several threads; the callback must therefore not block.
 */
internal class AndroidTailnetAddressSource(
    context: Context,
    private val interfaces: () -> List<NetworkCandidate> = ::scanInterfaces,
) : TailnetAddressSource {
    private class Tracked(var isVpn: Boolean? = null, var interfaceName: String? = null, var addresses: List<InetAddress>? = null)

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val networks = HashMap<Network, Tracked>()
    private var report: ((InetAddress?) -> Unit)? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onAddress: (InetAddress?) -> Unit) {
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                update(network) { it.isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = update(network) {
                it.interfaceName = linkProperties.interfaceName
                it.addresses = linkProperties.linkAddresses.map { link -> link.address }
            }

            override fun onLost(network: Network) = synchronized(lock) {
                networks.remove(network)
                publishLocked()
            }
        }
        synchronized(lock) {
            check(callback == null) { "Already started" }
            report = onAddress
            callback = registered
            networks.clear()
        }
        try {
            connectivity.registerNetworkCallback(vpnRequest(), registered)
        } catch (t: Throwable) {
            synchronized(lock) {
                callback = null
                report = null
            }
            throw t
        }
        // The callback delivers the networks that exist now; the interface scan covers what it cannot see.
        synchronized(lock) { publishLocked() }
    }

    override fun stop() {
        val registered = synchronized(lock) {
            report = null
            networks.clear()
            callback.also { callback = null }
        } ?: return
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun update(network: Network, change: (Tracked) -> Unit) = synchronized(lock) {
        change(networks.getOrPut(network) { Tracked() })
        publishLocked()
    }

    /** Computes and delivers the current address; called with [lock] held, so deliveries cannot be reordered. */
    private fun publishLocked() {
        val target = report ?: return
        val tracked = networks.values.mapNotNull {
            val isVpn = it.isVpn
            val addresses = it.addresses
            if (isVpn == null || addresses == null) null else NetworkCandidate(isVpn, it.interfaceName, addresses)
        }
        target(selectTailnetAddress(tracked + interfaces()))
    }

    companion object {
        /** The request for VPN networks, see the class comment. */
        fun vpnRequest(): NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setIncludeOtherUidNetworks(true)
            }
            .build()

        /** The up interfaces with their addresses; a tunnel device name is the only claim of VPN-ness made here. */
        fun scanInterfaces(): List<NetworkCandidate> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .map { NetworkCandidate(looksLikeTunnelInterface(it.name), it.name, it.inetAddresses.toList()) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
