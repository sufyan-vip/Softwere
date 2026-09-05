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
| **Projects** | Real workspace on device, templates for Empty / HTML-CSS-JS / Node / React, search, sort, storage stats |
| **AI Chat** | Streaming replies, stop/retry, tool-activity cards, code blocks, per-project conversations |
| **AI Agent** | 7 real tools: list, read, write, edit, delete, search, run command — sandboxed to one project |
| **OpenRouter** | Full model catalogue, searchable selector with categories, per-project model, connection test |
| **Editor** | File tree, tabs, dirty tracking, syntax highlighting, project-wide search, large-file guard |
| **Terminal** | Genuine `/system/bin/sh` process, streamed stdout/stderr, exit codes, history, interrupt |
| **Preview** | Built-in HTTP server on 127.0.0.1 + embedded WebView + console telemetry |
| **Git** | Status, colourised diff, history, commit — when a `git` binary is present |
| **Checkpoints** | Full-project snapshots with atomic rollback; works with no git at all |
| **Security** | API key encrypted with the Android Keystore, masked, never logged |

## Getting the APK

Built automatically by GitHub Actions on every push.

**Actions** tab → latest **Build APK** run → **Artifacts** →
`sufyan-harness-debug-apk` or `sufyan-harness-release-apk`.

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
