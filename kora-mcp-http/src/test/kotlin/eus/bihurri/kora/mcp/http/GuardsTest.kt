package eus.bihurri.kora.mcp.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress

class GuardsTest {
    // ---- PerAddressLimiter ----------------------------------------------------------------------------------------

    @Test
    fun `the limiter lets an address hold its cap and refuses the next one`() {
        val limiter = PerAddressLimiter(2)

        assertTrue(limiter.tryAcquire("100.1.1.1"))
        assertTrue(limiter.tryAcquire("100.1.1.1"))
        assertFalse(limiter.tryAcquire("100.1.1.1"))
        assertTrue(limiter.tryAcquire("100.1.1.2"), "another address has its own slots")

        limiter.release("100.1.1.1")
        assertTrue(limiter.tryAcquire("100.1.1.1"))
        assertFalse(limiter.tryAcquire("100.1.1.1"))
    }

    @Test
    fun `the limiter forgets addresses that hold nothing and ignores extra releases`() {
        val limiter = PerAddressLimiter(1)
        limiter.tryAcquire("a")
        limiter.tryAcquire("b")
        assertEquals(2, limiter.tracked())

        limiter.release("a")
        limiter.release("b")
        limiter.release("never-seen")

        assertEquals(0, limiter.tracked())
        assertThrows<IllegalArgumentException> { PerAddressLimiter(0) }
    }

    @Test
    fun `the limiter is exact under contention`() {
        val limiter = PerAddressLimiter(3)
        val granted = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(16) {
            Thread { repeat(100) { if (limiter.tryAcquire("x")) granted.incrementAndGet() } }
        }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(3, granted.get())
    }

    // ---- DenialThrottle -------------------------------------------------------------------------------------------

    @Test
    fun `the throttle delivers one denial per address and reason per window and counts the rest`() {
        var now = 0L
        val throttle = DenialThrottle(10_000) { now }

        assertEquals(0, throttle.admit("a", DenialReason.INVALID_TOKEN))
        repeat(4) { assertNull(throttle.admit("a", DenialReason.INVALID_TOKEN)) }
        assertEquals(0, throttle.admit("a", DenialReason.MISSING_TOKEN), "another reason")
        assertEquals(0, throttle.admit("b", DenialReason.INVALID_TOKEN), "another address")

        now = 9_999
        assertNull(throttle.admit("a", DenialReason.INVALID_TOKEN))
        now = 10_000
        assertEquals(5, throttle.admit("a", DenialReason.INVALID_TOKEN))
        assertNull(throttle.admit("a", DenialReason.INVALID_TOKEN))
        now = 20_000
        assertEquals(1, throttle.admit("a", DenialReason.INVALID_TOKEN))
        now = 30_000
        assertEquals(0, throttle.admit("a", DenialReason.INVALID_TOKEN))
    }

    @Test
    fun `a zero window delivers everything`() {
        val throttle = DenialThrottle(0)

        repeat(5) { assertEquals(0, throttle.admit("a", DenialReason.INVALID_TOKEN)) }
    }

    @Test
    fun `the throttle stays bounded when many addresses attack`() {
        val throttle = DenialThrottle(1_000_000_000) { 0 }

        val delivered = (1..5_000).count { throttle.admit("addr-$it", DenialReason.INVALID_TOKEN) != null }

        assertTrue(delivered <= 1_024, "delivered $delivered")
    }

    // ---- HostPolicy -----------------------------------------------------------------------------------------------

    private fun policy(address: String = "127.0.0.1", port: Int = 8080, vararg hosts: String) =
        HostPolicy(InetAddress.getByName(address), port, hosts.toSet())

    @Test
    fun `the bound address is accepted with its port or without`() {
        val policy = policy()

        for (host in listOf("127.0.0.1", "127.0.0.1:8080")) assertTrue(policy.accepts(host), host)
        for (host in listOf("127.0.0.1:9", "127.0.0.1:", "127.0.0.1:99999", "127.0.0.1:0", "127.0.0.1:80x", "127.0.0.2", "localhost",
            "127.0.0.1.evil.example", "0x7f.0.0.1", "2130706433", "", ":8080", " ", "evil.example", "127.0.0.1 ")) {
            assertFalse(policy.accepts(host), "'$host'")
        }
    }

    @Test
    fun `allowed names are matched case-insensitively, with the bound port or none`() {
        val policy = policy(hosts = arrayOf("Phone.Tail.TS.net"))

        for (host in listOf("phone.tail.ts.net", "PHONE.tail.ts.net:8080", "phone.tail.ts.net.")) assertTrue(policy.accepts(host), host)
        for (host in listOf("phone.tail.ts.net:80", "other.tail.ts.net", "xphone.tail.ts.net", "phone.tail.ts.net.evil.example")) {
            assertFalse(policy.accepts(host), host)
        }
    }

    @Test
    fun `dotted quads with an octet over 255 are refused as text, never looked up as names`() {
        val policy = policy()

        for (host in listOf("256.0.0.1", "127.0.0.256", "999.999.999.999", "256.0.0.1:8080")) assertFalse(policy.accepts(host), host)
    }

    @Test
    fun `IPv6 hosts are bracketed and any spelling of the bound address matches`() {
        val policy = policy("::1", 9000)

        for (host in listOf("[::1]", "[::1]:9000", "[0:0:0:0:0:0:0:1]:9000", "[0000::1]")) assertTrue(policy.accepts(host), host)
        for (host in listOf("[::2]", "[::1", "::1", "[::1]:1", "[]", "[::1]x", "[not-an-address]")) assertFalse(policy.accepts(host), host)
    }

    // ---- TailnetAddresses -----------------------------------------------------------------------------------------

    @Test
    fun `loopback and Tailscale ranges are allowed, everything else is not`() {
        val allowed = listOf(
            "127.0.0.1", "127.9.9.9", "::1", "100.64.0.0", "100.64.0.1", "100.100.1.2", "100.127.255.255",
            "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12:4843:cd96:6249:1eaa",
        )
        val refused = listOf(
            "100.63.255.255", "100.128.0.0", "101.64.0.1", "10.0.0.1", "192.168.1.5", "172.16.0.1", "169.254.1.1", "8.8.8.8",
            "fd7a:115c:a1e1::1", "fd7a:115d:a1e0::1", "fe80::1", "2001:db8::1", "fc00::1",
        )

        for (a in allowed) assertTrue(TailnetAddresses.isAllowed(InetAddress.getByName(a)), a)
        for (a in refused) assertFalse(TailnetAddresses.isAllowed(InetAddress.getByName(a)), a)
    }
}
