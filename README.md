# Sufyan Harness

**AI Development Workspace for Android**

Turn an Android phone into a practical AI-assisted software development workstation:
projects, a real code editor, a real shell, an AI coding agent that edits your files and
runs your commands, live web preview, diffs and rollback — all on the device.

```
Create Project → Tell AI what to build → AI inspects → AI edits files → AI runs commands
    → AI reads errors → AI fixes → Start server → Live Preview → Review Diff → Accept/Rollback
```

## Features

| | |
|---|---|
| **Projects** | Real workspace on device, typed projects (Android App / Website / Web App / Node / Empty), templates that verify every file they claim, search, sort, storage manager, ZIP + folder import/export |
| **AI Chat** | Streaming replies, stop/retry, a compact activity timeline instead of card-spam, final-answer layer with the files that changed, per-project conversations, real token usage and cost |
| **AI Agent** | 7 real tools (list, read, write, edit, delete, search, run command) sandboxed to one project, a token-budgeted context, an approval gate for destructive actions, and a verification loop that re-runs your real build/test command until it passes |
| **OpenRouter** | Full model catalogue, searchable selector with categories, per-project model, fallback model, configurable endpoint, connection test |
| **Editor** | File tree, tabs, dirty tracking, syntax highlighting, project-wide search, find/replace, undo, AI actions, large-file guard |
| **Terminal** | Multiple named sessions on a genuine shell process, streamed stdout/stderr, exit codes, history, interrupt, and WHAT/WHY/HOW diagnostics for a command that fails |
| **Linux runtime** | PRoot userspace with real probes and a repair flow — and an honest refusal when the build ships no loader |
| **GitHub** | Connect with a token, browse repos, clone a branch into a project, diff against the remote with real blob SHAs, detect conflicts, commit and push. The agent has no GitHub tool, so it can never push for you |
| **Android builds** | Detects the requirements, runs the real Gradle build, verifies the produced APK (manifest, dex, signature, ABIs) and hands it to the system installer |
| **Preview** | Built-in HTTP server on 127.0.0.1 + embedded WebView + console telemetry, framework dev servers, and errors reported straight into the chat |
| **Review & rollback** | Pure-Kotlin unified diffs, per-file review/revert of everything the AI touched, plus full-project checkpoints with atomic rollback |
| **Background work** | Every running task is listed in the app chrome with a Stop action and a foreground-service notification |
| **Offline & recovery** | Network features say "you are offline" instead of timing out, and an interrupted build/install/AI task is reported on the next launch with what to do about it |
| **Security** | Credentials encrypted with the Android Keystore, masked, never logged — see [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md) |

Known limitations are listed honestly in [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md).

## Getting the APK

The APK is built by GitHub Actions (`.github/workflows/android.yml`), which runs on every push:
lint → unit tests → `assembleDebug` → `assembleRelease` → APK verification → upload.

**Actions** tab → latest **Build APK** run → **Artifacts** →
`sufyan-harness-debug-apk` or `sufyan-harness-release-apk`.

(A copy of the pipeline is kept in `ci/android-workflow.yml`; see [`ci/README.md`](ci/README.md).)

> The release APK is signed with the debug key, because no release keystore is available in
> this build environment. Replace `signingConfig` in `app/build.gradle.kts` with a real
> keystore before publishing to a store.

### Build it yourself

Requires JDK 17 and the Android SDK.

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

## Setup

1. Install the APK.
2. **Settings → AI → OpenRouter API key** — paste a key from
   [openrouter.ai/keys](https://openrouter.ai/keys) and tap **Test**.
3. **Settings → AI → Model** — pick a model.
4. **Projects → New Project**, then open **AI Chat** and describe what you want built.

## Honest capability notes

This project follows a strict rule: **nothing reports success unless it actually worked.**

- Tools (git, Node, npm, curl) are listed as available **only** after their probe command
  exits 0 on your device. Stock Android ships none of them.
- The Linux/PRoot runtime is fully implemented but needs a per-ABI `libproot.so` in
  `app/src/main/jniLibs/`, which this build does not bundle. Installation is refused with an
  explanation rather than faked. The Android shell still runs real commands.
- The dev-process preview only reports "running" after a socket probe confirms the port.

See [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) for the full ledger and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for design details.

## Tech

Kotlin • Jetpack Compose • Material 3 • Navigation Compose • OkHttp • kotlinx.serialization •
Coroutines/Flow • EncryptedSharedPreferences — minSdk 26, targetSdk 34.
