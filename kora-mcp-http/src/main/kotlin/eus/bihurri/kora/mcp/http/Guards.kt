package eus.bihurri.kora.mcp.http

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Concurrent connections per remote address, so one peer cannot take every worker. Checked by the acceptor before a
 * connection is handed to a worker, and released when the connection ends.
 */
internal class PerAddressLimiter(private val cap: Int) {
    private val counts = HashMap<String, Int>()

    init {
        require(cap > 0) { "cap must be positive: $cap" }
    }

    /** Takes a slot for [address]; false when it already holds [cap]. */
    @Synchronized
    fun tryAcquire(address: String): Boolean {
        val now = counts[address] ?: 0
        if (now >= cap) return false
        counts[address] = now + 1
        return true
    }

    @Synchronized
    fun release(address: String) {
        val now = counts[address] ?: return
        if (now <= 1) counts.remove(address) else counts[address] = now - 1
    }

    /** Addresses that hold at least one slot. */
    @Synchronized
    fun tracked(): Int = counts.size
}

/**
 * Lets at most one denial per remote address and reason through per [windowNanos], and counts the ones it held back
 * so the next delivered one can say how many were swallowed. Keeps the app's [McpHttpListener.onDenied] out of reach
 * of a flood.
 */
internal class DenialThrottle(
    private val windowNanos: Long,
    private val now: () -> Long = System::nanoTime,
) {
    private class Entry(var windowStart: Long, var suppressed: Int)

    private val entries = HashMap<Pair<String, DenialReason>, Entry>()

    /**
     * The number of attempts suppressed since the last delivery, when this one is to be delivered (0 for the first);
     * null when it is to be swallowed.
     */
    @Synchronized
    fun admit(address: String, reason: DenialReason): Int? {
        val time = now()
        if (windowNanos <= 0) return 0
        val key = address to reason
        val entry = entries[key]
        if (entry == null) {
            if (entries.size >= MAX_ENTRIES) entries.values.removeIf { time - it.windowStart >= windowNanos && it.suppressed == 0 }
            if (entries.size >= MAX_ENTRIES) return null
            entries[key] = Entry(time, 0)
            return 0
        }
        if (time - entry.windowStart < windowNanos) {
            entry.suppressed++
            return null
        }
        val suppressed = entry.suppressed
        entry.windowStart = time
        entry.suppressed = 0
        return suppressed
    }

    private companion object {
        /** Bounds memory when many distinct addresses attack at once. */
        const val MAX_ENTRIES = 1_024
    }
}

/**
 * The `Host` header check, a second defence against DNS rebinding next to `Origin`: a request is for this server
 * only when its Host is the address it is bound to (any spelling of that IP, with the bound port or none) or a name
 * in [allowedHosts] (also with the bound port or none).
 */
internal class HostPolicy(
    private val bound: InetAddress,
    private val port: Int,
    allowedHosts: Set<String>,
) {
    private val allowed = allowedHosts.map(::normalize).toSet()

    fun accepts(header: String): Boolean {
        val (host, hostPort) = parse(header) ?: return false
        if (hostPort != null && hostPort != port) return false
        literal(host)?.let { return it == bound }
        return normalize(host) in allowed
    }

    private fun parse(value: String): Pair<String, Int?>? {
        val host: String
        val rest: String
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return null
            host = value.substring(0, end + 1)
            rest = value.substring(end + 1)
        } else {
            val colon = value.indexOf(':')
            host = if (colon < 0) value else value.substring(0, colon)
            rest = if (colon < 0) "" else value.substring(colon)
        }
        if (host.isEmpty() || host == "[]") return null
        if (rest.isEmpty()) return host to null
        if (!rest.startsWith(":")) return null
        val digits = rest.substring(1)
        if (digits.isEmpty() || digits.length > 5 || !digits.all { it in '0'..'9' }) return null
        val parsed = digits.toInt()
        return if (parsed in 1..65535) host to parsed else null
    }

    /** An IP literal (IPv4 dotted quad, or bracketed IPv6) as an address; never resolves a name. */
    private fun literal(host: String): InetAddress? {
        // Only text that is syntactically an address reaches getByName, which would otherwise resolve names.
        if (IPV4.matches(host)) {
            // Built from the octets: getByName would fall back to a name lookup for text such as 256.0.0.1.
            val octets = host.split('.').map { it.toInt() }
            if (octets.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
        }
        // Text with a colon is always parsed as an IPv6 literal by getByName, never looked up.
        val text = host.takeIf { it.startsWith("[") && it.endsWith("]") }
            ?.substring(1, host.length - 1)
            ?.takeIf { IPV6.matches(it) && ':' in it }
            ?: return null
        return try {
            InetAddress.getByName(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalize(host: String) = host.trim().trimEnd('.').lowercase()

    private companion object {
        val IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
        val IPV6 = Regex("[0-9A-Fa-f:.]+")
    }
}

/** Which addresses `start` accepts without being told to. */
internal object TailnetAddresses {
    private val TAILSCALE_ULA_PREFIX = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())

    /**
     * Loopback, 100.64.0.0/10 and fd7a:115c:a1e0::/48. The IPv4 range is Tailscale's, and also the carrier-grade NAT
     * range that mobile carriers use: it says nothing about which interface the address belongs to.
     */
    fun isAllowed(address: InetAddress): Boolean = when {
        address.isLoopbackAddress -> true
        address is Inet4Address -> address.address.let { it[0] == 100.toByte() && (it[1].toInt() and 0xC0) == 0x40 }
        address is Inet6Address -> address.address.copyOf(TAILSCALE_ULA_PREFIX.size).contentEquals(TAILSCALE_ULA_PREFIX)
        else -> false
    }
}
