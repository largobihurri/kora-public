# App handoff

This Kora session works on the Kora library only. Each app integrates Kora in its own session. Before that scope was
fixed (2026-10-15), work was started inside the app repositories. All of it lives in **separate git worktrees and
branches**: nothing was merged and no existing checkout was edited. Each app session decides what to keep, merge or
discard.

Every app integrates through `kora-android/README.md` (`KoraAndroid.install(app) { provide(...) }`), the external
access model in `kora-android/README.md`, `kora-mcp/README.md`, `kora-bridge/README.md` and, once T9 lands,
`kora-wear/README.md`.

## Rename to Kora (2026-10-15)

The library was renamed from CORA to **Kora**. Nothing had been released under the old names, so wire identifiers
changed too. Every app must move together: an app on the old names does not see apps on the new names (different
intent action, authority and Wear capability), and the other way round.

| Old | New |
|---|---|
| Packages `eus.bihurri.cora.*` (samples `eus.bihurri.cora.sample.*`) | `eus.bihurri.kora.*` (`eus.bihurri.kora.sample.*`) |
| Gradle modules `:cora-contract`, `:cora-core`, `:cora-mcp`, `:cora-android`, `:cora-wear-protocol`, `:cora-wear`, `:cora-bridge` | `:kora-contract`, `:kora-core`, `:kora-mcp`, `:kora-android`, `:kora-wear-protocol`, `:kora-wear`, `:kora-bridge` |
| `includeBuild("../CORA")` | `includeBuild("../KORA")` (the old folder name stays as a symlink) |
| Maven-style coordinates `eus.bihurri.cora:cora-*` | `eus.bihurri.kora:kora-*` |
| `CoraAndroid`, `CoraWear`, `CoraNode`, `CoraNodeService`, `CoraGateProvider` | `KoraAndroid`, `KoraWear`, `KoraNode`, `KoraNodeService`, `KoraGateProvider` |
| `CoraRequest`, `CoraResponse`, `CoraEvent`, `CoraError`, `CoraException`, `CoraCodec`, `CoraGate` | `KoraRequest`, `KoraResponse`, `KoraEvent`, `KoraError`, `KoraException`, `KoraCodec`, `KoraGate` |
| `NodeDescriptor.coraVersion` | `NodeDescriptor.koraVersion` |
| Intent action `eus.bihurri.cora.action.NODE` | `eus.bihurri.kora.action.NODE` |
| ContentProvider authority `<applicationId>.cora` | `<applicationId>.kora` (update `<queries>` entries too) |
| Gate methods `cora.describe`, `cora.call`, `cora.call.json`, `cora.mcp` | `kora.describe`, `kora.call`, `kora.call.json`, `kora.mcp` |
| Wear capability `cora_node`, message path `/cora/v1` | `kora_node`, `/kora/v1` |
| Convention plugin ids `cora.*`, Gradle property `-Pcora.recordFixtures` | `kora.*`, `-Pkora.recordFixtures` |
| `cora-bridge` executable | `kora-bridge` |

Checklist for an app session: update `includeBuild` and the dependency coordinates, replace the imports, the
`<queries>` authorities and any `adb ... content call` scripts, rebuild, and reinstall phone and watch together. The
foundation work is in `main`; Kora's own branches and worktrees were renamed too (`feat/kora-location`, worktree
`KORA-location`). The `feat/cora-*` branches and `*-cora-*` worktrees listed below live in the app repositories and are renamed by their
sessions.

## Common signing key (all apps)

Key: `~/.bihurri/signing/` (see `docs/signing.md`), certificate SHA-256 `47c3297e…60d2`. Kora peers trust each other
only when they share it. Switching an installed app needs one uninstall (data loss): back up first, phone and watch
together.

## Agent access over the tailnet

Lets the owner's agents (Claude Code on a PC, any MCP client in the tailnet) use an app's Kora node over MCP with no
USB cable. Module `kora-tailnet`; details, security model and API in `kora-tailnet/README.md`, the HTTP endpoint in
`kora-mcp-http/README.md`. Needs a Kora release that contains both modules (the owner picks the number).

Owner decisions (2026-10-15), the same for every app:

1. **No TLS in the app.** Tailscale encrypts end to end; the listener binds the Tailscale interface address only.
2. **A switch per app, with auto-off.** The listener exists only while the user's "agent access" switch is on, behind a
   foreground service and its notification, and switches itself off after an idle period the app chooses.
3. **One pairing per agent.** Each pairing has a name, a random token shown once, its own grants and its own
   revocation.

What the app does:

- Depend on `eus.bihurri.kora:kora-tailnet:X.Y.Z` (exact version). It brings `kora-mcp-http`; the node is handed in.
- Call `KoraTailnet.install(app, node, TailnetConfig(...))` in `Application.onCreate`, after `KoraAndroid.install`.
- Pick a **fixed port, distinct per app** (an agent URL stays valid across restarts, and two apps can be on at once),
  the idle period and the notification (small icon, wording, tap action). A port already taken gives status `Error`.
- Provide the **switch** (`KoraTailnet.start()` from the user's tap, `stop()`, observe `KoraTailnet.status`) and ask
  for `POST_NOTIFICATIONS` (Android 13+) before the first start.
- Provide the **pairing screen**: `KoraTailnet.pair(name)` (off the main thread), show `issued.token` once, never
  store or log it; the agent needs it plus the URL from `Listening.url`.
- Provide the **revoke list**: `KoraTailnet.pairings()` and `revoke(id)`.
- Provide the **grants**: each pairing is `Caller(REMOTE_AGENT, "tailnet:<id>")` (`KoraTailnet.caller(pairing)`). The
  node's policy decides through the app's `ExternalAccess.grantedScopes(caller)`; a new pairing can do nothing until
  the owner grants scopes one by one. Decide how `ExternalAccess.isEnabled()` relates to the tailnet switch.
- Optionally show `KoraTailnet.denied` ("someone tried without a valid token") and pass `allowedHosts` if agents use the
  phone's MagicDNS name.

What stays in Kora: finding the Tailscale address (never the carrier's 100.x one), binding and following network
changes, the HTTP endpoint and its checks, token hashing and the pairing file, the service, the notification plumbing
and the auto-off. The apps own every screen and every grant decision.

Proposed default ports (a proposal, the owner can change it; any fixed port above 1024 that no other Kora app uses
works):

| App | Port |
|---|---|
| Sonora | 47831 |
| Kardio | 47832 |
| KOKO | 47833 |

How an agent connects: `kora-tailnet/README.md#connect-an-agent-from-a-pc`.

## Kardio

| Worktree | Branch | State |
|---|---|---|
| `CARDIO-common-signing` | `feat/common-signing` (from `feat/t2f-absolute-elevation`) | Done: `240f77f`; every APK carries the common key; fallback to the debug key with a warning; build green. |
| `CARDIO-cora-music` | `feat/cora-music` (from `feat/common-signing`) | **Partial, stopped mid-task**: `3d82bff`, `d08c263`, `bd1ca05`, `4fc6410` (pure music domain with `MusicLink`, soundtrack recorder, placement on the track, Room schema v3) plus 18 uncommitted files (watch wiring in progress). Unverified beyond the writer's own tests. Its `odd/tasks/cora-music.md` describes the design. |

Decisions for the Kardio session (owner's product intent, 2026-10-15):
- During a hike Sonora plays on the phone (or on the watch); the Kardio watch shows a music control page and records
  where each song played (time from the event, position from the recorded GPS trace, no extra fixes). Controls go to
  the source that last reported playing. Songs missed while the link was down are recovered later through
  `media.history` (Kora T2b + Sonora T7h).
- Location handoff (Kora T10): phone GPS when the hike starts connected; watch GPS without the phone; a watch fix
  about every 5 minutes while connected; immediate switch on disconnection or when the phone is left behind; no
  stop and no jump; if the phone returns mid-hike, an interactive watch notification asks to switch. This requires
  taking location out of Health Services (its exercise GPS cannot be toggled mid-exercise), which changes an accepted
  Kardio decision: decide it in the Kardio session. Kora provides only the transport (`location` 1.1); the engine is
  Kardio's (see below).

### Location handoff (for the Kardio session)

Kora provides `location` 1.1 (batched fixes, `configure_stream`) over Binder and the wear bridge. Everything else is
Kardio's:
- **Engine head start**: Kora worktree `KORA-location`, branch `feat/kora-location`: `b42043c` (models, ports,
  geodesy, bias/blend helpers, tested) and an unfinished state machine with tests (last commit, unverified). Take it
  into Kardio (e.g. as a pure module) or rewrite it; it should not be merged into Kora.
- **Watch side facts**: Health Services exercise location is watch GPS only and cannot be toggled mid-exercise
  (only batching): location must be managed outside Health Services (watch GPS via `LocationManager`), which changes
  an accepted Kardio decision. Wear OS FLP chooses its own source and does not guarantee the switch.
- **Phone side facts** (research 2026-10-15, AOSP android16 + developer.android.com): a Data Layer callback is not a
  background-start exemption; a CompanionDeviceManager association plus the normal permission
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` is (no device profile needed), but it does not satisfy
  the while-in-use rule of a `location` FGS: `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") is also required,
  plus `FOREGROUND_SERVICE_LOCATION`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`. Without background location the
  service may run and deliver no fixes, so check it first and tell the watch to use its own GPS. Fallback: a
  notification the user taps exempts both rules. Batch fixes every 5–10 s (engineering choice, no official number).
  ColorOS/Realme: auto-launch, no battery optimisation, lock in recents. Sources: developer.android.com
  `background-work/services/fgs/restrictions-bg-start`, `fgs/service-types`, `connectivity/bluetooth/companion-device-pairing`,
  `sensors-and-location/location/background`, `training/wearables/apps/power`; AOSP `ActiveServices.java`,
  `ForegroundServiceTypePolicy.java` (android16-release).

## Sonora

| Worktree | Branch | State |
|---|---|---|
| `SONORA/common-signing` | `feat/common-signing` (from `wip/fx-levels`) | Done: `4f4f7c12`; debug and benchmark APKs carry the common key. After merging `feat/wear`, drop the benchmark `signingConfig = debug` override in `wear/build.gradle.kts` and `app/build.gradle.kts`. |
| `SONORA/cora-sonora` | `feat/cora-sonora` (from `feat/external-api`) | Done: phone app is a Kora node (`media.library`, `media.playback`), 71 new tests, emulator-verified (gate, peer discovery, MCP via adb), native review approved. See its `odd/tasks/cora-provider.md` (open gaps: per-app grant screen, removal of the old external API, real-library and R8 check on the phone). |
| `SONORA/cora-wear-node` | `feat/cora-wear-node` (from `feat/wear`) | **Partial, stopped**: `1914bdc7`, `dc6b5faa`, `89435808` (watch app as a Kora node: mappings, capabilities, node start). Unverified. |

Pending for Sonora (needed by Kardio's music feature): a timestamped play log and the `media.history` provider
(T7h) once Kora defines `media.history` (T2b).

### Artwork port proposal (for the Sonora session)

Kora defines `media.artwork` 1.0 (`kora-contract/.../media/MediaArtwork.kt`): `get_artwork` turns the `artworkId` of a
`TrackSummary` or `PlaylistSummary` into image bytes over Binder, wear and MCP. It is a separate, optional capability
next to `media.library`; it needs a Kora release that contains it (the owner picks the number). `samples/provider`
serves it with generated images and is the reference. A proposal, for the Sonora session to decide:

- **Ids.** Set `artworkId` in Sonora's `TrackSummary`/`PlaylistSummary` mappings to an opaque key Sonora can resolve
  itself (for instance its album id, or a hash of the cover source), never a file path, URI or `content://` string.
  Tracks of one album share the album's id, so consumers can cache by id. No artwork: leave `artworkId` null.
- **Resolving.** `provide(MediaArtwork) { on(MediaArtwork.GetArtwork) { ... } }` looks the key up in Sonora's own
  album art source (the cover it already shows) and decodes it off the main thread, sampled down while decoding
  (`inSampleSize` or `ImageDecoder.setTargetSize`) so a large cover is never decoded at full size. Stay well inside the
  5 s call timeout; a small in-memory cache of recent results is optional.
- **Size.** Scale so the longer edge is at most `request.maxEdgePx` (16..1024, default 512), keeping the aspect ratio
  and never upscaling a smaller cover. Report the real `widthPx`/`heightPx`.
- **Encoding and cap.** Photos as JPEG (`ArtworkMimeType.JPEG`, quality around 85); a cover that is already a small PNG
  may stay PNG. The bytes must be at most `MediaArtwork.MAX_ARTWORK_BYTES` (90 KiB): if they exceed it, lower the
  quality a step and then the size (for instance by a quarter) until they fit. 512 px JPEGs normally fit at once.
- **Errors.** An id Sonora does not know, or no longer has, throws `KoraException(KoraError(ErrorCode.INVALID_PAYLOAD,
  ...))`, as unknown playlist and track ids already do. Treat a cover that fails to decode the same way (the caller shows
  no artwork).
- **Grants.** `media.artwork.read` is its own scope: an existing `media.library.read` grant does not cover it. The grant
  screen lists it separately ("Cover art"), for peer apps, the adb shell and tailnet pairings alike.
- **Watch.** The watch reaches it through the wear relay like any other call; no watch-side provider is needed unless
  the watch app has its own library.
- **Tests.** Unknown id -> `invalid_payload`, a large cover is scaled to `maxEdgePx` and under the cap, a small cover is
  not upscaled, the refusal without `media.artwork.read`.

## KOKO

| Worktree | Branch | State |
|---|---|---|
| `KOKO-common-signing` | `feat/common-signing` (from `feat/f0-scaffold`) | Done: `63af8e5`; debug and release APKs carry the common key; tests green. |

KOKO can reach Kora apps as a signed peer (once on the common key) or as an MCP client through `kora-bridge`.
