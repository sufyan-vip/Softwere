# Getting the APK

The APK is produced by the repository's GitHub Actions workflow, which is the only place in this
project with a real Android SDK. Nothing here is a mock-up: the workflow runs `assembleRelease` and
`assembleDebug`, then opens each `.apk` and checks it is non-empty and contains both
`AndroidManifest.xml` and `classes.dex` before uploading it.

## Latest verified build

| | |
| --- | --- |
| Run | <https://github.com/sufyan-vip/Softwere/actions/runs/34017467247> |
| Commit | `HEAD` on `arena/01a07329-softwere` — crash fix, cloud build, cloud shell, agent step budget |
| Release APK | `app-release.apk` — 12 MB (artifact `sufyan-harness-release-apk`, 11,987,234 bytes zipped) |
| Debug APK | `app-debug.apk` — 18 MB (artifact `sufyan-harness-debug-apk`, 18,382,876 bytes zipped) |
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

- Release: <https://nightly.link/sufyan-vip/Softwere/actions/runs/34017467247/sufyan-harness-release-apk.zip>
- Debug: <https://nightly.link/sufyan-vip/Softwere/actions/runs/34017467247/sufyan-harness-debug-apk.zip>

## What changed since the first APK

The first build crashed on every launch after the first one (`Cannot create an instance of class
HarnessViewModel`). That is fixed in this build — see the *Post-release fix* section of
[V3-PROGRESS.md](V3-PROGRESS.md). If you already installed the earlier APK, install this one over
it; no need to clear data, and the app will now also show you the log of any crash it hits.

## Building an APK *from* the app (cloud build)

The Build APK screen will always show JDK 17, Gradle and the Android SDK as missing on a phone, and
that is not a bug that can be fixed: Android ships no JVM, and Google publishes `aapt2`, `zipalign`
and `apksigner` only for x86_64 desktops, so a Gradle/AGP build genuinely cannot run on an ARM
device. Instead the app builds on GitHub's machines and brings the APK back:

1. **Settings → GitHub** — connect a personal access token with the `repo` **and `workflow`** scopes.
2. **GitHub screen** — link the project to a repository (or create one) and pick a branch.
3. **Build APK → Build in the cloud** — tap *Cloud debug* or *Cloud release*.

What actually happens, in order: local changes are pushed; a small workflow
(`.github/workflows/sufyan-harness-build.yml`) is added to *your* project the first time; the run is
dispatched and then followed step by step in the UI; when it succeeds the artifact is downloaded,
unzipped, checked by `ApkVerifier` (manifest + dex + signature), and only then does it appear in the
APK list with **Install**. If any of that fails you get the reason and a link to the run — never a
fake success.

## Terminal packages, honestly

`apt`, `pkg`, `npm -g`, `pip` cannot install anything inside this app, and no build of it can change
that: an app targeting a modern API level may not execute binaries from its own data directory, and
this build ships no PRoot loader. So the Terminal has a **cloud shell**: tap the cloud icon in the
Terminal's top bar and the command you type runs in your linked GitHub repository on Ubuntu — where
node, npm, python, java, git and `sudo apt-get` all exist — and the real output, exit code included,
comes back into the app.

## Agent step budget

A turn no longer dies at "12 steps reached":

- **Settings → Agent steps per turn** (4-100, default 12) sets the budget.
- **Settings → Continue automatically** (on by default) resumes the turn by itself when the budget
  runs out, up to 5 times, and says so in the chat each time.
- If it does stop, the message says *paused, not failed*, and the Continue button carries on.

## Using a free AI provider instead of OpenRouter

The app talks plain OpenAI-compatible HTTP, so any provider that speaks it works. **Settings → AI**
has one-tap endpoint presets — OpenRouter, Groq, Gemini, Cerebras, Mistral, GitHub Models — and then
you paste *that provider's* key and type one of *its* model ids. Two things were fixed to make this
real rather than theoretical:

- **Test connection** no longer depends on `/auth/key` (an OpenRouter-only route). If that route is
  absent it proves the key the portable way, with an authenticated `GET /models`.
- **The model list** now sends the API key, which every provider except OpenRouter requires, and
  reports 401/404 distinctly instead of a generic failure.

Nothing here is unlimited — free tiers are per-minute and per-day capped. Truly unlimited means a
model running on your own hardware.

## When a cloud build fails

The app does what a developer would do: it downloads the run's log bundle, strips the timestamps and
stack frames, and shows the compiler's own lines (`e: ...`, `FAILURE:`, `Execution failed for task`).
Under them is **Ask the AI to fix it**, which opens the chat with those exact lines as the task, so
the agent reads the real error instead of guessing at it. Nothing is summarised by the app.

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
