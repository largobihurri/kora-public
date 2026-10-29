# kora-mcp-http

Serves a `KoraNode` over MCP **Streamable HTTP**: one URL, `POST` a JSON-RPC message, get the JSON answer. It puts
bearer-token authentication in front of `McpSession` (`:kora-mcp`), so an agent on another machine can use an app
without a USB cable. Pure JVM on `java.net.ServerSocket`, no dependency beyond `:kora-mcp`, runs on Android API 29+.

It does **not** decide where to listen. The caller passes the bind address, and the feature's security model needs
it to be the address of the Tailscale interface only (the Android module that does that is separate). The wildcard
address is refused.

## Why a small server of our own
Ktor's server engines are documented for the JVM only, not Android (the CIO engine is listed for JVM, Native and JS),
Android breakages have been reported against its runtime libraries (for example KTOR-3690), and it would add its own
runtime libraries to every app, none of which we can check without a device. This transport needs one `POST`
endpoint with `Content-Length` bodies, so a strict HTTP/1.1 subset on `ServerSocket` (no new dependency, covered by
tests over real sockets) is the smaller risk, and it refuses everything it does not understand instead of guessing.

## Security model
| Layer | What it does |
|---|---|
| Bind address (caller) | Tailnet interface only: nothing on the LAN or the carrier network can connect. |
| Pairing token | `Authorization: Bearer <token>`, 32 random bytes (`SecureRandom`), URL-safe Base64. Only the SHA-256 hash is stored; compared in constant time. `401` + `WWW-Authenticate: Bearer` otherwise. |
| Origin and Host checks | A request with an `Origin` header is `403` unless allow-listed (default: none): browsers always send one, agents never do (required by the specification against DNS rebinding). A `Host` that is neither the bound address nor in `allowedHosts` is `421`; a missing `Host` on HTTP/1.1 is `400`. Both run before the token. |
| Caller identity | Each pairing is `Caller(REMOTE_AGENT, "tailnet:<pairing id>")`, built from the verified token, never from the payload. One `McpSession` per pairing, keyed by id **and** token hash and created only while the store still knows the token: a pairing re-created under a revoked id starts with a fresh session, and a request in flight at revocation cannot revive the old one. Kora's policy, grants and audit apply per pairing exactly as for the adb gate. |
| Limits | Body size (default 1 MiB, `413` before reading), at most 8 simultaneous connections and 2 per remote address (`503` before anything is read), a 3 s deadline for the head before the caller is known, then `readTimeout` for the body, `requestTimeout` and `writeTimeout`, one request per connection. Nothing is read from a body before the caller is known. |
| Failure isolation | On Android an exception that leaves an executor thread kills the app, so every worker body, hook, log call and store call is guarded: a failure ends that connection (the `log` gets a generic line), never the process or the acceptor. A `tools/call` runs on a bounded handler pool so `requestTimeout` holds even for a handler that blocks. |
| Bind address | `start` refuses the wildcard and any address outside loopback and Tailscale's ranges (100.64.0.0/10, fd7a:115c:a1e0::/48) unless `allowNonTailnetAddress` is set. **100.64.0.0/10 is also the carrier-grade NAT range**, so passing the check does not prove the address is the Tailscale interface's: bind that interface's address specifically. |
| Denial reports | `onDenied` is called at most once per remote address and reason per 10 s (`deniedThrottle`); `DeniedAttempt.suppressed` counts what was held back. |
| Logs | One line per request: method, path (or `<other>`), status, pairing id. Never a token, header or body. |

Tokens are not encrypted on the wire by this module: the tailnet (WireGuard) does that. Do not bind this to a network
you do not trust.

## Endpoint behaviour
Checks run in this order, so an unauthenticated peer learns as little as possible: apart from the `Origin` and `Host`
refusals, every request without a valid token gets the same `401`, so paths and methods cannot be probed.

| Request | Status |
|---|---|
| `Origin` header present and not allow-listed | `403` |
| `Host` not the bound address (IP, with or without port) nor in `allowedHosts` | `421` |
| No `Host` on an HTTP/1.1 request | `400` |
| Missing, non-Bearer, unknown, revoked or malformed token (whatever the path or method) | `401` |
| Path other than the endpoint (`/mcp` by default; a query string is ignored), with a valid token | `404` |
| Method other than `POST` (`GET` has no SSE stream, `DELETE` has no session) | `405`, `Allow: POST` |
| `Content-Type` not `application/json` (charset, if any, must be UTF-8) | `415` |
| `Accept` excludes `application/json` (a missing header accepts anything) | `406` |
| `Expect` other than `100-continue` | `417` |
| No `Content-Length`, or chunked body | `411` |
| Bad or duplicated `Content-Length`, both lengths and chunking, malformed head | `400` |
| Body larger than the limit | `413` |
| Head larger than 16 KiB or more than 64 headers | `431` |
| Body not UTF-8 or not JSON | `400`, JSON-RPC `-32700` |
| Unsupported `MCP-Protocol-Version` | `400`, `-32022` with the supported list |
| Version, `Mcp-Method` or `Mcp-Name` header missing or different from the body (stateless era) | `400`, `-32020` |
| JSON-RPC request | `200`, `application/json`, the answer of `McpSession` (protocol errors and tool errors stay in the JSON) |
| JSON-RPC notification or response | `202`, no body |
| The node did not answer within `requestTimeout` | `504` |
| A bug or an `Error` while answering | `500`, JSON-RPC `-32603`, no detail |
| More connections than `maxConnections`, more than `maxConnectionsPerAddress` from one address, or the tool pool is full of stuck handlers (tool calls only) | `503`, `Retry-After: 1` |

HTTP error bodies are JSON-RPC errors with `id: null`, which the specification allows.

### Specification
- Streamable HTTP `2025-11-25`: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- Streamable HTTP `2026-07-28`: https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http

Choices where the specification leaves room:
- **No sessions.** `Mcp-Session-Id` is optional in `2025-11-25` and removed in `2026-07-28`; the server never issues
  one and ignores one that arrives. The legacy handshake state lives in the pairing's `McpSession`, so two clients
  sharing a pairing share it: give each agent its own pairing.
- **JSON only, no SSE.** `GET` is `405`, as both revisions allow. `subscriptions/listen` and MCP change
  notifications are therefore unavailable over HTTP; clients call `tools/list` again when they need it.
- **Versions.** A request whose `_meta` carries `io.modelcontextprotocol/protocolVersion` is stateless-era and needs
  the matching `MCP-Protocol-Version`, `Mcp-Method` and (for `tools/call`) `Mcp-Name` headers. A legacy client may send
  the header with its negotiated version or omit it (`initialize` carries none). `2025-03-26` is not served, so it is
  `400` when sent explicitly.
- **`Accept`** needs to allow `application/json` only; the specification also asks clients to list
  `text/event-stream`, which we never use.

## API
```kotlin
// Pairing: the app persists the hash, shows the token once.
val issued = PairingTokens.generate()                       // issued.token (give to the agent), issued.hash (store)
val pairing = Pairing(PairingTokens.generateId(), "Claude on my-desktop")
store.add(pairing, issued.hash)                              // InMemoryPairingStore, or your own PairingStore

val server = McpHttpServer(
    node = node,
    serverInfo = McpServerInfo("kardio", "1.0"),
    pairings = store,                                        // fun find(tokenHash: ByteArray): Pairing?
    config = McpHttpConfig(
        maxBodyBytes = 1 shl 20,
        allowedOrigins = emptySet(),
        allowedHosts = setOf("gt-7-pro.tail1234.ts.net"),     // MagicDNS name, if clients use it instead of the IP
        maxConnectionsPerAddress = 2, headTimeout = 3.seconds, deniedThrottle = 10.seconds,
    ),
    listener = object : McpHttpListener {
        override fun onRequest(pairing: Pairing) { autoOff.reset() }                  // authenticated request
        override fun onDenied(attempt: DeniedAttempt) { /* reason, remoteAddress, atEpochMs */ }
    },
)
val port = server.start(tailnetAddress, port = 0)            // binds that address only; returns the port
store.revoke(pairing.id); server.evictSession(pairing.id)    // revoke first, then free its session
server.stop()                                                // frees the port; start() works again
```
Revoking is the store's job: once `find` stops returning the pairing, its token is refused on the next request.
`InMemoryPairingStore` also refuses to add a revoked id again: the id is part of the caller identity, so a new pairing
gets a new id.

The bound address is always accepted in `Host`. To let clients use the phone's MagicDNS name
(`<device>.<tailnet>.ts.net`), put it in `allowedHosts`; a port in the request must be the one the server listens on.
Hooks run on the connection's thread before the answer is sent; record and return, and de-duplicate `onDenied`.

## Connecting Claude Code
```bash
claude mcp add --transport http kardio http://<tailnet-ip>:<port>/mcp \
  --header "Authorization: Bearer <token>"
```
The tailnet address is that of the phone (`tailscale ip -4` there, or its MagicDNS name). Remove with
`claude mcp remove kardio`. The agent then sees the tools of the app (`media_library__list_playlists`, ...) and can
call those the owner granted to its pairing.

## Not covered
TLS (the tailnet encrypts), SSE streams, sessions, JSON-RPC batches (removed from MCP), keep-alive, HTTP/2, request
rate limiting (tokens carry 256 bits, so guessing is not a concern; flooding is bounded by `maxConnections`).
