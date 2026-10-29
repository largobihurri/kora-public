package eus.bihurri.kora.tailnet

/** What the tailnet listener is doing now, as shown by the app's switch and by the notification. */
public sealed interface TailnetStatus {
    /** The switch is off: nothing listens and there is no notification. Also the state after an auto-off. */
    public data object Off : TailnetStatus

    /**
     * The switch is on but there is no Tailscale address to listen on (Tailscale is off, signed out or still
     * connecting). The listener starts by itself when the address appears, and the idle timer keeps running.
     */
    public data object WaitingForTailnet : TailnetStatus

    /** Listening on the Tailscale address only. [url] is what an agent is configured with. */
    public data class Listening(
        /** The Tailscale address in textual form (IPv4, or IPv6 without brackets). */
        public val address: String,
        public val port: Int,
        /** `http://<address>:<port><path>`, with IPv6 addresses in brackets. */
        public val url: String,
    ) : TailnetStatus

    /**
     * The listener could not run. Typical cause: the port is in use. When the switch stays on, the next change of the
     * Tailscale address tries again; a failure that ends the session (the network callback could not be registered,
     * the foreground service was not allowed to start) leaves the switch off.
     */
    public data class Error(public val reason: String) : TailnetStatus
}
