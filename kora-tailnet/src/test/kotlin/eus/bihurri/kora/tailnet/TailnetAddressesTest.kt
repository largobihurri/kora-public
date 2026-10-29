package eus.bihurri.kora.tailnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which address becomes the listener's: the one on the VPN interface, never a carrier's look-alike. */
class TailnetAddressesTest {
    private fun candidate(isVpn: Boolean, name: String, vararg addresses: String) =
        NetworkCandidate(isVpn, name, addresses.map(::address))

    private fun select(vararg candidates: NetworkCandidate) = selectTailnetAddress(candidates.toList())?.hostAddress

    // What Tailscale puts on its interface: the 100.x address and the fd7a:115c:a1e0::/48 one.
    private val tailscaleV4 = "100.73.42.82"
    private val tailscaleV6 = "fd7a:115c:a1e0::1234"

    @Test
    fun `the Tailscale interface wins over the carrier CGNAT address in the same range`() {
        // The owner's phone: tun0 is Tailscale, rmnet_data4 is the carrier with its own 100.x address.
        val selected = select(
            candidate(false, "rmnet_data4", "100.109.122.102"),
            candidate(true, "tun0", tailscaleV4, tailscaleV6, "fe80::1"),
            candidate(false, "wlan0", "192.168.1.20"),
        )
        assertEquals(tailscaleV4, selected)
    }

    @Test
    fun `a CGNAT address on a non VPN interface is never chosen, whatever its name`() {
        assertNull(select(candidate(false, "rmnet_data4", "100.109.122.102")))
        assertNull(select(candidate(false, "tun0", tailscaleV4, tailscaleV6)))
    }

    @Test
    fun `a VPN that only has a 100 x address is refused, another VPN can use that range too`() {
        // Cloudflare WARP, for one, uses 100.64.0.0/10 and has no fd7a address.
        assertNull(select(candidate(true, "tun0", "100.96.1.2", "fe80::5")))
        assertNull(select(candidate(true, "tun0", "100.96.1.2", "fd00::1")))
    }

    @Test
    fun `the IPv4 address must be on the same interface as the Tailscale IPv6 one`() {
        // An unrelated VPN holds a 100.x address while Tailscale (IPv6 only here) is on another interface.
        val selected = select(
            candidate(true, "tun1", "100.96.1.2"),
            candidate(true, "tun0", tailscaleV6),
        )
        assertEquals("fd7a:115c:a1e0:0:0:0:0:1234", selected)
    }

    @Test
    fun `a VPN address outside the Tailscale ranges is not chosen`() {
        assertNull(select(candidate(true, "tun0", "10.8.0.2", "192.168.43.1", "172.16.0.5", "fd00::1")))
    }

    @Test
    fun `an IPv6 only tailnet is served by its fd7a address`() {
        assertEquals("fd7a:115c:a1e0:0:0:0:0:1234", select(candidate(true, "tun0", "fe80::2", tailscaleV6)))
    }

    @Test
    fun `IPv4 is preferred when both families are present`() {
        assertEquals(tailscaleV4, select(candidate(true, "tun0", tailscaleV6, tailscaleV4)))
        assertEquals(tailscaleV4, select(candidate(true, "tun0", tailscaleV4, tailscaleV6), candidate(true, "tun1", "fd7a:115c:a1e0::9")))
    }

    @Test
    fun `nothing is chosen when there is nothing`() {
        assertNull(select())
        assertNull(select(candidate(true, "tun0")))
        assertNull(select(candidate(true, "tun0", "127.0.0.1", "::1")))
    }

    @Test
    fun `several candidates give the same answer in any order`() {
        val first = candidate(true, "tun0", "100.99.0.7", "fd7a:115c:a1e0::7")
        val second = candidate(true, "tun1", tailscaleV4, tailscaleV6)
        val forward = selectTailnetAddress(listOf(first, second))
        val backward = selectTailnetAddress(listOf(second, first))
        assertEquals(forward, backward)
        assertEquals(tailscaleV4, forward?.hostAddress)
    }

    @Test
    fun `the range edges are 100 64 0 0 to 100 127 255 255`() {
        assertFalse(TailscaleRanges.contains(address("100.63.255.255")))
        assertTrue(TailscaleRanges.contains(address("100.64.0.0")))
        assertTrue(TailscaleRanges.contains(address("100.127.255.255")))
        assertFalse(TailscaleRanges.contains(address("100.128.0.0")))
        assertFalse(TailscaleRanges.contains(address("101.64.0.1")))
    }

    @Test
    fun `the IPv6 range is fd7a 115c a1e0 slash 48 and loopback is never in it`() {
        assertTrue(TailscaleRanges.contains(address("fd7a:115c:a1e0:ffff::9")))
        assertFalse(TailscaleRanges.contains(address("fd7a:115c:a1e1::1")))
        assertFalse(TailscaleRanges.contains(address("fd7b:115c:a1e0::1")))
        assertFalse(TailscaleRanges.contains(address("127.0.0.1")))
        assertFalse(TailscaleRanges.contains(address("::1")))
    }

    @Test
    fun `tunnel device names are tun followed by digits`() {
        assertTrue(looksLikeTunnelInterface("tun0"))
        assertTrue(looksLikeTunnelInterface("tun12"))
        assertFalse(looksLikeTunnelInterface("tun"))
        assertFalse(looksLikeTunnelInterface("tunnel0"))
        assertFalse(looksLikeTunnelInterface("rmnet_data4"))
        assertFalse(looksLikeTunnelInterface("wlan0"))
        assertFalse(looksLikeTunnelInterface("atun0"))
    }

    @Test
    fun `the agent url brackets IPv6 and drops any zone`() {
        assertEquals("http://100.73.42.82:47831/mcp", tailnetUrl(address("100.73.42.82"), 47831, "/mcp"))
        assertEquals("http://[fd7a:115c:a1e0:0:0:0:0:1]:47831/mcp", tailnetUrl(address("fd7a:115c:a1e0::1"), 47831, "/mcp"))
    }
}
