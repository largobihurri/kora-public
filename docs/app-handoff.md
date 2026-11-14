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

Key: `~/.bihurri/signing/` (see `docs/signing.md`). The shared key is RSA now, certificate SHA-256
`5f281692…c59f`; it follows the first key, an EC one with certificate SHA-256 `47c3297e…60d2`, through the EC to RSA
signing lineage `ec-to-rsa.lineage` in the same folder (both digests are read from that lineage). Kora peers trust
each other only when they share it. Switching an installed app needs one uninstall (data loss): back up first, phone and watch
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

### Volume consumer guidance (for the Kardio session)

Kora defines `media.volume` 1.0 (`kora-contract/.../media/MediaVolume.kt`; first released in Kora 1.4.0):
`get_volume`, `set_volume(level, muted?, order?)` and the event `volume_changed`, all carrying `VolumeState(level,
maxLevel, muted)`. For the crown on the music page:
- **Absolute levels.** Map rotation to an absolute level in `0..maxLevel` of the last state seen (never relative
  steps). Past the ends is fine: the provider clamps and answers the applied state.
- **Coalesce and order.** Accumulate crown ticks into a target level and send only the latest target. Every write
  carries a `WriteOrder(writer, sequence)`: `writer` a random UUID per session (each app start, or each time the page
  opens), `sequence` increasing with every write. Kora dispatches calls in parallel, so either keep one write in flight
  (when it answers, send the newest target if it changed) or rely on the order: the provider applies only the newest
  write of the current writer, and a replaced writer's late writes are ignored.
- **Cold start.** When the music page opens, refresh the wear directory (`KoraWear.requestRefresh()`), then
  `node.awaitProvider(MediaVolume, 5.seconds)` (or longer) before the first call, so it does not fail with
  `provider_unavailable` while the phone's descriptor is still arriving. If it returns false, show the control as
  unavailable and keep observing `availability(MediaVolume)`. Wait with the same target as the call: if the watch
  app itself provides `media.volume`, an untargeted wait answers at once with the local one, so pass
  `Target.Peer(<phone node id>)` to `awaitProvider` and to the calls.
- **Keep a subscription.** Collect `volume_changed` while the page is visible: it starts with the current state, keeps
  the link warm, and updates the UI when the volume changes on the phone (hardware buttons, other apps). Cancel it when
  the page goes away.
- **Echo.** While writes are in flight, show the local target and ignore `volume_changed` states that differ from it
  (they are older writes coming back); once the last write has answered, adopt the provider's state.
- **Scopes.** `media.volume.read` (`get_volume`, `volume_changed`) and `media.volume.control` (`set_volume`). The
  watch is Sonora's counterpart (`PEER`), trusted by the default policy.
- **Measured latency** (watch -> phone, `samples/wearcheck` steps `volume_latency` and `volume_event`): to be filled
  from the device run.

  | Measure | Value |
  |---|---|
  | `set_volume` round trip p50 / p95 / max (30 sequential) | _pending device run_ |
  | `volume_changed` after `set_volume` (`eventMs`) | _pending device run_ |
  | Cold: `peerWaitMs` + first `getMs` after a fresh start | _pending device run_ |

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

### Volume port proposal (for the Sonora session)

Kora defines `media.volume` 1.0 (`kora-contract/.../media/MediaVolume.kt`, binding rules in its KDoc; first released
in Kora 1.4.0). Kardio's watch uses it to drive Sonora's volume from the crown. Proposal for Sonora's
phone app:
- **Which volume.** Either the music stream (`AudioManager.STREAM_MUSIC`): what the hardware buttons change and what
  the user hears, with integer steps already (`getStreamMaxVolume`); or Sonora's player volume: independent of other
  apps but a float that needs a mapping to steps (for instance 20) and does not follow the hardware buttons.
  Recommendation: the music stream, so the crown and the buttons always agree.
- **Steps.** Announce the steps as `maxLevel` (the stream's maximum) and report `level` as the current step.
- **Set.** Clamp `request.level` to `0..maxLevel` (and to `getStreamMinVolume` if the device has a floor), apply it
  without showing the system volume UI, and answer the applied `VolumeState`. `muted` null leaves mute unchanged; true
  or false mutes or unmutes (`adjustStreamVolume` with `ADJUST_MUTE`/`ADJUST_UNMUTE`, `isStreamMute`), keeping the level.
- **Order.** One `WriteOrderGuard<Caller>()` for the provider, and `set_volume` through
  `applyInOrder(caller, request.order, apply = { ...; currentState() }, current = { currentState() })`, keyed by
  `CallContext.caller`: writes without `order` always apply; ordered ones apply last-write-wins per writer session.
- **Event.** Keep a `MutableStateFlow<VolumeState>` and `emits(MediaVolume.VolumeChanged) { state }`. Update it for
  every change, not only Kora's writes: hardware buttons and other apps too (for instance a `ContentObserver` on
  `Settings.System.CONTENT_URI` that re-reads the stream volume and mute; with Media3 device volume,
  `Player.Listener.onDeviceVolumeChanged`). A new subscriber gets the current state first.
- **Grants.** Two separate scopes: `media.volume.read` and `media.volume.control`. The counterpart watch (`PEER`) is
  trusted; for external callers (other apps, adb shell, tailnet pairings) list them on the grant screen ("Volume",
  "Change volume") and keep `media.volume.control` off until the user grants it.
- **Tests.** Clamping above `maxLevel`; `muted` null keeps mute; a stale ordered write leaves the state unchanged; a
  hardware button change is emitted; the refusal without `media.volume.control`. Reference provider:
  `samples/provider/.../SampleVolume.kt` (in-memory, with tests).

## Oharrak

Oharrak provides the organizer vocabulary of Kora `1.3.0` (released, tag `v1.3.0`). The design is `docs/organizer-vocabulary.md`; what the provider module needs,
operation by operation, is `docs/organizer-handoff.md`.

- Provide six capabilities at `1.0`: `reminders`, `calendar`, `notes`, `reminders.attachments`, `notes.attachments`
  and `files`. A capability is atomic: every operation needs a handler. `calendar.attachments` is not in `1.3.0`: it
  is designed (section 8 of the design) and arrives later as an additive capability, once Oharrak has a store for
  the attachments of events.
- Contract changes after Oharrak's review of 2026-11-12, to re-read: `docs/organizer-vocabulary.md` section 13,
  "Third round", lists each with where it lives. Among them: `notes` has twelve operations, `delete_note` only moves
  a note to Recently Deleted and `delete_note_permanently` deletes one that is there for good; every operation
  declares an effect (`OperationSpec.effect`).
- Recording: Oharrak's recorder is not commanded through Kora in `1.3.0`. A voice note from KOKO is KOKO's own
  recording, uploaded with `files.upload` and attached with `notes.attachments.add` (`audio/*` is an audio
  attachment). Commanding Oharrak's recorder is a later, additive capability whose design is redone to fit it
  (section 10 of the design).
- `files.upload` and `files.download` carry a byte stream: register them with `onUpload` and `onDownload`, read on
  `Dispatchers.IO` and to the end, check `UploadFileRequest.sizeBytes` against the stream and your own limit, and
  answer an upload within the caller's `streamIdleTimeout` of its last byte (an upload only stages the file).
- Streams cross only Binder between apps on one phone (protocol 2). An app still on Kora `1.2` sees and calls a `1.3`
  app for everything that carries no stream.
- The Binder streams are checked on the emulator (API 36) with `samples/stream-checks.sh`: a file goes from one app to the other and
  back byte for byte, a read or a write blocked on a pipe ends when the descriptor is closed (by the idle timeout or
  by another thread), and a stream fails at once, rather than hanging, when the provider or the caller is killed
  half way through. The first upload and download between Oharrak and KOKO on a phone confirms it on hardware.

### AI consumer guidance (for the Oharrak session)

Kora defines `ai` 1.0 (`kora-contract/.../ai/AiCapability.kt`, binding rules in its KDoc; first released in
Kora 1.4.0). KOKO
provides it; Oharrak's `KoraSummaryEngine` consumes it, and a KOKO-backed transcriber too (for phones without Android's
on-device recognizer). Oharrak holds no model and no API key.

- **Engines.** `ai.list_engines` answers `EngineList`: each `AiEngine` has `id`, `name`, `kind` (`ON_DEVICE`, `API`),
  `tasks` (`TRANSCRIBE`, `SUMMARIZE_MEETING`, `ASK`), `available` and `isDefault`. Map it to `SummaryEngineOption`;
  offer only the engines whose `tasks` hold the task in question. Keep the chosen `id` in the settings and send it as
  `engineId` (null for KOKO's default). Treat `EngineKind.UNKNOWN` and `AiTask.UNKNOWN` as newer values.
- **Transcription.** Stage the recording with `files.upload` of KOKO (`audio/wav`, 16-bit PCM, mono, 16 kHz; about
  115 MB an hour; every provider takes at least 512 MiB) and start `transcribe(TranscribeRequest(audioFileId,
  engineId?, language? such as "es-ES", fromMs, requestKey?))`. To go on after the passages Oharrak already has, send
  `fromMs` = the end of the last one (stage the whole recording again; offsets still count from its start). Read the
  passages with `get_transcript_passages(jobId, offset, limit ≤ 200)`, in pages, while the job runs, once it
  succeeded and after it failed; `AiJob.transcript.passageCount` says how many there are so far. Each passage has
  `startMs`, `endMs` and `text`, like Oharrak's `Passage`. `progressPercent` is set when KOKO can tell.
- **Jobs, not calls.** `summarize_meeting` and `ask` answer at once with an `AiJob` in `QUEUED`; the summary arrives
  later. Keep the `jobId` with the meeting (summary pending), then read it with `get_job` (on start and when the
  meeting is shown) or subscribe to `job_changed` while Oharrak runs. `SUCCEEDED` carries `summary`
  (`overview`, `decisions`, `actionItems`, the same shape as Oharrak's `Summary`) or `answer`; `FAILED` carries
  `failure.reason`. Finished jobs are kept at least 24 hours; after that the id is `invalid_payload`, so ask again.
- **Request keys.** Send `requestKey` (for example `"<meetingId>#<attempt>"`). After a `timeout`, send the same request
  again: KOKO answers the job it already started. A failed or cancelled job is not reused for its key.
- **Texts.** A transcript of up to 40 000 characters goes inline (`TextInput(text = ...)`). A longer one is staged
  with `files.upload` of KOKO (`text/plain`, UTF-8) and named by `TextInput(fileId = ...)`. `ask` takes up to 100
  documents whose inline texts together hold at most 40 000 characters; stage the rest.
- **Grants.** `ai.read` (`list_engines`, `get_job`, `get_transcript_passages`, `job_changed`) and `ai.run`
  (`transcribe`, `summarize_meeting`, `ask`, `cancel_job`), plus `files.write` to stage recordings and texts.
- **Unavailable.** No provider of `ai` (KOKO not installed or not yet on this Kora) is `provider_unavailable`; map it to
  `EngineUnavailable.UNREACHABLE` and keep the summary pending.

## KOKO

| Worktree | Branch | State |
|---|---|---|
| `KOKO-common-signing` | `feat/common-signing` (from `feat/f0-scaffold`) | Done: `63af8e5`; debug and release APKs carry the common key; tests green. |

KOKO can reach Kora apps as a signed peer (once on the common key) or as an MCP client through `kora-bridge`.

### Organizer vocabulary (for the KOKO session)

With Kora `1.3.0`, KOKO operates Oharrak's reminders, calendar and notes through the capabilities listed under
Oharrak, each with a `.read` and a `.write` scope; the design is `docs/organizer-vocabulary.md`.

- Every operation declares its effect, `OperationSpec.effect` (`OperationEffect.READ`, `WRITE` or `DESTRUCTIVE`);
  KOKO reads it from the spec. Every `.read` operation is `READ`. The destructive ones are
  `reminders.delete_reminder`, `delete_list`, `delete_tag`, `calendar.delete_event`, `delete_calendar`,
  `notes.delete_note_permanently`, `delete_folder` and `remove` of `reminders.attachments` and `notes.attachments`.
  `notes.delete_note` is `WRITE`: it moves the note to Recently Deleted, from where `restore_note` brings it back.
  The effect is not on the wire; MCP clients see it as the tool annotations `readOnlyHint` and `destructiveHint`.

- Every operation is an MCP tool except `files.upload` and `files.download`: a model cannot hold bytes.
- To attach a file the user gave KOKO, KOKO's own code uploads it with the typed client,
  `node.callSending(FilesCapability.Upload, UploadFileRequest(name, mimeType, size), ByteStream(size, input))`, and
  hands the model the answer's `fileId`, which goes into the `add` of `reminders.attachments` or
  `notes.attachments` (events take no attachments in `1.3.0`). To read one back, `open` answers a `FileHandle` and
  `node.callReceiving(FilesCapability.Download, FileIdRequest(fileId))` answers `WithBytes`, which KOKO closes.
- A staged file is used by one `add`, and an `add` that fails leaves it usable; after a `timeout` KOKO reads the
  item again to see whether the attachment is there. A handle stays valid at least 10 minutes after its last use
  while the file exists, and belongs to the caller it was given to.
- A voice note is KOKO's own recording: KOKO records with its own microphone, uploads the audio with `files.upload`
  and attaches it with `notes.attachments.add`. Commanding Oharrak's recorder (`notes.recording`) is not in `1.3.0`;
  it is a later, additive capability whose design is redone to fit Oharrak's recorder.
- Files cross only between apps on the phone: not over MCP, not to the watch.

### AI provider guidance (for the KOKO session)

Kora defines `ai` 1.0 (`kora-contract/.../ai/AiCapability.kt`, binding rules in its KDoc; first released in
Kora 1.4.0). KOKO
provides it for Oharrak's meeting mode (speech to text, summaries, questions), with its on-device models and its API
engines; keys never leave KOKO.

- **Atomic.** Register all seven operations and the event: `list_engines`, `transcribe`, `summarize_meeting`, `ask`,
  `get_job`, `get_transcript_passages`, `cancel_job`, `job_changed`. Also provide `files` so callers can stage
  recordings and long texts (upload only stages; a staged file is used by the one `ai` request that names it, and a
  request refused before the job starts leaves it usable). Accept staged `audio/wav` (16-bit PCM, mono, 16 kHz) of at
  least 512 MiB.
- **Answer within the call timeout (5 s by default).** `list_engines` reads KOKO's engine registry without loading a
  model. `transcribe`, `summarize_meeting` and `ask` only check the request, resolve the engine (an unknown id, or one that does not
  run the task, is `invalid_payload`), store the job and answer it `QUEUED`; the work runs in a foreground service.
- **Jobs.** Keep them per caller (`CallContext` names it): a job id from another caller is `invalid_payload`, and
  `job_changed` shows a subscriber only its own jobs, first every job of its own KOKO keeps, then each change. Keep a
  finished job and its result at least 24 hours. A job left unfinished when KOKO stopped ends `FAILED` with
  `INTERRUPTED` unless KOKO resumes it.
- **Request keys.** The same caller and `requestKey` while the job is kept answers that job, unless it failed or was
  cancelled, in which case a new job starts.
- **Results.** `MeetingSummary` and `answer` hold at most 20 000 characters each; shorten before answering. A
  transcription keeps its passages in order with the job (offsets from the start of the recording, also after
  `fromMs`), counts them in `AiJob.transcript`, and answers them in pages of at most `limit` and 20 000 characters;
  serve the passages so far while it runs and those kept after it failed.
- **Failures.** `ENGINE_UNAVAILABLE` (model missing, no key), `UNSUPPORTED_INPUT` (a format the engine does not
  take, a damaged file), `INPUT_TOO_LARGE`, `NETWORK` (API unreachable or over
  quota), `ENGINE_FAILED`, `INTERRUPTED`; the `message` is for logs only.
- **Effects.** `list_engines`, `get_job` and `get_transcript_passages` are `READ`; `transcribe`, `summarize_meeting`,
  `ask` and `cancel_job` are `WRITE` (they may spend API money). All are MCP tools, so an agent can use inline texts;
  staging needs Binder.
