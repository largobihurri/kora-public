package eus.bihurri.kora.tailnet

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * What the Android side knows about one network interface, reduced to what the choice of address needs.
 *
 * [isVpn] is the claim that makes an address eligible, so each source sets it only on proof: a `ConnectivityManager`
 * network that has `TRANSPORT_VPN`, or (fallback) an interface named like a tunnel device. A carrier interface is
 * never a VPN, even when it holds an address that looks like Tailscale's.
 */
internal class NetworkCandidate(
    val isVpn: Boolean,
    val interfaceName: String?,
    val addresses: List<InetAddress>,
)

/** The address ranges Tailscale assigns: 100.64.0.0/10 and fd7a:115c:a1e0::/48. */
internal object TailscaleRanges {
    private val ULA_PREFIX = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())

    /**
     * Whether [address] lies in a Tailscale range. Loopback is not: unlike `McpHttpServer`'s own bind check, this one
     * decides which address production binds, and nothing but a tailnet address may be chosen.
     *
     * 100.64.0.0/10 is also the carrier-grade NAT range (and other VPNs use it), so an IPv4 address in it says nothing
     * about the interface it is on; see [selectTailnetAddress].
     */
    fun contains(address: InetAddress): Boolean = isIPv4(address) || isIPv6(address)

    /** In 100.64.0.0/10. */
    fun isIPv4(address: InetAddress): Boolean =
        address is Inet4Address && address.address.let { it[0] == 100.toByte() && (it[1].toInt() and 0xC0) == 0x40 }

    /** In fd7a:115c:a1e0::/48, the range Tailscale draws every node's IPv6 address from. */
    fun isIPv6(address: InetAddress): Boolean =
        address is Inet6Address && address.address.copyOf(ULA_PREFIX.size).contentEquals(ULA_PREFIX)
}

/**
 * The address to listen on, or null while there is none (Tailscale is off or not connected yet).
 *
 * An interface qualifies only when both hold:
 * 1. it is known to be a VPN ([NetworkCandidate.isVpn]), and
 * 2. it carries an address in fd7a:115c:a1e0::/48, the signature of Tailscale: every node gets one next to its
 *    100.x IPv4 address, on the same interface.
 *
 * The IPv4 address is only ever taken from a qualifying interface. 100.64.0.0/10 alone proves nothing: mobile carriers
 * hand the phone one on a cellular interface (carrier-grade NAT), and other VPNs (Cloudflare WARP, for one) use the
 * same range; binding there would listen on the wrong network. IPv4 is preferred over IPv6. Among several qualifying
 * addresses the lowest textual one wins, so the same set of addresses always gives the same answer and the listener
 * does not rebind just because the interfaces were enumerated in another order.
 */
internal fun selectTailnetAddress(candidates: List<NetworkCandidate>): InetAddress? {
    val chosen = candidates
        .filter { candidate -> candidate.isVpn && candidate.addresses.any(TailscaleRanges::isIPv6) }
        .mapNotNull { candidate ->
            // Its own IPv4 address when it has one (kept for the agent URL), else its IPv6 address.
            candidate.addresses.filter(TailscaleRanges::isIPv4).minByOrNull { it.hostAddress.orEmpty() }
                ?: candidate.addresses.filter(TailscaleRanges::isIPv6).minByOrNull { it.hostAddress.orEmpty() }
        }
        .sortedBy { it.hostAddress }
    return chosen.firstOrNull { it is Inet4Address } ?: chosen.firstOrNull()
}

/**
 * Whether [name] is how Android names the tunnel device of a VPN app (`tun0`, `tun1`...). Tailscale for Android uses
 * `tun0`. Only used by the fallback that lists `NetworkInterface`s, where there is no `NetworkCapabilities` to ask.
 */
internal fun looksLikeTunnelInterface(name: String): Boolean = TUNNEL_NAME.matches(name)

private val TUNNEL_NAME = Regex("tun[0-9]+")
