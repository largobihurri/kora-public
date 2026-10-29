# kora-mcp

Exposes any `KoraNode` as an MCP server. Pure JVM, no network code: `McpSession` turns one JSON-RPC message into
zero or one JSON-RPC message, so a transport (stdio bridge, Streamable HTTP) is a thin loop around it.

## Specification
Implements MCP `2026-07-28` (stateless, `server/discover`, `subscriptions/listen`) and, on the same session, the
handshake revisions `2025-11-25` and `2025-06-18`. Source: https://modelcontextprotocol.io/specification .
Out of scope: resources, prompts, logging, sampling, tasks, Kora events (subscriptions), JSON-RPC batches (removed
since `2025-06-18`), and every transport. Transports so far: the gate method `kora.mcp` of `:kora-android` (one session
per caller, over `adb`) and the PC-side stdio bridge `:kora-bridge`.

## Tools
One tool per operation of the built-in capabilities the node can reach now (its own or a compatible peer's), named
`<capability, dots as underscores>__<operation>`, e.g. `media_library__list_playlists`. The description adds the
required scope. `inputSchema` and `outputSchema` (JSON Schema 2020-12) are generated from the serializer
descriptors (`JsonSchemas.input` / `JsonSchemas.output`): `required` = properties without default, wire enums list
their values without `unknown` in an `inputSchema` (a caller never sends it) and with `unknown` last in an
`outputSchema` (a result may come from a peer on a newer minor whose new constant this build reads as `unknown`, so
`structuredContent` still conforms), nullable = `["string","null"]` or `anyOf`, integers annotated with `@IntegerRange` add `minimum` and
`maximum` (e.g. `maxEdgePx` of `media_artwork__get_artwork`: 16..1024). Results always carry defaults, so every property of an
`outputSchema` is required. `structuredContent` is always an object; a result that is `null`, a primitive or a list
is wrapped as `{"result": ...}`. `tools/list` paginates with opaque cursors; a peer joining or leaving emits
`notifications/tools/list_changed`. Because `KoraNode.provide` requires a handler for every operation of the capability
(a capability is atomic), every advertised tool is backed by a handler: none answers `unknown_operation`.

A successful result's `content` is one `text` block holding the `structuredContent` JSON, except for
`media_artwork__get_artwork`: an `image` block (`data` = the Base64 of `structuredContent.bytes`, `mimeType`) plus a
short metadata `text` (type, pixels, byte count, no bytes). The Base64 is then in the answer twice, which is what
`MediaArtwork.MAX_ARTWORK_BYTES` (90 KiB) is sized for: a maximum-size answer is about 246 KB, under the 256 KiB
`McpGate` limit. An artwork whose type is `unknown` gets the plain JSON text instead, since an image block needs a
real image MIME type (`ToolContent`).

## Hosting a session
```kotlin
val session = McpSession(node, Caller(CallerKind.REMOTE_AGENT, pairingId), McpServerInfo("kardio", "1.0"))
// one session per connection (or per caller identity, when the transport is stateless like the adb gate)
// per incoming line / HTTP body:
session.handle(message)?.let(::write)          // or session.serve(message) to stream subscriptions/listen
// legacy clients: forward session.notifications to the client for the life of the connection
```
`tools/call` runs through `JsonGate` (`forwardToPeers = true`), so lookup, version check, policy and audit are the
node's. Failures of the tool are results with `isError: true` (`unauthorized: Scope '...' is not granted`); protocol
failures are JSON-RPC errors (`-32700`, `-32600`, `-32601`, `-32602`, `-32603`, `-32022`).

## Security
- The caller is fixed at construction by the transport from facts it verified; nothing in the payload, including
  `_meta.clientInfo`, sets identity. Any external kind is accepted (`REMOTE_AGENT`, `SHELL`, `EXTERNAL_APP`; see
  `McpSession.serves`) so grants stay per caller: an adb transport serves the `shell` caller, a future HTTP one a
  paired `REMOTE_AGENT`. `SELF` and `PEER` are refused: they hold every scope and must not be borrowed by an agent.
- The node's policy applies: master switch on plus an explicit grant per scope, audited. Peer capabilities are
  authorized by the hosting node first (`KoraNode.dispatch`), because peers trust it as `PEER`.
- This module opens no listener. A future transport must bind to the tailnet interface only, require the pairing
  token and TLS (Kardio plan), and derive the `Caller` from that pairing, never from the request.
