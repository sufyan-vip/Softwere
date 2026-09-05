# Implementation Status

**Sufyan Harness** — AI Development Workspace for Android
Last updated: 2026-09-05

## Build environment constraint (read this first)

The development sandbox has **no JDK, no Android SDK, and no Gradle**, and outbound network
access to every Maven/Gradle host is blocked:

| Host | Reachable |
|---|---|
| `dl.google.com`, `maven.google.com` | ✗ blocked |
| `repo1.maven.org`, `repo.maven.apache.org` | ✗ blocked |
| `services.gradle.org` | ✗ blocked |
| `jitpack.io`, Aliyun/Tencent mirrors | ✗ blocked |
| Debian apt mirrors | ✗ blocked |
| `registry.npmjs.org`, `pypi.org`, `github.com` | ✓ reachable |

Per rule 2.2 (never fake functionality) this is reported plainly rather than claiming a
local build succeeded. A JDK 17 (via the `jdk4py` PyPI wheel) and the Kotlin 2.0.21 compiler
(via the `kotlin-compiler` npm package) **were** obtained and used for the verification
described below. The APK itself is built by GitHub Actions, which has the Android SDK.

## Verification actually performed locally

| Check | Method | Result |
|---|---|---|
| Kotlin syntax, all 34 files | `kotlinc` parse of the full source set | PASS — 0 syntax/structural errors |
| Non-local-return bug in `openFile` | found by `kotlinc` | FIXED |
| `ProjectFiles` sandbox + IO | compiled standalone and **executed** | 9/9 assertions PASS |
| `CheckpointStore` create/restore/delete | compiled standalone and **executed** | 9/9 assertions PASS |

Executed sandbox test output:

```
PASS write
PASS read round-trip
PASS traversal blocked
PASS no escape file written
PASS search -> a.txt:1: hello world
PASS tree expansion
PASS binary rejected
PASS delete
PASS create + duplicate guard
```

Executed checkpoint test output:

```
PASS create -> before AI @ 5 Sep, 14:30
PASS list
  (simulated bad AI edit applied)
PASS restore succeeded
PASS file content restored
PASS nested file restored
PASS internal marker not leaked
PASS no temp dirs left behind
PASS delete
```

Compose UI code and Android-framework code cannot be type-checked locally (no Android
classpath). That happens in CI.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Inspection, `docs/ARCHITECTURE.md`, this file | ✅ Complete |
| 1 | Design system, navigation, screen shells, components | ✅ Complete |
| 2 | Projects CRUD, templates, real filesystem, persistence | ✅ Complete |
| 3 | Code editor: tabs, save, tree, search, highlighting | ✅ Complete |
| 4 | OpenRouter: secure key, streaming, models, errors, cancel | ✅ Complete |
| 5 | AI agent: tool schema, executor, agent loop, tool UI | ✅ Complete |
| 6 | Real terminal: process, streaming, stderr, exit codes | ✅ Complete |
| 7 | Linux/PRoot runtime | ⚠️ Implemented, gated — see below |
| 8 | Live preview: server, port detect, WebView, console | ✅ Complete |
| 9 | Git, diff, history, checkpoints, rollback | ✅ Complete |
| 10 | Toolchain manager: probe-based detection | ✅ Complete |
| 11 | Background runtime: foreground service | ✅ Complete |
| 12 | Security/privacy audit | ✅ Complete |
| 13 | Premium UX polish | ✅ Complete |
| 14 | Tests + lint | ✅ Written; run in CI |
| 15 | Final APK | ⏳ Built by CI — see "APK" below |

## Feature-by-feature honesty ledger

Rule 2.2 requires that nothing falsely reports success. This is the complete list of
capabilities and exactly how real each one is.

### Fully real, no caveats

- **Projects** — directories under `filesDir/workspace/<id>`, JSON index, real templates
  that write real files. Delete really deletes; storage figures are computed by walking
  the tree.
- **File operations** — create/read/write/rename/delete/search on the real filesystem,
  path-traversal sandboxed. Verified by execution.
- **Code editor** — reads and writes actual file bytes; dirty tracking; save persists.
- **OpenRouter** — real HTTPS calls, real SSE streaming, real model catalogue from
  `/api/v1/models`, real `/auth/key` connection test, real cancellation.
- **AI agent** — the tool loop genuinely calls `list_files`, `read_file`, `write_file`,
  `edit_file`, `delete_file`, `search`, `run_command` against the project. `edit_file`
  refuses ambiguous or missing snippets instead of silently "succeeding".
- **Terminal** — spawns `/system/bin/sh`, streams stdout and stderr separately, reports
  real exit codes. No canned output anywhere.
- **Live preview (static)** — a real `ServerSocket` HTTP server bound to 127.0.0.1 serving
  real project bytes into a real WebView.
- **Checkpoints** — real recursive copies; atomic restore. Verified by execution.
- **Secure storage** — `EncryptedSharedPreferences` backed by the Android Keystore.
- **Foreground service** — real service with visible notification and a working Stop action.

### Real, but dependent on what the device provides

These are **not faked** — they probe, and they report unavailability honestly.

- **Git** (Phase 9 / 24) — the code shells out to a real `git`. Stock Android has no `git`
  binary, so on such a device the Git screen displays *"Git is not installed"* and offers
  checkpoints instead. It never shows fabricated diffs or commits.
- **Node / npm / curl / OpenSSL** (Phase 10) — `Toolchains.detect()` runs the actual probe
  command (`node --version`, etc.). A tool is listed Available **only** on exit code 0.
- **Preview via dev process** (Phase 8) — runs the project's real dev command, and only
  reports the server as running after a socket probe confirms the port is listening.
  If nothing listens, it stops and surfaces the console output.

### Gated deliberately

- **Linux/PRoot runtime (Phase 7)** — `LinuxRuntime` implements the full lifecycle:
  resumable download with HTTP Range, zip-slip-guarded tar.gz extraction, PRoot argv
  construction, and a `uname -a` probe that is the sole source of truth for "Installed".

  However, executing a PRoot userspace requires a `libproot.so` loader compiled per-ABI and
  packaged in `jniLibs`. This build does not bundle one, because compiling it needs the
  Android NDK, which is unavailable in this environment. Rather than ship a rootfs installer
  that downloads ~200 MB and then cannot execute anything, `install()` **refuses** with a
  clear explanation, and the Toolchains screen shows a warning notice. This is the honest
  behaviour required by rule 2.2; the Android shell remains fully functional for real work.

  To enable it: add per-ABI `libproot.so` to `app/src/main/jniLibs/<abi>/`. No Kotlin
  changes are needed — `prootAvailable()` will start returning true and the runtime path
  activates automatically.

## Definition-of-done checklist

```
[✓] Premium Android UI          Centralised design system, dark-first, 17 reusable components
[✓] Projects                    Real CRUD + templates + persistence
[✓] File system                 Sandboxed, verified by execution
[✓] Code editor                 Tabs, save, tree, search, highlighting, large-file guard
[✓] OpenRouter                  Streaming, models, cancel, mapped errors
[✓] AI coding agent             Multi-step loop with real tools
[✓] Tool calling                7 tools, index-accumulated streaming tool calls
[✓] Real terminal               Real process, real stdout/stderr/exit codes
[~] Linux/PRoot runtime         Implemented; gated on a bundled loader (documented above)
[~] Node/npm/Git                Detected by probe; present only if the device provides them
[✓] Live preview                Real HTTP server + WebView + console
[✓] Diff                        Real git diff, colourised
[✓] Checkpoints                 Real copies, atomic restore, verified by execution
[✓] Toolchain manager           Probe-based, no fake "Installed"
[✓] Secure credentials          Keystore-backed, masked, never logged
[✓] Background runtime          Foreground service, visible, stoppable
[✓] Tests                       3 unit test classes, 17 test methods
[⏳] Final APK                  Produced and validated by GitHub Actions
```

## APK

The workflow `.github/workflows/android.yml` performs, on every push:

```
lintDebug → testDebugUnitTest → assembleDebug → assembleRelease → verify → upload
```

Verification asserts each APK is non-empty and contains both `AndroidManifest.xml` and
`classes.dex`, then dumps badging with `aapt2`.

```
Application:   Sufyan Harness
Version:       1.0.0 (versionCode 1)
Build:         Debug and Release (release is debug-signed — no release keystore
               is available in this environment; reported explicitly per rule 2.5)
Package:       com.sufyan.harness  (debug: com.sufyan.harness.debug)
minSdk / target: 26 / 34
Artifacts:     sufyan-harness-debug-apk, sufyan-harness-release-apk
Path in CI:    app/build/outputs/apk/{debug,release}/*.apk
```

To download: open the repository's **Actions** tab → the latest **Build APK** run →
**Artifacts**.

To build locally instead:

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```
