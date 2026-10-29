# kora-android

Android transports for Kora: a Binder transport so apps signed with the same key find each other and exchange typed
calls and events, and an external gate (`ContentProvider`) for the adb shell, third-party apps and, later, remote
agents. Both reach the same node and the same caller policy.

## Integrate an app

1. Depend on a published release with an exact version: `implementation("eus.bihurri.kora:kora-android:X.Y.Z")`
   (repository and token setup: `docs/releasing.md`).
2. Install the node once in `Application.onCreate` and register what the app provides inside the block, so the
   node is published (to peers, the gate and the shell) only when it is complete:

   ```kotlin
   val node = KoraAndroid.install(this) {         // wires Binder transport + peer discovery
       provide(MediaLibrary) {
           on(MediaLibrary.ListPlaylists) { page -> ... }
           on(MediaLibrary.GetPlaylistTracks) { request -> ... }
           on(MediaLibrary.SearchTracks) { request -> ... }
       }
   }
   ```

   A capability is an atomic contract: `provide(capability) { ... }` must register a handler for **every** operation
   and a source (`emits`) for **every** event the capability declares, otherwise it throws
   `IllegalArgumentException` naming the capability and the missing members. That happens inside `install`'s block,
   so a partial provider fails at app start instead of advertising operations that would answer `unknown_operation`
   to peers, the gate and MCP. If an app can offer only part of a feature, that part is a separate capability.
3. Use peers through the node: `node.call(spec, input)`, `node.events(spec)`, `node.peers`,
   `node.availability(capability)`. The default target is this app, then any peer that offers the capability.

Peers can also arrive by other routes: `KoraAndroid.install(this, peerRoutes = listOf(route))` merges their directory and transport
with the Binder ones (Binder wins on a duplicate node id). `:kora-wear` uses it to reach the paired phone or watch.

The library manifest already declares the exported `KoraNodeService` (action `eus.bihurri.kora.action.NODE`), the
exported `KoraGateProvider` (authority `<applicationId>.kora`) and the `<queries>` entry needed to discover peers on
Android 11+. Nothing else goes in the app manifest.

## Who is who

Every Binder transaction and every gate call is identified from the calling uid, never from the payload:

| Caller | How it is recognised | `Caller` |
|---|---|---|
| The app itself | same uid | `SELF`, id = package name |
| adb shell / root | uid 2000 / 0 | `SHELL`, id = `shell` / `root` |
| A Bihurri app | same signing certificate | `PEER`, id = package name |
| Any other app | any other uid with a package | `EXTERNAL_APP`, id = package name, `signingCertificateSha256` |

A caller that cannot be identified (a uid without packages, or an app whose certificate cannot be read) is answered
`unauthorized`. What each kind may do is decided by the node's caller policy, per scope:

- `SELF` and `PEER` may use every scope; peers are trusted because they carry the common signing key.
- `EXTERNAL_APP`, `SHELL` and `REMOTE_AGENT` may use a scope only if the app's `ExternalAccess` says the master switch is
  on **and** that exact caller was granted that scope. Scopes never imply each other (`media.playback.control` does not
  give `media.playback.read`).

## External access

External access is controlled by the app and its user, not by Android:

- **No Android permission.** The library declares none. A custom permission would be defined by every Kora app, and apps
  signed with different keys cannot define the same permission (`INSTALL_FAILED_DUPLICATE_PERMISSION`); it would also
  invite permission squatting. The gate is exported and checks the caller in code.
- **`ExternalAccess` port**, implemented by the app over its settings, and passed as the policy:
  `KoraAndroid.install(this, policy = DefaultCallerPolicy(externalAccess))`.
  - `isEnabled()`: the master switch. **Default it to off** and expose it in the app's settings.
  - `grantedScopes(caller)`: the scopes the user granted to that caller, one by one. There are no wildcards.
  - `onDenied(caller, scope)`: called whenever an external caller is refused (switch off or scope not granted), so the
    app can show "X asked for media.library.read" and let the user approve it. It runs on the call path: record and
    return, de-duplicate per caller and scope, and hand slow work elsewhere. Failures are swallowed.
- **Grants are keyed on package name plus certificate digest.** For an `EXTERNAL_APP`, `Caller.signingCertificateSha256`
  is the lowercase SHA-256 of its signing certificate (what `apksigner verify --print-certs` prints). A package name can
  be reused by a different app after the original is uninstalled; keying on both means that app does not inherit the
  grants. `Caller` is a value type, so it can be used as a map key as it is. With several signers the digest is a
  SHA-256 over the sorted signer fingerprints; after a signing-key rotation it is the current certificate, so the user is
  asked again (safe default).
- **Revocation.** The policy is consulted when a call or subscription starts. When the user turns the master switch off
  or removes a grant, call `KoraAndroid.revokeUntrustedSubscriptions()`: it ends every live subscription of external apps,
  the shell and remote agents with `unauthorized`. Single calls need nothing, each is authorized on arrival.
- **Audit.** Every access is audited by the node (`AuditLog` passed to `install`), including refused ones. Entries never
  hold payloads.

`samples/provider` shows an in-memory `ExternalAccess` (`SampleExternalAccess`): switch on, the shell granted
`media.library.read` and `media.artwork.read` only. A real app must not do this.

## The gate

`KoraGateProvider` (authority `<applicationId>.kora`, e.g. `content://eus.bihurri.kora.sample.provider.kora`) serves
`ContentResolver.call(uri, method, arg, extras)` and nothing else (`query`, `insert`, `update`, `delete` are unsupported).
The calling uid is read in each call. Call it from a background thread: a caller in the same process on the main thread
is refused. Requests and answers above 256 KiB are answered `payload_too_large`; a handler that takes longer than the
node's call timeout (5 s by default) is answered `timeout`.

| Method | Input | Result keys |
|---|---|---|
| `kora.describe` | none | `json`: the node descriptor |
| `kora.call` | extra `request`: CBOR `KoraRequest` bytes | `response`: CBOR `KoraResponse` bytes (success or failure) |
| `kora.call.json` | `arg`: JSON object, below | `json`: `{"ok":true,"output":...}` or `{"ok":false,"error":{"code","message"}}` |
| `kora.mcp` | `arg`: one JSON-RPC message, plain or `b64:`-encoded, see [MCP over adb](#mcp-over-adb) | `json` (or `json_b64`): the JSON-RPC answer; no key for a notification |

The constants are in `KoraGate` (`METHOD_*`, `EXTRA_REQUEST`, `KEY_*`). When the gate itself refuses a call (Kora not
installed: `provider_unavailable`, main thread: `provider_unavailable`, unidentified caller: `unauthorized`, missing
input: `invalid_payload`, unknown method: `unknown_operation`) the bundle also has `error_code` and `error_message`.
A refusal by the policy is a normal answer (`unauthorized` inside `json` or `response`), with no `error_code` key.

`kora.call.json` takes typed, nested JSON, never number arrays:

```json
{"capability":"media.library","version":"1.0","operation":"list_playlists","input":{"limit":10,"offset":0}}
```

`version` is optional (the version of the built-in capability) and so is `input` (`{}`). Field names are those of the
contract types (`PageRequest` is `limit` and `offset`; `PlaylistTracksRequest` is `playlistId` and `page`; `SearchRequest`
is `query` and `page`). Unknown keys are ignored. Operations that return nothing answer `"output":{}`. Only the built-in
capabilities can be called this way. Error codes are the contract's wire names (`unauthorized`, `invalid_payload`,
`unknown_capability`, `unknown_operation`, `incompatible_version`, `provider_unavailable`, `timeout`,
`payload_too_large`, `internal`).

An app calling the gate on Android 11+ needs package visibility for it: add
`<queries><provider android:authorities="eus.bihurri.kora.sample.provider.kora" /></queries>` to its manifest.

### From adb

The adb shell is identified as `SHELL` (uid 2000), so it is served only what the app granted to it. The `content` tool
prints the bundle (`Result: Bundle[{json=...}]`).

The remote shell parses the command again, so the JSON needs a second level of quoting. Either wrap the argument in an
inner pair of single quotes (`"'...'"`), or wrap the whole remote command in double quotes. Both pass the JSON through
unchanged; a plain `--arg '{"a":1}'` does not, because the remote shell strips the double quotes. Avoid single quotes
inside the JSON.

```sh
# describe
adb shell content call --uri content://eus.bihurri.kora.sample.provider.kora --method kora.describe

# an allowed call
adb shell content call --uri content://eus.bihurri.kora.sample.provider.kora --method kora.call.json \
  --arg "'{\"capability\":\"media.library\",\"operation\":\"list_playlists\",\"input\":{\"limit\":10,\"offset\":0}}'"

# the same, wrapping the whole remote command instead
adb shell "content call --uri content://eus.bihurri.kora.sample.provider.kora --method kora.call.json --arg '{\"capability\":\"media.library\",\"operation\":\"list_playlists\"}'"

# a call the shell was not granted (media.playback.read): answered unauthorized and recorded by onDenied
adb shell content call --uri content://eus.bihurri.kora.sample.provider.kora --method kora.call.json \
  --arg "'{\"capability\":\"media.playback\",\"operation\":\"get_state\"}'"
```

Use `adb -s <serial>` when several devices are connected.

### MCP over adb

`kora.mcp` hosts an MCP server (`:kora-mcp`) behind the gate, so an MCP client on the PC reaches the node through adb
with no network listener on the phone. `:kora-bridge` is the ready-made stdio client; the method can also be called by
hand. `arg` is one JSON-RPC message; the answer is the JSON-RPC response in `json`, and a notification has no key.

- **Session per caller.** The runtime keeps one `McpSession` per identified caller, so `initialize` and later requests
  may arrive in separate `content call`s. adb is always `shell`, so grants are the ones given to `shell`, and all adb
  clients of the app share that session (one MCP client per app). An app process restart loses it: a handshake-era
  client must `initialize` again (the bridge does it). Tools of peer capabilities are served too, authorized by this app.
- **Callers.** Any external kind is served (`SHELL`, `EXTERNAL_APP`); the identity comes from the uid, never from the
  payload. `SELF` and `PEER` are refused with `error_code=unauthorized`: they hold every scope and have the typed API.
- **Encoding.** The remote shell re-parses the command, so an `arg` that starts with `b64:` is the message in UTF-8,
  URL-safe Base64 without padding (`A-Za-z0-9-_`, nothing to quote), and the answer then comes as `json_b64` in the same
  encoding. Anything else is the message itself and the answer is plain `json`. A JSON-RPC message starts with `{`, so
  the two never collide.
- **Refusals.** The gate's own refusals (main thread, unidentified caller, missing or invalid argument,
  `payload_too_large`) carry only `error_code` and `error_message`, never `json`, because they are not JSON-RPC.
  Requests and answers above 256 KiB are refused; the command line of `adb shell` limits a request to ~75 KB in practice.
- **No streaming.** `subscriptions/listen` is answered `-32601` and list-changed notifications are never delivered.

```sh
adb shell content call --uri content://eus.bihurri.kora.sample.provider.kora --method kora.mcp \
  --arg 'b64:eyJqc29ucnBjIjoiMi4wIiwiaWQiOjEsIm1ldGhvZCI6InBpbmcifQ'   # {"jsonrpc":"2.0","id":1,"method":"ping"}
```

## Behaviour of the Binder transport

- Trust: the service identifies the caller as above. Discovery lists only apps signed with our key as peers; a third party
  can bind the service (the policy then decides per scope) but is never listed as a peer. The node id of an Android app is
  its package name.
- Binding is lazy and shared per peer; a connection with no call or subscription in flight is unbound after
  `idleTimeout` (default 30 s). A dying peer fails in-flight calls with `provider_unavailable`; the next use reconnects.
- Envelopes above 256 KiB are refused with `payload_too_large` (Binder transactions share ~1 MB per process).
- Peer descriptors are cached per package version and refreshed on package install/update/removal.
- `describe` is answered to any identified caller: it lists capability ids and versions only, which a caller needs before
  asking the user for access.
- Why the gate may block a Binder thread while the service does not: `ContentProvider.call` is synchronous by contract, so
  the handler runs under `runBlocking`, bounded by the call timeout. The service stays asynchronous (callbacks) because it
  also carries long-lived subscriptions for many concurrent callers.

Samples: `samples/provider` and `samples/consumer` (same debug key; filter logcat by `KoraSample`).
