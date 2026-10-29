# kora-tailnet

Lets an app serve its Kora node over MCP to the owner's agents **through Tailscale, with no USB cable**: while a user
switch is on, the app listens on the phone's Tailscale address, behind a foreground service with a notification, one
revocable pairing (token) per agent, and an auto-off after an idle period. It wraps `McpHttpServer` from
[`:kora-mcp-http`](../kora-mcp-http/README.md) (endpoint behaviour, status codes, limits) and adds what only Android
can: finding the Tailscale address, following it, the service, and persistent pairings.

It is communication only: no screens. The app owns the switch, the pairing screen, the grants UI and the auto-off
period. Kora owns the listener, the service and the notification plumbing.

## Wire an app

1. `implementation("eus.bihurri.kora:kora-tailnet:X.Y.Z")` (exact version, see `docs/releasing.md`). It brings
   `kora-mcp-http` with `api`; it does not need `kora-android`, the node is handed in.
2. Install once, after the node, in `Application.onCreate`:

   ```kotlin
   val node = KoraAndroid.install(this, policy = DefaultCallerPolicy(myExternalAccess)) { ... }
   KoraTailnet.install(
       this, node,
       TailnetConfig(
           port = 47831,                      // fixed: the URL an agent is configured with stays valid
           idleTimeout = 30.minutes,          // the app's choice
           notification = TailnetNotification(smallIcon = R.drawable.ic_stat_agent, contentIntent = openSettings),
       ),
   )
   ```
3. The switch: `if (on) KoraTailnet.start() else KoraTailnet.stop()`, and observe `KoraTailnet.status`
   (`StateFlow<TailnetStatus>`): `Off`, `WaitingForTailnet`, `Listening(address, port, url)`, `Error(reason)`.
4. Ask for `POST_NOTIFICATIONS` (Android 13+) before the first `start()`. Without it the service still runs but its
   notification is hidden. Everything else is declared by the library manifest: `INTERNET`, `ACCESS_NETWORK_STATE`,
   `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and the (private) `TailnetService`.

`start()` must run while the app may start a foreground service, in practice from the user's tap on the switch. If
Android refuses, the status becomes `Error` instead of crashing. The state does not survive the process: after a
restart the switch is off, and the app decides whether to show it as off or ask again.

## What it does

### Finding the Tailscale address
Only the address of the **Tailscale VPN interface** is ever used. 100.64.0.0/10 is also the carrier-grade NAT range
(the owner's phone has a carrier address `100.109.122.102` on `rmnet_data4` next to Tailscale's `100.73.42.82` on
`tun0`) and other VPNs use it too (Cloudflare WARP, for one), so an address in that range proves nothing. An interface
qualifies only if both hold (`selectTailnetAddress`, a pure function with its own tests):

1. it is known to be a VPN, and
2. it carries an address in **fd7a:115c:a1e0::/48**, the signature of Tailscale: every node gets one next to its IPv4
   address, on the same interface (Tailscale documents that every device in a tailnet has both; nothing found says
   the Android client can run without the IPv6 one).

The IPv4 address that is chosen (IPv4 is preferred, for a familiar URL) must be on that same qualifying interface;
a 100.x address on any other interface is ignored. With several qualifying addresses the lowest textual one wins, so
the choice does not depend on the enumeration order. How VPN-ness is established (`AndroidTailnetAddressSource`):

- **Primary: `ConnectivityManager`.** A network callback whose request has `TRANSPORT_VPN` and **clears
  `NET_CAPABILITY_NOT_VPN`**: the platform sets that capability by default and documents that it must be cleared to see
  VPN networks. On Android 12+ the request also uses `setIncludeOtherUidNetworks(true)`, because by default a request
  only matches networks that apply to the calling uid, and the app may have been excluded from Tailscale. A network
  counts as a VPN only once its `NetworkCapabilities` were seen to carry `TRANSPORT_VPN`.
- **Fallback: the interface list.** On every update (and at start) `NetworkInterface`s are listed too, and one counts
  as a VPN only if it is up and named `tun<N>` (Tailscale for Android uses `tun0`); it still needs the fd7a address
  to qualify. A carrier interface never counts. It is only evaluated when the callback fires or at start: it covers a
  VPN the callback could not see then, not one that appears later without any callback.
- Reports reach the controller in the order the state changed, also when the system calls from several threads.

### Binding and following the network
The server binds that exact address (never the wildcard, never loopback in production: `McpHttpServer` refuses
anything outside loopback and Tailscale's ranges, and the address chosen here is never loopback). When the address
changes the server is stopped and bound again on the new one; when it disappears the status is `WaitingForTailnet` and
the listener starts by itself when Tailscale comes back. The port is the one in `TailnetConfig`; if it is taken the
status is `Error` (no crash, no other port) and the next address change tries again.

### Foreground service, notification and auto-off
`TailnetService` hosts the session while the switch is on. Each session has its own number and the service instance
remembers the session it serves: if the service is destroyed for any reason while that session is on, the session ends
too (the listener never runs without its notification), and a service of an old session, or a stale stop request, never
touches a newer one. When a session ends (switch off, Stop action, auto-off, failure) the service releases the
foreground state with its notification before it stops, never updates the notification afterwards, and removes its
notification again when destroyed unless a newer session is running. Its notification is built from `TailnetNotification`: the
app's small icon and wording (English defaults, `text = { status -> NotificationText(title, text) }` to localise), an
optional tap action, a low-importance channel (`kora_tailnet`, "Agent access") and a **Stop** action that switches the
listener off. The notification follows the status.

- **Auto-off.** The session ends (status `Off`, service stopped, port freed) after `idleTimeout` without an
  authenticated request, counted from the switch being turned on, so waiting for the tailnet counts as idle too. Each
  request with a valid token restarts the period; refused requests do not. Pick a period longer than the longest tool
  call you expect (a request can take up to 30 s). Idleness is measured with `SystemClock.elapsedRealtimeNanos`, which
  keeps counting in deep sleep (`System.nanoTime` and coroutine delays do not), and is enforced three ways:
  - a coroutine that ends the session at the deadline while the device is awake;
  - an **`AlarmManager` alarm** (`setAndAllowWhileIdle`, `ELAPSED_REALTIME_WAKEUP`) at the deadline, so it fires while
    the device sleeps and in Doze. It needs no exact-alarm permission, which makes it inexact: in Doze the system lets
    an app's allow-while-idle alarms fire at most about once every nine minutes, so a sleeping phone can keep the
    listener up that much past the deadline. An alarm that fires before the deadline (there was activity) re-arms for
    the new one;
  - a check on every event the controller sees: after the deadline the pairing lookup returns nobody (the request
    gets `401` and is not served), and that request, a refused one or an address change ends the session instead of
    reviving it. So a phone that woke up for a packet never answers on an expired session.
- **Foreground service type: `specialUse`**, with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property explaining it
  (Android 14+ requires a type and, for `specialUse`, the subtype). The types that exist describe media, location,
  camera, microphone, phone calls, data transfer, health and similar; none describes "a local endpoint served to the
  user's own agents". `connectedDevice` is the nearest ("interaction with external devices that require a ... network
  connection") but it is meant for hardware peripherals and requires one of a set of device permissions
  (`CHANGE_NETWORK_STATE`, Bluetooth, NFC, ...) that this feature does not otherwise use. `dataSync` ends after 6 hours
  on Android 15; `specialUse` has no such limit and the session is bounded by the idle timeout anyway. The cost:
  `specialUse` needs a justification when publishing on Google Play (not relevant to sideloaded personal apps).

## Pairing flow

```kotlin
val issued = KoraTailnet.pair("Claude on my-desktop")   // background thread: writes a file
show(issued.token)                                      // once: only its hash is stored, it cannot be recovered
// the owner puts it in the agent: Authorization: Bearer <token> at (KoraTailnet.status as Listening).url
KoraTailnet.pairings()                                  // list for the UI
KoraTailnet.revoke(id)                                  // token refused from the next request, session dropped
```

- The pairing store is a JSON file in `noBackupFilesDir` (not backed up, not restored onto another device). It holds
  id, name, creation time and the **SHA-256 hash** of the token, never the token. Writes are atomic (temporary file,
  `fsync`, rename). Decoding is tolerant: unknown keys are ignored, one bad entry is skipped, and a file that is not
  JSON is kept aside as `.corrupt` while the store starts empty (nobody is authenticated by a file that cannot be
  trusted; pair again).
- A revoked id is never accepted again, also after a restart (revoked ids are kept in the file), so a new pairing can
  never inherit the grants, audit trail or session of a revoked one. Revocation takes effect in memory even if the
  write fails; `revoke` then throws `IOException` after the fact, and the app should tell the user and retry.
- **Fail closed.** If writing the file fails during `revoke`, the token is refused in this process, but the file still
  lists the pairing and a restart would bring it back. The store remembers that it is behind and retries the write
  before `pair` and before `start()`; while it still cannot be written, `pair` throws and `start()` leaves the status
  `Error` and does not listen. If the process dies while the disk is still unwritable nothing can be done: tell the user
  when `revoke` throws.
- Names are 1 to 80 printable characters, display only: they never take part in identity.
- `IssuedPairing.toString()` and the token never reach logs from this module.

## Grants: what a pairing may do

Each pairing is its own caller: `Caller(REMOTE_AGENT, "tailnet:<pairing id>")`, built by the server from the verified
token (nothing in the payload picks it). `KoraTailnet.caller(pairing)` returns that value. There is **no new policy
model**: the node's `CallerPolicy` decides, and for `REMOTE_AGENT` the default policy is the same one the adb shell
goes through (`ExternalAccess`, see `kora-android/README.md#external-access`):

```kotlin
class AgentAccess(private val settings: Settings) : ExternalAccess {
    override suspend fun isEnabled() = settings.agentAccessOn            // master switch (all external callers)
    override suspend fun grantedScopes(caller: Caller) = settings.grants[caller].orEmpty()
    override suspend fun onDenied(caller: Caller, scope: Scope) = settings.noteRequest(caller, scope)  // "X asked for ..."
}

// after pairing, the owner approves scopes one by one (there are no wildcards):
settings.grant(KoraTailnet.caller(issued.pairing), setOf(MediaLibrary.ReadScope))
```

A new pairing may do **nothing** until it is granted scopes, and the master switch of `ExternalAccess` applies to
remote agents as it does to the shell: it is the app's decision how it relates to the tailnet switch (for instance
`isEnabled()` returning true while `KoraTailnet.status` is not `Off`). On `revoke`, remove the grants kept for that
caller; leaving them is harmless (ids are not reused) but clutters the settings. Every access, refused ones included,
is audited by the node as for any other caller.

`KoraTailnet.denied` (`SharedFlow<DeniedAttempt>`) reports requests turned away before they reach the node (no or wrong
token, foreign `Origin` or `Host`), at most one per address and reason per 10 s, so the app can show "someone at
100.x tried without a valid token". It never holds what the peer sent.

## Connect an agent from a PC

The agent only needs the URL the app shows (`Listening.url`, `http://<tailnet address>:<port>/mcp`) and the token of its
pairing. With Claude Code:

```sh
claude mcp add --transport http <name> http://<phone tailnet address or MagicDNS name>:<port>/mcp \
  --header "Authorization: Bearer <token>"
```

`claude mcp get <name>` (or `/mcp` inside Claude Code) shows whether it connected; a wrong token is a 401. The flags
match the Claude Code MCP documentation (checked 2026-10-15). The switch must be on in the app, the PC must be in the
same tailnet. `tools/list` shows the same catalogue to every agent; the pairing's grants are enforced when a tool is
called (a tool without a grant answers an `unauthorized` tool error). A MagicDNS name works only if the app put it in
`TailnetConfig.allowedHosts`; otherwise the server answers `421` and the IP address must be used.

## Security model

| Layer | What it does |
|---|---|
| Bind address | The Tailscale VPN interface address only. Nothing on the LAN, the carrier network or loopback can connect. |
| Pairing token | 256-bit random, `Authorization: Bearer`; hashed at rest; compared in constant time; one per agent. |
| Caller identity | One `REMOTE_AGENT` caller per pairing; the node's policy and grants apply; a fresh pairing can do nothing. |
| No TLS | Owner decision: the tailnet (WireGuard) encrypts end to end and only the owner's devices are in it. The token travels inside WireGuard and over no other network, because nothing else can reach the listener. |
| Origin and Host checks, limits | As `kora-mcp-http`: browsers are refused (DNS rebinding), body and connection limits, no work before the caller is known. |
| Auto-off and switch | The listener exists only while the user's switch is on, and ends after the idle period, which counts deep sleep; an expired session is never served. |

Not in scope: access from outside the tailnet, TLS, MCP events over SSE (agents call `tools/list` again when they need
it). Doze: with the screen off and the phone stationary Android may suspend network access even for a foreground
service, so an agent can find the phone slow or unreachable then; excluding the app from battery optimisation is the
user's choice and not requested by this module.

## Tests
JVM tests (`TailnetControllerTest` with real loopback sockets and virtual time, `FilePairingStoreTest`,
`TailnetAddressesTest`) and Robolectric tests (`AndroidTailnetAddressSourceTest`, `TailnetServiceTest`, `TailnetIdleAlarmTest`,
`KoraTailnetTest`, which goes end to end from `pair` to a granted `tools/call`):
`flock /tmp/sonora-gradle.lock ./gradlew :kora-tailnet:testDebugUnitTest`. Loopback is bound only through
test-internal seams (`allowNonTailnetAddress`); production code paths cannot reach it.
