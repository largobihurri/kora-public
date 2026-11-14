# Common signing key

Every Bihurri app (Sonora, Kardio, KOKO and future apps, phone and watch) is signed with one shared key.
Kora relies on it to tell peers from everybody else, and it does so in code, not with a manifest permission:

- The node service (`KoraNodeService`) is exported and declares no permission. For each Binder transaction the
  caller is identified from the uid the kernel attached to it and from the signing certificate of that uid
  (`CallerIdentifier`): the app itself is `SELF`, the adb shell is `SHELL`, an app signed with the same certificate
  as this one is `PEER`, and any other app is `EXTERNAL_APP`, bound to the digest of its certificate. A caller that
  cannot be identified is refused.
- The node's caller policy then decides, per scope, what each kind of caller may do. By default only `SELF` and
  `PEER` are served; the shell and external apps need the app's external access switch and a grant.
- Discovery uses the same check: an app lists as peers only the installed packages that serve a Kora node and are
  signed with its own certificate.

An app signed with another key is therefore never a peer: it is not discovered, and its calls arrive as an external
app.

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
