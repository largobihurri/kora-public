# Releasing Kora

Apps depend only on **published, stable Kora releases with an exact version**. A release is a Git tag `vX.Y.Z` on
`main`; a GitHub Actions workflow builds it and publishes the libraries to GitHub Packages (private registry of
`largobihurri/kora`).

## Version policy

The library follows semantic versioning. The version is written once, in the root `build.gradle.kts`, and also feeds
`KoraBuild.VERSION`.

| Change | Bump |
|---|---|
| Breaking Kotlin API, or a capability major dropped | major |
| Additive: new capability, new capability minor, new API | minor |
| Fixes with no API or wire change | patch |

Capability versions (`media.library@1.1`) are separate: they version the wire contract and follow
`docs/compatibility.md`. A library release can carry several capability changes; apps never decide compatibility from
the library version.

## Cut a release

1. Finish the contract change and its wire fixtures as described in `docs/compatibility.md` (steps 1 to 5).
2. Bump `version` in the root `build.gradle.kts` on a feature branch and open a pull request. CI (`ci.yml`) runs
   `./gradlew build` on it.
3. Merge the pull request into `main`.
4. Tag the merge commit and push the tag:

   ```sh
   git checkout main && git pull
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

5. The `release.yml` workflow then:
   - fails unless the tag without `v` equals the Gradle `version`;
   - runs `./gradlew build`;
   - publishes every library module as `eus.bihurri.kora:<module>:X.Y.Z` with sources and POM metadata
     (`publishAllPublicationsToGitHubPackagesRepository`; see [Published modules](#published-modules));
   - builds `:kora-bridge:distZip` and creates the GitHub Release for the tag with that zip attached
     (`kora-bridge` is a PC tool, not a Maven artifact).

Test fixtures of `kora-contract` are for this build's own tests and are never published.

### Published modules

A module is published if and only if it applies the `kora.publishing` convention plugin, and the release publishes
all of them in one run, so a new library module needs no change to the workflow. Today these are `kora-contract`,
`kora-core`, `kora-mcp`, `kora-mcp-http`, `kora-android`, `kora-tailnet`, `kora-wear-protocol` and `kora-wear`.
`kora-bridge` (PC tool, shipped as a zip) and the `samples` never publish. To list the current set:

```sh
grep -l 'kora.publishing' */build.gradle.kts
```

## Versions are immutable

Never move a tag, re-tag, delete a published version or publish the same version twice (GitHub Packages refuses it
anyway). Apps pin exact versions, so a changed release would silently change what they ship. A broken release is fixed
by a new patch version; if the broken one must not be used, say so in its GitHub Release notes.

The modules are uploaded one after another, so a release job that fails half way leaves an incomplete version
(some modules exist, others do not). An incomplete version can never be resolved by an app, so do not retry or
complete it: bump the patch version and release again, and mention the skipped version in the next release notes.

## Check what a release contains

Without credentials or network, publish to the local staging repository and inspect it:

```sh
flock /tmp/sonora-gradle.lock ./gradlew publishAllPublicationsToStagingRepository
find build/staging-repo -type f        # .pom, .module, .jar/.aar and -sources.jar per module
```

`publishAllPublicationsToGitHubPackagesRepository` is the real publish. It reads `GITHUB_ACTOR` and `GITHUB_TOKEN`
(or the Gradle properties `gpr.user` and `gpr.key`); the workflow provides them. Do not publish by hand from a laptop.

## Consume Kora from an app

In the app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/largobihurri/kora") {
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
            content { includeGroup("eus.bihurri.kora") }
        }
    }
}
```

In the module that uses it, with an **exact** version (no ranges, no `+`, no `latest`):

```kotlin
dependencies {
    implementation("eus.bihurri.kora:kora-android:X.Y.Z")
}
```

Pick the modules the app needs (`kora-android`, plus `kora-wear` for a watch app and `kora-tailnet` for agent access
over the tailnet); their other Kora dependencies come transitively at the same version. Updating Kora in an app is a deliberate change of that version number.

### Token

GitHub Packages needs a token even to read a private package: a GitHub token with the `read:packages` scope.

```sh
gh auth refresh -s read:packages      # once; then use the token of `gh auth token`
```

Put the user and token in `~/.gradle/gradle.properties` on each machine, never in a repository:

```properties
gpr.user=<github user>
gpr.key=<token with read:packages>
```

In an app's own CI, use `GITHUB_ACTOR` and a token that can read the Kora packages (the default `GITHUB_TOKEN` of
another repository cannot, so use a personal token stored as a secret).

### Composite build

`includeBuild("../KORA")` still works for occasional joint development of Kora and an app on one machine. Never use it
for a build that ships: a shipping build resolves the published, exact version.

## Public mirror

From 1.2.1 every release is also published openly, for apps whose source is public (Sonora, GPL-3.0): a clean copy of
the release commit goes to the public repository `largobihurri/kora-public` (no history, no internal notes) and its
libraries to a static Maven repository on that repository's GitHub Pages, `https://largobihurri.github.io/kora-public/maven`,
which needs no login. GitHub Packages cannot serve them openly: a Maven package there takes the visibility of the
repository it was published from, and these coordinates belong to this private repository.

```sh
tools/publish-source/export.sh v1.2.1 <empty dir>     # the clean copy, for kora-public's main
./gradlew publishAllPublicationsToStagingRepository     # in that copy: build/staging-repo is the Maven tree
```

The Maven tree is added under `maven/` on kora-public's `gh-pages` branch, next to the earlier versions; versions there
are immutable too. An app reads it with:

```kotlin
maven("https://largobihurri.github.io/kora-public/maven") { content { includeGroup("eus.bihurri.kora") } }
```

