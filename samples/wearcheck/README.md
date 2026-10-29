# wearcheck

One APK (`eus.bihurri.kora.sample.wearcheck`) to verify the `kora-wear` relay on real devices. Install the **same file** on
a phone with Google Play services and on its paired Wear OS watch: the Data Layer only delivers messages between apps with
the same package name and signing key, and one debug APK has one key.

Each device provides stub media data that names its role (playlists `phone-1`, `phone-2` or `watch-1`, `watch-2`; tracks
`<role> track N` every 2 s) and serves the other one. Opening the app on either device checks the relay towards the other:

| Step | What it does | Passes when |
|---|---|---|
| `refresh` | `requestRefresh()`, waits up to 20 s for a `wear:` peer | a `wear:` peer shows up in `node.peers` |
| `list_playlists` | `MediaLibrary.ListPlaylists` on that peer | the call answers; the names are the other device's role |
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
KoraWearCheck step=subscribe ok=true detail=peer=wear:... count=3 titles=[watch track 1, ...]
KoraWearCheck step=cancel ok=true detail=eventsAtCancel=3 eventsAfterWait=3
KoraWearCheck step=summary ok=true passed=4 failed=0
```

On the **other** device's logcat, the answering side logs `KoraWearCheck served=list_playlists caller=wear:...` and, for
the subscription, `served=NowPlayingChanged state=collecting` and, after the cancel, `state=ended`. The latter is the proof
that the cancel reached the serving side.

Both devices must be connected and the app must be installed on both. Run it again for the other direction.

## Notes

- Calls over the link use a 20 s timeout (the default is 10 s).
- External access is off: only the counterpart (a `PEER`) is served, never the adb shell or other apps.
- `CheckActivity` is exported because the launcher and `adb shell am start` start it from another uid.

## Uninstall

```sh
adb -s <phone-serial> uninstall eus.bihurri.kora.sample.wearcheck
adb -s <watch-serial> uninstall eus.bihurri.kora.sample.wearcheck
```
