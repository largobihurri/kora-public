package eus.bihurri.kora.tailnet

import android.app.PendingIntent
import eus.bihurri.kora.mcp.McpServerInfo
import eus.bihurri.kora.mcp.http.McpHttpConfig
import kotlin.time.Duration

/**
 * What the app decides about its tailnet listener. Everything else (limits, timeouts, the checks on `Origin` and
 * `Host`) uses the safe defaults of [McpHttpConfig]; production never relaxes the bind-address check.
 *
 * @param port the TCP port to listen on, fixed so that the URL an agent is configured with stays valid across
 *   restarts. Pick one that is unlikely to clash (above 1024); if it is taken the status is
 *   [TailnetStatus.Error], not a different port.
 * @param idleTimeout the listener switches itself off after this long without an authenticated request (counted from
 *   the switch being turned on, and restarted by each authenticated request). Refused requests do not count. Time the
 *   device spends asleep counts; an alarm enforces the deadline, inexactly (see the module README).
 * @param notification what the foreground service shows.
 * @param allowedHosts extra names clients may use in the `Host` header, typically the phone's MagicDNS name
 *   (`phone.tail1234.ts.net`). The tailnet address is always accepted.
 * @param path the URL path of the MCP endpoint.
 * @param serverInfo identity reported to MCP clients; defaults to the package name and version name of the app.
 */
public class TailnetConfig(
    public val port: Int,
    public val idleTimeout: Duration,
    public val notification: TailnetNotification,
    public val allowedHosts: Set<String> = emptySet(),
    public val path: String = McpHttpConfig.DEFAULT_PATH,
    public val serverInfo: McpServerInfo? = null,
) {
    init {
        require(port in 1..65535) { "port must be 1..65535 (a fixed port keeps the agent URL stable): $port" }
        require(idleTimeout.isPositive()) { "idleTimeout must be positive: $idleTimeout" }
        // Validated here so a bad value fails at install, not when the switch is first turned on.
        McpHttpConfig(path = path)
    }
}

/**
 * The text of the notification, in the app's language.
 */
public class NotificationText(public val title: CharSequence, public val text: CharSequence)

/**
 * The foreground-service notification. The app supplies its small icon and wording; Kora supplies a low-importance
 * channel and a "Stop" action that switches the listener off. On Android 13+ the app must hold the `POST_NOTIFICATIONS`
 * permission for the notification to show (the service runs either way; the app asks the user).
 *
 * @param smallIcon a drawable resource id of the app (status bar icon).
 * @param text the wording for each status; the default is in English. Called on any thread whenever the status changes;
 *   keep it quick.
 * @param contentIntent opened when the user taps the notification (usually the app's screen with the switch), or null.
 * @param channelId id of the notification channel Kora creates.
 * @param channelName name of that channel as the user sees it in the system settings.
 * @param stopActionLabel label of the "Stop" action.
 * @param notificationId id of the notification; change it only if it clashes with one of the app's.
 */
public class TailnetNotification(
    public val smallIcon: Int,
    public val text: (TailnetStatus) -> NotificationText = { defaultText(it) },
    public val contentIntent: PendingIntent? = null,
    public val channelId: String = DEFAULT_CHANNEL_ID,
    public val channelName: CharSequence = DEFAULT_CHANNEL_NAME,
    public val stopActionLabel: CharSequence = DEFAULT_STOP_LABEL,
    public val notificationId: Int = DEFAULT_NOTIFICATION_ID,
) {
    public companion object {
        public const val DEFAULT_CHANNEL_ID: String = "kora_tailnet"
        public const val DEFAULT_CHANNEL_NAME: String = "Agent access"
        public const val DEFAULT_STOP_LABEL: String = "Stop"
        public const val DEFAULT_NOTIFICATION_ID: Int = 0x4B6F

        /** The English wording used when the app gives none. */
        public fun defaultText(status: TailnetStatus): NotificationText = NotificationText(
            title = DEFAULT_CHANNEL_NAME,
            text = when (status) {
                TailnetStatus.Off -> "Off"
                TailnetStatus.WaitingForTailnet -> "Waiting for Tailscale"
                is TailnetStatus.Listening -> "Listening on ${status.url}"
                is TailnetStatus.Error -> status.reason
            },
        )
    }
}
