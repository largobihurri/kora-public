# wearcheck

One APK (`eus.bihurri.kora.sample.wearcheck`) to verify the `kora-wear` relay on real devices. Install the **same file** on
a phone with Google Play services and on its paired Wear OS watch: the Data Layer only delivers messages between apps with
the same package name and signing key, and one debug APK has one key.

Each device provides stub media data that names its role (playlists `phone-1`, `phone-2` or `watch-1`, `watch-2`; tracks
`<role> track N` every 2 s), with generated cover art (`media.artwork`: ids `art-<playlist id>` and `art-<role>-t<n>`,
flat PNGs under `MediaArtwork.MAX_ARTWORK_BYTES`, unknown ids answer `invalid_payload`), and serves the other one. Opening the app on either device checks the relay towards the other:

| Step | What it does | Passes when |
|---|---|---|
| `refresh` | `requestRefresh()`, waits up to 20 s for a `wear:` peer | a `wear:` peer shows up in `node.peers` |
| `list_playlists` | `MediaLibrary.ListPlaylists` on that peer | the call answers; the names are the other device's role |
| `artwork` | `MediaArtwork.GetArtwork` at 384 px for the `artworkId` of the peer's first playlist | a known image type, at most 384 px; reports mime, px, bytes and round-trip ms |
| `artwork_unknown` | the same for `art-unknown` | the peer answers `invalid_payload` |
| `subscribe` | collects `NowPlayingChanged` from it for 7 s | at least 2 events (`<other role> track N`) |
| `cancel` | cancels, waits 3 s | the local counter no longer moves (always true once the collector is cancelled; the real check is `state=ended` in the other device's log, see below) |
| `summary` | | all steps passed |

## Install

```sh
./gradlew :samples:wearcheck:assembleDebug
APK=samples/wearcheck/build/outputs/apk/debug/wearcheck-debug.apk
adb -s <phone-serial> install -r $APK
adb -s <watch-serial> install -r $APK      # watch over adb Wi-Fi/Bluetooth debugging
```

The APK has no watch-only hardware requirement, so it installs on both.

## Run

Start it on one device (or tap the "Kora wear check" icon; the watch shows the same lines on screen):

```sh
adb -s <serial> shell am start -n eus.bihurri.kora.sample.wearcheck/.CheckActivity
adb -s <serial> logcat -s KoraWearCheck KoraWear
```

Look for (one line per step):

```
KoraWearCheck step=refresh ok=true detail=wearPeers=[wear:eus.bihurri.kora.sample.wearcheck]
KoraWearCheck step=list_playlists ok=true detail=peer=wear:... total=2 names=[watch-1, watch-2]
KoraWearCheck step=artwork ok=true detail=peer=wear:... id=art-phone-1 mime=image/png px=384x384 bytes=... ms=...
KoraWearCheck step=artwork_unknown ok=true detail=peer=wear:... id=art-unknown error=invalid_payload ms=...
KoraWearCheck step=subscribe ok=true detail=peer=wear:... count=3 titles=[watch track 1, ...]
KoraWearCheck step=cancel ok=true detail=eventsAtCancel=3 eventsAfterWait=3
KoraWearCheck step=summary ok=true passed=6 failed=0
```

On the **other** device's logcat, the answering side logs `KoraWearCheck served=list_playlists caller=wear:...` and, for
the subscription, `served=NowPlayingChanged state=collecting` and, after the cancel, `state=ended`. The latter is the proof
that the cancel reached the serving side.

Both devices must be connected and the app must be installed on both. Run it again for the other direction.

## Artwork transport check

Install the same APK on both devices, then start the check **on the watch**
(`adb -s <watch-serial> shell am start -n eus.bihurri.kora.sample.wearcheck/.CheckActivity`): the `artwork` step
resolves the phone's `art-phone-1` over kora-wear at 384 px and `artwork_unknown` expects `invalid_payload`; both lines
also appear on the watch screen. The phone's logcat shows `served=get_artwork caller=wear:...`. Both sides serve
artwork, so the steps pass in the other direction too.

## Notes

- Calls over the link use a 20 s timeout (the default is 10 s).
- External access is off: only the counterpart (a `PEER`) is served, never the adb shell or other apps.
- `CheckActivity` is exported because the launcher and `adb shell am start` start it from another uid.

## Volume check

Both devices also provide `media.volume` from an in-memory stub (`WearVolume`: 15 steps, starts at 7, the same rules
as `samples/provider`), and the check adds four steps towards the other device. Every write carries a `WriteOrder`
with a random writer per run and an increasing sequence, as the Kardio crown would send them. The interesting
direction is **watch -> phone**: start the check on the watch
(`adb -s <watch-serial> shell am start -n eus.bihurri.kora.sample.wearcheck/.CheckActivity`) right after installing or
force-stopping the app on both devices, so the first call is cold.

| Step | What it does | Passes when |
|---|---|---|
| `volume_cold` | right after `refresh`: `awaitProvider(MediaVolume, 20 s, Target.Peer(phone))`, then the first `get_volume` on the peer | the peer offered it and the first call answered (no `provider_unavailable`); reports `peerWaitMs`, `getMs` |
| `volume_latency` | 30 sequential `set_volume` round trips (levels 0..14) | the final `get_volume` is the last level written; reports p50, p95 and max ms |
| `volume_burst` | 20 concurrent `set_volume` writes with increasing sequences; only the greatest one writes level 15 | the final `get_volume` is 15 (last write wins across the relay) |
| `volume_event` | subscribes to `volume_changed`, waits for the current state, sets a different level | the change arrives within 10 s; reports `setMs` and `eventMs` (from before the call to the event) |

```
KoraWearCheck step=volume_cold ok=true detail=peer=wear:... peerOffers=true peerWaitMs=... firstGet=ok getMs=... state=7/15
KoraWearCheck step=volume_latency ok=true detail=peer=wear:... writes=30 p50=...ms p95=...ms max=...ms final=14 expected=14 writer=...
KoraWearCheck step=volume_burst ok=true detail=peer=wear:... writes=20 elapsedMs=... final=15 expected=15 answeredExpected=...
KoraWearCheck step=volume_event ok=true detail=peer=wear:... from=15 to=14 setMs=... eventMs=...
```

The `summary` line counts these four steps too. The cold wait targets the peer because the app also provides
`media.volume` itself (an untargeted wait would answer at once from the local provider); `peerWaitMs` is the wait
Kardio's watch spends in the same `awaitProvider` call. Copy the `volume_latency` and `volume_event` numbers into `docs/app-handoff.md` ("Volume consumer
guidance"). The pure parts (percentiles, levels, pass rules, the stub's rule) are JVM-tested in `VolumeChecksTest`:
`./gradlew :samples:wearcheck:testDebugUnitTest`.

## Uninstall

```sh
adb -s <phone-serial> uninstall eus.bihurri.kora.sample.wearcheck
adb -s <watch-serial> uninstall eus.bihurri.kora.sample.wearcheck
```
