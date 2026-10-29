# Compatibility

Every Bihurri app ships its own copy of Kora, compiled into its APK. Android never lets one app replace another app's
code, so installing Kardio built with Kora 1.1 does **not** update the Kora inside Sonora built with Kora 1.0. Apps
update Kora when their developer bumps the exact Kora version they depend on and rebuilds (`docs/releasing.md`).

What Kora guarantees instead is that **apps built with different Kora versions keep talking**: the wire protocol is
versioned and backward compatible, so nobody has to update at the same time.

## Rules

1. **Capabilities are versioned `major.minor`.** A call is accepted when the majors match and the provider's minor is
   at least the caller's (`CapabilityVersion.isCompatibleWith`). A 1.1 app can use every 1.0 member of a 1.0 peer;
   it uses 1.1 members only after checking `node.availability(...)` / `providers(...)` for a 1.1 provider.
2. **Minor versions only add.** New optional fields (with defaults), new operations, new events, new enum constants.
   Never remove, rename, retype or change the meaning of anything in a minor version.
3. **Readers are tolerant.** Decoders ignore unknown fields; wire enums decode unknown constants as `UNKNOWN`.
   A reader that passes such a value on writes `unknown`, so MCP output schemas list `unknown` among a wire enum's
   values, while input schemas leave it out (`kora-mcp/README.md`).
4. **Breaking changes need a new major**, and a provider keeps serving the previous major for at least one release of
   every app. A node can provide two majors of one capability at once, see "Two majors at once" below.
5. **Transports are versioned separately**: Binder (`IKoraNode.protocolVersion`), the wear bridge (frame header
   version), the adb gate (method names), MCP (negotiated protocol revision). A transport version is raised only for
   additive changes, and the old one is kept readable.
6. **Atomic capabilities**: a provider of `x@1.1` provides every member of 1.1, so a caller that sees `x@1.1` can rely
   on all of it.
7. **New apps** join by consuming Kora; new vocabulary is added to Kora as new capabilities or minor versions, never
   as app-specific hooks.

## Two majors at once

A node keeps one provider per capability id **and major**. To break a capability, define the new major as a second
`Capability` object with the same id and major `n+1`, and register both:

```kotlin
node.provide(ThingV1) { ... }   // thing@1.x, kept for the transition
node.provide(ThingV2) { ... }   // thing@2.0
```

- A request is served by the provider whose major equals the major it carries; asking for a major nobody provides is
  `incompatible_version` (the message lists the versions the node has), an unknown id stays `unknown_capability`.
- `describe()` advertises both versions, so peers (and `KoraNode.availability` / `providers` of a spec built against
  either major) see exactly what each major offers.
- Registering the same id and major twice fails. Within one major the minor rule is unchanged.
- Everything that looks a built-in capability up is major-aware. `Capabilities.find(id, major)` returns the definition
  of that major (`find(id)` returns the newest). `KoraNode.dispatch` and `dispatchSubscribe` authorize a forwarded
  request with the scope of the major it carries (a major the registry does not know is `unknown_operation`, never
  checked against another major's scope), `JsonGate` decodes with the definition of the `version` it is given (without
  one, the newest), and MCP offers the tools of every version in the registry. The tools of major 0 and 1 keep the
  names `<capability>__<operation>`; from major 2 the name carries the major, `<capability>_v2__<operation>`, so a
  second major never renames the first. A different set of definitions (tests, or capabilities outside Kora) goes
  through `CapabilityRegistry`, an optional parameter of `KoraNode`, `JsonGate` and `McpSession` (`McpToolSet`).
- Drop the old major only after every app has shipped a release that speaks the new one.

## Binary fields

A contract field that carries bytes (today only `ArtworkImage.bytes` of `media.artwork`) is annotated
`@ByteString @Serializable(with = BytesSerializer::class)`: a CBOR byte string between peers and a padded standard
Base64 string in JSON (MCP schema `{"type":"string","contentEncoding":"base64"}`). Every binary field has a byte cap in
the contract chosen so that the encoded message fits the 256 KiB limit of every transport, including MCP answers that
repeat the bytes in Base64; `MediaArtwork` documents the arithmetic. Raising a cap is a breaking change for readers
on smaller transports, so it needs a new major.

## Guarantees enforced by the build

`./gradlew build` fails when one of these breaks:

1. **Golden wire fixtures** (`kora-contract/src/test/resources/wire`, `kora-wear-protocol/src/test/resources/wire`):
   `<group>/<version>/<name>.cbor.hex` (the bytes peers exchange) and `<name>.json` for every operation input, operation
   output and event of every built-in capability, the envelope types (`KoraRequest`, `KoraResponse`, `KoraEvent`,
   `KoraError`, `SubscribeRequest`, `NodeDescriptor`; the same bytes cross the Binder interface) and every relay frame.
   `<group>` is a capability id, `envelope` or `wear`; `<version>` is the capability version (`1.1`), the envelope
   revision (`WireSamples.ENVELOPE_VERSION`) or the frame revision (`WireFixturesTest.REVISION` in the wear module,
   whose major is `WearProtocol.VERSION`). The sample values live in one place per module (`WireSamples`, and the
   wear test).
   - Every stored fixture of **every** version must still decode with the current code, and its CBOR and JSON files must
     agree. Old fixtures are what an older app emitted, so this is the "new Kora reads old Kora" guarantee. A fixture
     whose sample was removed fails too: members of a released version never go away.
   - The current encoding of the **current** version must equal the stored fixture, which catches an accidental wire
     change (a renamed field, a changed default, a serializer upgrade).
   - Every operation and event of a built-in capability must have samples (a new member cannot ship without them).
2. **Forward tolerance**: the encoded samples are edited to look like a future minor (an extra field in every object, an
   unknown enum constant in every enum field) and must read like the originals in both codecs; a peer whose descriptor
   has unknown fields, unknown capabilities and a newer minor routes, calls and subscribes normally. This is the
   "old Kora reads new Kora" guarantee for additive changes.
3. **`NodeDescriptor.koraVersion`** reports the Kora library version (`KoraBuild.VERSION`, generated from the Gradle
   project version) for logs and diagnostics. Never decide compatibility from it; capability versions are the only input.

What this does not do: it never runs two real Kora builds against each other. The old side is represented by the
fixtures its release stored; keep that in mind before relaxing them.

## Cutting a Kora release

1. Finish the contract change. A capability that gained members or fields gets its **minor** bumped
   (`Capability(id, CapabilityVersion(1, 2))`); a breaking change is a new major provided next to the old one.
   Update the version history in the capability's KDoc.
2. Bump `version` in the root `build.gradle.kts` (this is `KoraBuild.VERSION`, the only place the version is written).
3. Add samples for new members and for the new shapes of changed types in `WireSamples` (the coverage test names what
   is missing). If an envelope type gained a field, raise the minor of `WireSamples.ENVELOPE_VERSION`; if a relay frame
   gained a field or a body type, raise the minor of the wear `REVISION` (and `WearProtocol.VERSION` only for a change
   old readers cannot survive). An additive change alters the bytes of the samples, so it always starts a new revision
   rather than touching the stored one.
4. Record the fixtures of the new versions:
   `flock /tmp/sonora-gradle.lock ./gradlew test -Pkora.recordFixtures=true`.
   It writes only the files that do not exist, in `src/test/resources/wire/<group>/<new version>/`, and never
   overwrites. Review them (`git status`) and commit them with the change.
5. Run `flock /tmp/sonora-gradle.lock ./gradlew build`. Existing fixtures must pass untouched. If an old fixture fails,
   the change is breaking: restore the old behaviour or make it a new major.
6. Bump the version, merge to `main` and tag `vX.Y.Z`; the workflow publishes the release. Apps pick it up only when
   they raise their pinned version. See `docs/releasing.md`.

A fixture of a version that was **never released** may be deleted and re-recorded while the change is still in review.
Once a version is released its fixtures are frozen.
