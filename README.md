# Kora

Kora is the communication library of the Bihurri Android apps: apps signed with the same key find each other on the
device and exchange typed requests and events, an app can expose its capabilities to an agent over MCP, and a phone app
can relay to its Wear OS counterpart.

This repository holds the source of each Kora release, one commit per release. Licence: Apache License 2.0 (`LICENSE`,
`NOTICE`).

## Modules

| Module | What it is |
|--------|------------|
| `kora-contract` | The capability contract: ids, versions, operation and event specs, wire envelope, errors and codecs. |
| `kora-core` | The node: provider registry, router, caller policy, audit and event subscriptions. |
| `kora-android` | Android transports: Binder peer transport, discovery, caller identification and the external gate. |
| `kora-mcp` | Exposes any Kora node as MCP tools generated from the capability contract. |
| `kora-mcp-http` | MCP over Streamable HTTP with pairing-token authentication. |
| `kora-tailnet` | Serves a node over MCP HTTP on the Tailscale address only, behind a foreground service with auto-off. |
| `kora-wear`, `kora-wear-protocol` | Phone to watch relay over the Wear OS Data Layer. |
| `kora-bridge` | A stdio MCP server on a PC for the Kora node of an Android app, reached through adb (not a library). |

Each module's `README.md` has the details; `docs/` covers releasing, compatibility and signing.

## Using it

The libraries are published as `eus.bihurri.kora:<module>:<version>` in a static Maven repository that needs no
login:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://largobihurri.github.io/kora-public/maven") { content { includeGroup("eus.bihurri.kora") } }
    }
}

// a module's build.gradle.kts: an exact version
dependencies { implementation("eus.bihurri.kora:kora-android:1.2.1") }
```

## Building

JDK 17 or later and the Android SDK; `./gradlew build` builds and tests every module.
`./gradlew publishAllPublicationsToStagingRepository` writes the Maven tree to `build/staging-repo`.
