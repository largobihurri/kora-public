# provider sample

`eus.bihurri.kora.sample.provider`: a fake music library with cover art and a player (`media.library`, `media.artwork`,
`media.playback`) that exercises the Kora transports on a device. Never published. Install it next to
`samples/consumer` for the Binder peer test (see `kora-android/README.md`), or use it alone for the tailnet test below.

**Artwork.** Every track (`t-<n>`) and playlist carries an `artworkId` (`art-t-<n>`, `art-pl-1`, `art-pl-2`; opaque to
consumers). `get_artwork` draws a flat-colour "record" PNG at `min(maxEdgePx, 640)` pixels, never upscaled, and steps
the size down by a quarter while the PNG exceeds `MediaArtwork.MAX_ARTWORK_BYTES` (`SampleArtwork`; the handler,
`provideSampleArtwork`, is JVM-tested through a node with a fake renderer; `ArtworkRenderer` draws on device). Any other id answers `invalid_payload`. The adb shell and every tailnet pairing are granted
`media.artwork.read` (sample-only, see below).

## Tailnet test

Checks `kora-tailnet` end to end: an agent on another tailnet machine calls this app over MCP HTTP, with no USB cable.
The "Agent access" section of the app's screen drives it.

| Choice | Value |
|---|---|
| Port | **8765**, fixed (`TailnetSample.PORT`): the URL is `http://<phone tailscale address>:8765/mcp` |
| Auto-off | **5 minutes** (`TailnetSample.IDLE_TIMEOUT`). The check box "Auto-off after 1 minute (test)" selects **1 minute** instead; it is remembered, switches the listener off and reopens the screen (pairings are kept) |
| Notification | Channel "Agent access", icon `ic_stat_agent`, a "Stop" action, tap opens the app |
| Notification permission | Asked at runtime on Android 13+ when the switch is turned on. If refused the listener still runs, with no visible notification |

### Install and switch on

```sh
flock /tmp/sonora-gradle.lock ./gradlew :samples:provider:assembleDebug
adb -s <serial> install -r samples/provider/build/outputs/apk/debug/provider-debug.apk
adb -s <serial> shell am start -n eus.bihurri.kora.sample.provider/.MainActivity
```

1. Tailscale must be connected on the phone. Tap the **Agent access** switch and allow notifications.
2. The status line shows **Off**, **Waiting for tailnet** (Tailscale not connected yet; it starts by itself when it
   is), **Listening: http://100.x.y.z:8765/mcp** or **Error: ...** (for instance the port is in use).

### Pair an agent

Tap **Pair agent**, type a name (for instance `Claude on my-laptop`) and **Pair**. The token appears **once** in a
selectable text view: select it, copy it, put it in the agent. It is not written to logs or files (only its hash is
stored by the library), it is never shown again, and it disappears with **Hide token** or when you leave the screen
(the screen stays on while it is shown). To get another one, pair again and revoke the old pairing. Each pairing is
listed with a **Revoke** button; the last refused requests (reason, address, time) are listed below.

**Grants are sample-only.** The sample auto-grants every pairing all the scopes it provides
(`media.library.read`, `media.artwork.read`, `media.playback.read`, `media.playback.control`) for as long as the pairing exists
(`SampleExternalAccess.TAILNET_AGENT_SCOPES`; it goes through the same `ExternalAccess.grantedScopes` the adb shell
grant uses). A real app must not: a new pairing can do nothing until the user grants it scopes one by one (see
`kora-tailnet/README.md`, "Grants").

### From another tailnet machine (`my-laptop`)

```sh
URL=http://<phone tailscale address>:8765/mcp      # as shown in the status line
TOKEN=<the token from the screen>
HDR=(-H 'Content-Type: application/json' -H 'Accept: application/json')
```

**No token** and **wrong token** (both `401`, with `WWW-Authenticate: Bearer`; the screen lists them as
`MISSING_TOKEN` and `INVALID_TOKEN`):

```sh
curl -si "${HDR[@]}" -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' "$URL" | head -5
curl -si "${HDR[@]}" -H 'Authorization: Bearer not-a-real-token' -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' "$URL" | head -5
```

**Valid token.** This is the handshake revision `2025-11-25`: the state lives in the pairing's session, so run the
three steps in order (the token is sent as `Authorization: Bearer`; `MCP-Protocol-Version` is the negotiated one and
may be left out of `initialize`):

```sh
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'MCP-Protocol-Version: 2025-11-25')

# initialize -> 200, result.protocolVersion "2025-11-25"
curl -s "${HDR[@]}" "${AUTH[@]}" "$URL" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
# notification -> 202, empty body
curl -si "${HDR[@]}" "${AUTH[@]}" "$URL" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' | head -1
# tools/list -> the tools of media.library, media.artwork and media.playback
curl -s "${HDR[@]}" "${AUTH[@]}" "$URL" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
# tools/call -> the two sample playlists ("Morning run", "Late night")
curl -s "${HDR[@]}" "${AUTH[@]}" "$URL" -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"media_library__list_playlists","arguments":{}}}'
```

Other checks while paired: `media_playback__get_state` and `media_playback__toggle` (arguments `{}`) answer too, and
`media_artwork__get_artwork` with `{"artworkId":"art-pl-1","maxEdgePx":128}` (an id from the listing) answers an
`image` block; an unknown id answers `invalid_payload`. After
**Revoke**, the same token gives `401` from the next request. The log line of every request (`adb logcat -s
KoraTailnet`) shows method, path, status and pairing id, never a token or a body.

Claude Code can use it the same way: `claude mcp add --transport http provider "$URL" --header "Authorization: Bearer $TOKEN"`.

### Carrier address unreachable

The phone may have a second `100.64.0.0/10` address on its mobile interface (carrier-grade NAT) next to Tailscale's.
Only the Tailscale one may answer:

```sh
adb -s <serial> shell ip -4 addr show        # Tailscale: tun0; carrier: e.g. rmnet_data4, also 100.x
curl -sS --max-time 5 -o /dev/null -w '%{http_code}\n' "http://<carrier address>:8765/mcp"   # fails (timeout/refused), never 401
adb -s <serial> shell 'ss -ltn | grep 8765'  # if ss exists: only the tun0 address is bound, never 0.0.0.0
```

### Auto-off

Tick **Auto-off after 1 minute (test)** (the listener goes off; switch it on again), then leave it idle: the status
changes to **Off** about one minute after the switch was turned on or the last request **with a valid token** (refused
requests do not count), the notification disappears and `curl` no longer connects. Repeat with a valid request every
~40 s to see that it keeps the session alive. With the box clear the period is 5 minutes.

### Also worth a look
- Tailscale off (or switched off and on) while the switch is on: **Waiting for tailnet**, then **Listening** again by
  itself when the address returns, still within the idle period.
- The notification's **Stop** action and the switch both end the session.
- The state does not survive a process death: after a restart the switch is off; pairings are kept.
