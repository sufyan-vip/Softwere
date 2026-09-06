# Getting the APK

The APK is produced by the repository's GitHub Actions workflow, which is the only place in this
project with a real Android SDK. Nothing here is a mock-up: the workflow runs `assembleRelease` and
`assembleDebug`, then opens each `.apk` and checks it is non-empty and contains both
`AndroidManifest.xml` and `classes.dex` before uploading it.

## Latest verified build

| | |
| --- | --- |
| Run | <https://github.com/sufyan-vip/Softwere/actions/runs/34002043430> |
| Commit | `36d75d0` on `arena/01a07329-softwere` |
| Release APK | `app-release.apk` — 12 MB (artifact `sufyan-harness-release-apk`, 11,944,308 bytes zipped) |
| Debug APK | `app-debug.apk` — 18 MB (artifact `sufyan-harness-debug-apk`, 18,315,265 bytes zipped) |
| Verification | `PASS: ... is a valid Android package` for both files |
| Application id | `com.sufyan.harness` (debug variant: `com.sufyan.harness.debug`) |
| Version | 1.0.0 (versionCode 1) |
| minSdk / targetSdk | 26 / 34 |
| ABIs | universal — the app ships no native libraries of its own |

## Download

1. Open the run page above and scroll to **Artifacts**.
2. Download **sufyan-harness-release-apk** (or the debug one) — GitHub serves it as a `.zip`.
3. Unzip it; inside is `app-release.apk`.

A direct, login-free link for the same artifacts (the repository is public):

- Release: <https://nightly.link/sufyan-vip/Softwere/actions/runs/34002043430/sufyan-harness-release-apk.zip>
- Debug: <https://nightly.link/sufyan-vip/Softwere/actions/runs/34002043430/sufyan-harness-debug-apk.zip>

## Install on a phone

```
adb install -r app-release.apk
```

Or copy the `.apk` to the device and open it; Android will ask permission to install from this
source the first time.

## Honest note about signing

`release` is signed with the **debug** keystore, because this repository contains no private
release key (committing one would be a security defect). The APK installs and runs, but it is not
Play-Store publishable as-is. To ship it, add a real keystore as encrypted CI secrets and point
`signingConfigs.release` at it.

## Building it yourself

With Android Studio, or on any machine with JDK 17 and the Android SDK:

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```
