# Release signing secrets

The `Release APK` workflow signs with the same keystore you use locally. It
reads the four env vars that `app/build.gradle.kts` already falls back to when
`local.properties` is absent (which it always is in CI, since it's gitignored).

## Repository secrets to create

Settings → Secrets and variables → Actions → **New repository secret**:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | base64 of `keystore.jks` (see below) |
| `RELEASE_STORE_PASSWORD` | `release.storePassword` from your `local.properties` |
| `RELEASE_KEY_ALIAS` | `release.keyAlias` from your `local.properties` |
| `RELEASE_KEY_PASSWORD` | `release.keyPassword` from your `local.properties` |

The keystore is binary, so it has to be base64-encoded to survive a secret
field. From the repo root:

```sh
base64 -w0 keystore.jks | xclip -selection clipboard   # or: | wl-copy, | pbcopy
```

`-w0` matters — without it `base64` wraps at 76 columns and the decode in CI
still works, but only because `base64 -d` tolerates newlines. Keep it on one
line anyway.

You can also set all four at once with the GitHub CLI:

```sh
gh secret set RELEASE_KEYSTORE_BASE64 < <(base64 -w0 keystore.jks)
gh secret set RELEASE_STORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

(The last three prompt for the value, so it never lands in your shell history.)

## What the workflow does

- **Push to `main`** — builds a signed release APK and attaches it to the
  workflow run as an artifact, kept for 90 days.
- **Push a `v*` tag** — same build, plus a GitHub Release with the APK attached
  and auto-generated notes.
- **Manual run** — the `Run workflow` button, same as a push to `main`.

The keystore is decoded to `$RUNNER_TEMP` (outside the checkout) and deleted
after the build, so it can't end up inside an uploaded artifact.

## Debug APKs on pull requests

`PR Debug APK` (`.github/workflows/pr-debug-apk.yml`) runs `testDebugUnitTest`
on every PR targeting `main`, then — only if the tests pass — `assembleDebug`,
attaching the APK to the run for 14 days. Grab it from the run's **Artifacts**
section to test a branch on a device. When the tests fail there's no APK, and
Gradle's HTML report is uploaded as `unit-test-report` instead.

It needs none of the secrets above — debug builds sign with the committed
`app/debug.keystore` (below), so it also works on PRs from forks, where secrets
aren't available.

Debug and release install side by side (`applicationId` suffix `.debug`), and
the debug launcher icon reads **RomMDroid (Beta)** so they're tellable apart.
The label comes from `app_launcher_name`, a `resValue` the `debug` build type
overrides in `app/build.gradle.kts`.

## The committed debug keystore

`app/debug.keystore` is in the repo on purpose, wired up as the `debug`
`signingConfig` in `app/build.gradle.kts`. It uses the Android debug defaults:

| | |
| --- | --- |
| Alias | `androiddebugkey` |
| Store / key password | `android` |
| DN | `CN=Android Debug, O=Android, C=US` |
| SHA-256 | `78:51:DB:42:...:CE:3A:11:6A` |
| Expires | 2056-08-28 |

Without it, each machine and each CI runner signs with its own auto-generated
`~/.android/debug.keystore`, so a debug APK from CI won't install over one you
built locally — `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Sharing one key means any
debug APK upgrades any other, wherever it was built.

It is not a secret and guards nothing: the passwords are public, it can't sign
a release, and Play won't accept it. Do not reuse it for anything else.

**One-time step:** a debug build installed before this key existed was signed
with your old local key, so the first new build won't install over it. Run
`adb uninstall app.rommdroid.debug` once. Release installs are unaffected.

## Version codes

`versionCode` comes from `github.run_number` in CI, so every build gets its own
monotonically increasing code. Locally it falls back to `1`, or you can force a
value with `-PbuildNumber=N`:

```sh
./gradlew assembleRelease -PbuildNumber=7
```

`versionName` is still hand-edited in `app/build.gradle.kts`.

## Losing the keystore

Back up `keystore.jks` somewhere outside this machine. Android identifies an
app by its signing certificate; if the key is lost, no future build can upgrade
an already-installed copy — users have to uninstall first.
