# kora-bridge

Stdio MCP server for the Kora node of an Android app, reached through adb. An MCP client on the PC (Claude Code)
launches it; it forwards each JSON-RPC message to the app's gate method `kora.mcp` and prints the answer.
Nothing listens on the network, on the phone or on the PC.

## Build and run
```sh
./gradlew :kora-bridge:installDist     # runtime needs a JDK 17+ on PATH or JAVA_HOME
kora-bridge/build/install/kora-bridge/bin/kora-bridge --serial emulator-5554 --package eus.bihurri.kora.sample.provider
```
Required: `--serial`, `--package` (applicationId). Optional: `--adb <path>`, `--timeout <s>` per adb call (default
30). stdin/stdout carry one UTF-8 JSON-RPC message per line; logs go to stderr.

## Claude Code
Verified with Claude Code 2.1.183 (the `.mcp.json` below is what `claude mcp add --scope project` writes):
```sh
claude mcp add kora-sonora -- /path/to/kora-bridge/bin/kora-bridge --serial <adb-serial> --package <applicationId>
```
```json
{"mcpServers":{"kora-sonora":{"type":"stdio","command":"/path/to/kora-bridge/bin/kora-bridge",
  "args":["--serial","<adb-serial>","--package","<applicationId>"]}}}
```

## Security model
- adb access is the credential: the app sees the caller as `shell` (uid 2000), exactly like `adb shell content call`.
  The app's master switch and the scopes granted to `shell` decide which tools work; others answer `isError`
  (`unauthorized: ...`) and are reported through `ExternalAccess.onDenied`. Every call is audited by the node.
- The caller identity comes from the uid, never from the messages. `SELF` and `PEER` are not served over MCP.
- The command line carries `b64:` + URL-safe Base64 (`A-Za-z0-9-_`), so no message content reaches the remote shell
  as syntax. `--serial` and `--package` are validated for the same reason. Messages are not logged.

## Limits
- One request at a time, in order; a slow tool call delays the next message. Requests up to ~75 KB (adb command
  line); answers up to 256 KiB (the gate's limit), larger ones fail with a JSON-RPC error.
- No server-initiated traffic: `notifications/tools/list_changed` and `subscriptions/listen` are not delivered.
- The phone keeps one MCP session per caller, so everything arriving by adb shares the `shell` session: use one
  client per app. If the app process is restarted, the bridge replays the remembered `initialize` and retries.
- Failures (adb missing, device offline, app not installed, gate refusal) become JSON-RPC errors `-32000` with the
  id of the request (`data.code` holds the gate's code); a line that is not JSON gets `-32700` (`-32600` if it is JSON but not an object).
