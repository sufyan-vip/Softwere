# Sufyan Harness — Architecture

**AI Development Workspace for Android**

## Phase 0 — Repository inspection findings

The repository at the start of this build contained exactly two files:

```
README.md                                 (1 line: "# Softwere")
Sufyan_Harness_Master_Build_Prompt_v2.md  (the master specification)
```

There was **no** existing Gradle build, AndroidManifest, SDK configuration, dependency
declaration, source tree, native code, test suite, or UI. Nothing existed that could be
destroyed, so rule 2.3 ("do not destroy existing work") was satisfied by preserving both
files unchanged and adding the Android project alongside them.

## Module layout

Single-module Android application — appropriate for the scope, and it keeps build times
tolerable. Package-level separation enforces the layering.

```
app/
  src/main/java/com/sufyan/harness/
    HarnessApp.kt            Application: owns singletons, notification channel
    MainActivity.kt          Single activity, Compose entry point
    HarnessViewModel.kt      Single shared VM: projects, editor, chat, terminal, git
    ui/
      theme/       Design system: colours, spacing, radius, typography
      components/  Reusable primitives (AppTopBar, HarnessCard, StatusChip, ...)
      HarnessRoot.kt         Navigation graph + bottom bar
      projects/    Projects dashboard, project creation, project settings
      chat/        AI chat + model selector
      terminal/    Real shell UI
      editor/      File tree, tabs, code editor
      preview/     WebView live preview
      git/         Status, diff, history, checkpoints
      github/      GitHub connect, repos, push/pull, conflicts
      build/       Android build + APK artifacts
      review/      Per-file review and revert of AI changes
      settings/    Global settings, toolchain manager, storage manager
    data/
      Workspace.kt     Project CRUD on the real filesystem + templates + ProjectScaffold
      FileTree.kt      Sandboxed file operations (ProjectFiles)
      SecureStore.kt   Keystore-backed credential storage
      Settings.kt      Non-secret preferences
      DiffEngine.kt    Pure-Kotlin LCS unified diff (no git binary needed)
      ProjectArchive.kt Zip/source/production/selection export + import
    ai/
      AiProvider.kt        Provider-agnostic interface + DTOs
      OpenRouterProvider.kt SSE streaming, error mapping, model list
      AgentTools.kt        Real tool implementations + the §48 approval gate
      Agent.kt             Agent loop (model -> tools -> verify -> model)
      AgentContext.kt      Token budgeting and history pruning
      CommandPlanner.kt    Evidence-backed command detection
    runtime/
      ShellSession.kt  Interactive shell + one-shot exec
      LinuxRuntime.kt  PRoot userspace, resumable install
      Toolchains.kt    Probe-based tool detection
      DevServer.kt     Static HTTP server + dev-process supervision
      GitService.kt    Git wrapper + CheckpointStore
      ChangeTracker.kt Session baseline, per-file review and revert
      TerminalSessions.kt Multiple named shells
      CommandDiagnostics.kt Exit code -> WHAT / WHY / HOW + fix actions
      EnvHealth.kt / RuntimeRepair.kt  Real probes, honest blockers
      GitHubService.kt / ProjectSync.kt GitHub REST + blob-SHA diffing
      AndroidBuildService.kt / ApkVerifier.kt Gradle build + APK validation
      TaskRegistry.kt  Every background task, drives the task strip
      Connectivity.kt  §55 offline state
      Recovery.kt      §56 interrupted-operation markers + scratch sweep
      RuntimeService.kt Foreground service
```

## Data flow

```
UI (Compose)
  ↓ events
HarnessViewModel  ── StateFlow ──▶ UI
  ↓
Agent ──▶ AiProvider ──▶ OpenRouterProvider ──▶ OkHttp (SSE stream)
  ↓                                                  │
AgentTools                                    StreamEvent.Text / Tools
  ↓
ProjectFiles / ShellSession  (real filesystem, real processes)
```

## Key design decisions

### Sandboxing
`ProjectFiles.resolve()` canonicalises every path and throws `SecurityException` if the
result escapes the project root. Neither the user nor the AI agent can touch anything
outside the active project. This is unit-tested and was additionally verified by executing
the compiled class (see IMPLEMENTATION_STATUS.md).

### Honesty about capability
Where a capability depends on something the device may not provide (a PRoot loader, Node,
git), the code **probes** rather than assumes:

- `Toolchains.detect()` marks a tool available only when its probe command exits 0.
- `LinuxRuntime.refresh()` marks the runtime Installed only after `uname -a` runs inside it.
- `LinuxRuntime.install()` **refuses** to download a rootfs when no PRoot loader is bundled,
  rather than downloading hundreds of MB and reporting a fake success.
- `DevServer.startProcess()` only reports a running server after a socket probe confirms
  something is listening on the port.

### Streaming and cancellation
OpenRouter responses are consumed as SSE line-by-line and emitted as `StreamEvent`s on
`Dispatchers.IO`. The agent job is a `viewModelScope` coroutine, so Stop cancels the
in-flight HTTP read immediately and the UI leaves partial text in place.

### Tool-call accumulation
OpenAI-compatible streaming splits tool calls across deltas, keyed by `index`. The provider
accumulates name/id/arguments fragments per index and only emits `StreamEvent.Tools` once
the stream completes, which avoids executing a tool with truncated JSON arguments.

### Rollback safety
`CheckpointStore.restore()` never deletes the live tree before the replacement is ready:
it stages a full copy, renames the current project to `.prev`, swaps in the staged copy, and
only then deletes `.prev`. If the swap fails the original is renamed back. Verified by
execution.

### Memory discipline on phones
- Terminal output is bounded to 3000 lines (`ShellSession.maxLines`).
- Preview console is bounded to 300 lines.
- Editor refuses files over 2 MB and binary files rather than OOMing.
- Tool payloads sent back to the model are truncated (12 KB) and in the UI (4 KB).
- The replayed conversation is pruned to a token budget (`AgentContext`, default 24k) instead of
  growing without bound.
- Build logs keep the last 500 lines on screen; project sync skips files over 5 MB.

## Security posture

| Concern | Handling |
|---|---|
| API key at rest | `EncryptedSharedPreferences` + Android Keystore (AES256-GCM) |
| API key in UI | Masked input; only ever shown as `sk-or-•••••••• last4` |
| API key in logs | Never logged; no analytics in the app at all |
| Path traversal | Canonical-path check in `ProjectFiles.resolve()` |
| Tar extraction | Entry paths validated against the rootfs root (zip-slip guard) |
| Cleartext HTTP | Disabled except for `127.0.0.1`/`localhost` (preview) |
| Agent blast radius | Tools are constructed against one project directory only |
| Background work | Foreground service with visible state and a Stop action |
| GitHub token | Same encrypted store; sent only in an `Authorization` header, never in a command line or URL |
| Destructive agent actions | Gated in `AgentTools.gate()`; refused outright when no approval channel exists |
| GitHub from the agent | Impossible by construction — there is no GitHub tool (§33) |
| Backups | `android:allowBackup="false"`, so the encrypted store cannot be pulled with `adb backup` |

The full §53 audit, including accepted risks, is in [`SECURITY_AUDIT.md`](SECURITY_AUDIT.md).

## Build and delivery

The sandbox this project was developed in has **no JDK, no Android SDK, and no network
access to `dl.google.com`, `maven.google.com`, `repo1.maven.org` or `services.gradle.org`**,
so the APK cannot be compiled locally. The APK is therefore produced by
`ci/android-workflow.yml` (see `ci/README.md` to activate) on GitHub-hosted runners, which do have the Android SDK.
The workflow runs lint, unit tests, `assembleDebug` and `assembleRelease`, validates each
APK (non-empty, contains `AndroidManifest.xml` and `classes.dex`), and uploads both as
artifacts.
