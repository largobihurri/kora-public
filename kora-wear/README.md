# kora-wear

Phone <-> watch relay for Kora over the Wear OS Data Layer: the same app on both devices (same `applicationId` and signing key)
sees the other device's Kora peers as `wear:<node id>`, and can call them and subscribe to their events. Logic (frames, chunking,
relay classes, in-memory link for tests) is in `:kora-wear-protocol` (JVM); this module is the Android glue
(play-services-wearable 20.0.1). Watch -> Data Layer -> phone app -> Binder -> Sonora is `wear:eus.bihurri.sonora`.

## Install (same code on phone and watch, in `Application.onCreate`)

```kotlin
val wear = KoraWear.install(this)                                              // sends nothing yet
val node = KoraAndroid.install(this, peerRoutes = listOf(wear.route)) { provide(...) { ... } }
wear.serve(node)                                                               // the other device may use this node
wear.requestRefresh()                                                          // when the UI needs the other device
```

Names: capability `kora_node` (`res/values/wear.xml`; an app with its own `android_wear_capabilities` must list it) and message
path `/kora/v1/frame`; the library manifest declares `KoraWearListenerService` for `MESSAGE_RECEIVED` on `/kora/v1`.

## What wakes what, and what it costs
Only a message wakes the other device's app (through that service); starting an app sends nothing. The peer list is fetched by
`requestRefresh()`, then changes are pushed, a lost link empties it, a reconnection or restart asks again. No polling, keep-alive,
alarm or wakelock; one frame per call, answer, event or cancel, no batching delay. The counterpart is looked up lazily and cached;
capability changes are heard by a listener registered on first use (no manifest filter). A subscription lives only while a collector
exists; cancelling, a link loss or a counterpart restart releases the serving side and the peer behind it. Frames over ~65 KB are
chunked (max 256 KiB, up to 5 messages).

## Security
Wear OS only delivers messages between apps with the same package name and signing certificate (developer.android.com/training/
wearables/data/overview, "Security"). Frames are still validated (version, size, sender, ids) and identity never comes from a
frame: the counterpart is served as `Caller(PEER, "wear:<package>")` through `KoraNode.dispatch`/`dispatchSubscribe`, so the
node's policy and audit apply and local peers see this app as the caller. `wear:` ids are never relayed back; 32 subscriptions max.

## Limits and how to verify
Best effort, no retry: a lost frame shows as a timeout (10 s; raise the node's `callTimeout` for calls over the link). One
counterpart (the nearby reachable device; other paired watches are ignored). If the system kills the serving process, its
subscriptions end silently until the next call or refresh. Only built-in capabilities are forwarded to peers.
Verify on a paired phone + watch: install both builds, open the watch app, `requestRefresh()`, check `node.peers` lists
`wear:` ids, call `ListPlaylists` on `wear:eus.bihurri.sonora`, collect `NowPlayingChanged`, `adb logcat -s KoraWear` on both.
