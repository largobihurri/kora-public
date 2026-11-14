# consumer sample

`eus.bihurri.kora.sample.consumer`: a node with no providers that uses what its peers offer. Never published. Install
it next to `samples/provider` (same debug key, so each lists the other as a `PEER`); it logs the peers, the provider's
playlists and its now-playing events under the tag `KoraSample` and mirrors the lines on screen.

**Gate.** `ConsumerExternalAccess` opens the consumer's gate to the adb shell for the read tools it relays to the
provider (`media.library.read`, `media.artwork.read`, `media.playback.read`); `media.playback.control` and every other
caller are refused and logged (`accessDenied ...`). Sample-only: a real app keeps external access off by default.

## Artwork transport check

**Binder, typed API.** Open the app and tap **Fetch artwork**: it calls `MediaArtwork.GetArtwork` on the provider peer
for the artwork of its first listed track (`art-t-1` when none is listed) at 256 px and logs one line:

```sh
adb -s <serial> shell am start -n eus.bihurri.kora.sample.consumer/.MainActivity
adb -s <serial> logcat -s KoraSample    # artwork id=art-t-1 peer=eus.bihurri.kora.sample.provider mime=image/png px=256x256 bytes=... ms=...
```

**Binder, relayed from MCP.** `kora-bridge` talks to the consumer, whose gate authorizes the shell and relays the call
over Binder to the provider (which serves the consumer as a `PEER`, so the provider's own shell grants do not apply):

```sh
./gradlew :kora-bridge:installDist
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"sh","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"media_artwork__get_artwork","arguments":{"artworkId":"art-t-1","maxEdgePx":256}}}' \
  | kora-bridge/build/install/kora-bridge/bin/kora-bridge --serial <serial> --package eus.bihurri.kora.sample.consumer
```

The answer to `id` 2 carries an `image` block (`image/png`) and matching `structuredContent`; `media_playback__toggle`
answers `unauthorized` (no control grant).
