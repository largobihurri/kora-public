# Common signing key

Every Bihurri app (Sonora, Kardio, KOKO and future apps, phone and watch) is signed with one shared key.
Kora relies on it: peers protect their node service with the `signature` permission
`eus.bihurri.kora.permission.PEER`, which Android grants only to apps signed with the same certificate.

## Where the key lives

Outside every repository, on each build machine:

```
~/.bihurri/signing/bihurri.jks          # the keystore
~/.bihurri/signing/signing.properties   # storePassword, keyAlias, keyPassword
```

`signing.properties`:

```properties
storeFile=/home/<user>/.bihurri/signing/bihurri.jks
storePassword=<secret>
keyAlias=bihurri
keyPassword=<secret>
```

Both files are secrets: `chmod 600`, never commit them, and copy them between build machines over a secure channel
(the same key must exist on every machine that builds an app). Keep an offline backup: losing the key means no app
can be updated in place again.

## Creating the key (once, by the owner)

```bash
mkdir -p ~/.bihurri/signing && chmod 700 ~/.bihurri ~/.bihurri/signing
keytool -genkeypair -v -keystore ~/.bihurri/signing/bihurri.jks -alias bihurri -keyalg EC -groupname secp256r1 -validity 36500 -dname "CN=Bihurri"
```

`keytool` asks for the passwords interactively; write them into `signing.properties` afterwards.

## Wiring an app (task T5)

Each app's build reads `~/.bihurri/signing/signing.properties` (overridable with the Gradle property
`bihurri.signing.properties`) and signs **every** variant — debug, benchmark, nightly, release — and both the phone and
the watch module with it. When the file is missing the build falls back to the default debug key and logs a warning,
so a machine without the key still builds, but that APK cannot join the Kora mesh.

## Switching an installed app

An installed app cannot be updated with a different certificate: the first install with the common key requires an
uninstall, which deletes the app's data. Back up first (Sonora: library, settings and converted files; Kardio: recorded
sessions). Do it once per app and device, phone and watch together.
