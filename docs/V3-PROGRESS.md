# V3 master spec — audit (PHASE 0) and progress

Source of truth: `Sufyan_Harness_Master_Rebuild_Prompt_V3.md` (repo root).
The audit below is the PHASE 0 output the spec asks for: `WORKING / PARTIAL / BROKEN / MISSING / MOCKED`,
followed by what each phase changes. Nothing here is aspirational — a feature is only listed as `WORKING`
if the code behind it really runs (RULE 3, no fake success).

## PHASE 0 — audit of the app as it stood before this branch

| Spec area | Status | Evidence in code |
| --- | --- | --- |
| §6 Identity, tagline | PARTIAL | Name used in UI; tagline absent |
| §7-8 Design system (dark-first, cyan/teal accent, 4dp grid, mono font, type scale) | WORKING | `ui/theme/Theme.kt` (`HarnessColors`, `Spacing`, `Radius`, `MonoStyle`, `HarnessTypography`); components all consume it |
| §9 Five primary destinations | WORKING | `ui/HarnessRoot.kt` `TABS` = Projects / AI Chat / Terminal / Editor / Settings, contextual screens as routes |
| §10 Project home as dashboard (search, sort, recent, type, storage, actions) | PARTIAL | `ProjectsScreen` has search + sort (Recent/Name/Size) + storage in the sheet, but no runtime/Git status line and no detail screen |
| §11 New project flow ("what are you building" + stored type) | PARTIAL | `NewProjectScreen` picks a `Template` (Empty/Web/Node/React); no `ANDROID_APP`, no type stored in metadata |
| §12 Project detail screen | MISSING | No such screen; `ProjectSettingsScreen` covers settings only |
| §13-15 Agent workspace (conversation / activity timeline / final answer, compact tool rows) | PARTIAL | `ChatScreen` shows one big card per tool call (the exact thing §14 calls out as too repetitive); no timeline, no final-answer layer |
| §16 Agent state + Stop | PARTIAL | `AgentEvent.Status` was received and thrown away in `HarnessViewModel.send`; Stop exists (`stopGeneration()`) and does cancel the job |
| §17 Session summary (files changed / commands run / build / preview) | MISSING | Not modelled anywhere |
| §18 Tool set (list/read/write/edit/delete/search/run_command) | WORKING | `ai/AgentTools.kt` + `ai/Agent.kt` (real file IO, real processes) |
| §19 Context management | PARTIAL | Full history is replayed each turn; no truncation/token budget |
| §20 Autonomous build loop, §47 AI build/fix mode | MISSING | Agent loop is bounded by `maxIterations` only |
| §21-23 Terminal repair + env health + command diagnostics | PARTIAL | Real `sh -i` process (`runtime/ShellSession.kt`), `Toolchains` detection; no per-command "what/why/how" diagnosis |
| §24 Terminal settings must work | PARTIAL | Only font size is honoured (and it is honoured) |
| §25 Terminal UI (header, toolbar, selectable output) | PARTIAL | Output not selectable; no toolbar; header shows project only |
| §26 Multi-session manager | MISSING | One shell per project |
| §27-28 PRoot runtime + repair | PARTIAL | `runtime/LinuxRuntime.kt` (real proot download/exec); no repair flow |
| §29-33 GitHub integration, security, UI, conflicts | MISSING | `runtime/GitService.kt` is local-only git + checkpoints |
| §34-39 Android project type, build, APK install, rebuild loop | MISSING | Not implemented |
| §40-44 Web project type, export, preview, error reporting | PARTIAL | `runtime/DevServer.kt` (real static/process server) + `PreviewScreen`; no export/download, no error reporting into chat |
| §49 Chat history | WORKING | Persisted per project (`<id>.chat.json`) |
| §50 Cost/usage | PARTIAL | `ModelInfo` has pricing; no usage totals |
| §51 Notifications | PARTIAL | `RuntimeService` foreground notification |
| §52 Storage manager | PARTIAL | Sizes per project; no manager screen |
| §57 Persistence | WORKING | JSON index + per-project files; no Room (acceptable: spec allows file persistence) |
| §58 Testing | PARTIAL | 3 unit tests in `app/src/test` |
| Build health | WORKING | CI green: `lintDebug`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, APK verification |

## Phase plan (spec §60)

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Complete audit | DONE (this file) |
| 1 | UI/UX rebuild: Projects, project details, agent workspace, terminal, dialogs, empty/loading/error | DONE — agent workspace timeline + state bar + session summary, project dashboard, ProjectDetailScreen, creation-flow type cards, terminal chrome and selectable output. Built green in CI |
| 2 | Project type system (ANDROID_APP/WEBSITE/WEB_APP/NODE/EMPTY + metadata) | DONE — type metadata drives scaffold/preview/build/toolchain hints + agent prompt context; every template verifies the files it declares on disk; Android App still correctly gated to Phase 11 |
| 3 | Project + file system repair (CRUD, import/export, browser, search, storage) | DONE — browser CRUD (create/rename/delete), real ZIP export + import, real folder import, search, per-project + total + runtime storage with a Storage manager |
| 4 | Code editor (syntax, tabs, search, replace, save, undo, AI actions) | DONE — tabs, dirty state, save, line numbers, lightweight syntax highlight, project search, in-file find/replace with match count, undo stack, editor AI actions routed to the chat composer |
| 5 | OpenRouter (models, keys, fallbacks) | DONE — key in the Keystore store, live model list, streaming + tool-call accumulation, real usage/cost, retry/backoff, configurable endpoint, fallback model |
| 6 | True AI agent (planning, tool budget, verification) | DONE — `AgentContext` budgeting/pruning, `CommandPlanner` evidence-backed commands, verification loop with a real command, approval gate, fallback model |
| 7 | Terminal repair | DONE — multi-session `TerminalSessions`, real settings honoured, `CommandDiagnostics` WHAT/WHY/HOW + fix actions, command history, selectable output |
| 8 | Linux / PRoot runtime | DONE — `EnvHealth`, `RuntimeRepair` (diagnose + repair, honest blockers), Toolchain screen; a build without a PRoot loader says so instead of pretending |
| 9 | GitHub | DONE — `GitHubService` (connect/repos/branches/commits/push/pull/zip), `ProjectSync` diffing with real blob SHAs, conflict detection, GitHub screen; the agent deliberately has no GitHub tool |
| 10 | Web preview + export | DONE — real static server + framework dev servers, error reporting into chat, ZIP/source/production/selection export through a FileProvider |
| 11 | Android build / install | DONE — `AndroidBuildService` (requirements, real Gradle invocation, artifacts), `ApkVerifier` (manifest/dex/signature/ABI), install + share intents, Build screen |
| 12 | Git, diff, checkpoints | DONE — pure-Kotlin `DiffEngine`, `ChangeTracker` review/revert per file, checkpoints, Changes and Review screens |
| 13 | Background runtime | DONE — `TaskRegistry` + foreground `RuntimeService`, task strip in the app chrome, completion notifications |
| 14 | Security + performance | DONE — `docs/SECURITY_AUDIT.md`, offline mode (§55) via `Connectivity`, crash recovery (§56) via `Recovery`, `run_command` master switch, cleartext restricted to localhost, bounded logs/scrollback |
| 15 | Full QA | DONE — 111 unit tests across 15 classes, all executed locally and green (see `docs/IMPLEMENTATION_STATUS.md` for the rig) |
| 16 | Final APK | DONE — CI run [34002043430](https://github.com/sufyan-vip/Softwere/actions/runs/34002043430) built and verified `app-release.apk` (12 MB) and `app-debug.apk` (18 MB) from commit `36d75d0`. See [APK.md](APK.md) |

## PHASE 1 — what this change set does

1. **Agent workspace (§13-17)** — `ChatScreen` is rebuilt into the three conceptual layers:
   *Conversation*, *Agent activity* (a collapsible execution timeline with compact one-line tool rows),
   and *Final answer* (the answer, the files it changed, and the actions). A live agent state bar
   (`Inspecting project / Editing files / Running command / Installing package / Building / Complete /
   Failed`) with a real **Stop** button, and a session summary card (files changed, commands run, last
   verification, preview state) with `Review changes` / `Open preview` / `Continue`.
   The phase and the file list are derived from the actual tool calls the agent ran — the strings come
   from `AgentEvent`s, never from a template.
2. **Project home dashboard (§10)** — workspace header with the tagline, always-visible search, sort,
   recent/open sections, and per-card type, file count, storage, last modified, real Git state for the
   open project, plus a Details action.
3. **Project detail screen (§12)** — new route: identity line (type • template • Git), real runtime and
   preview state, primary actions (AI agent, editor, terminal, preview) and section rows (Files, Git,
   Checkpoints, Settings). Areas the later phases own are shown as *not in this build yet* rather than
   being faked.
4. **New project flow (§11)** — "What are you building?" with the five types from the spec; the chosen
   type is stored in project metadata (`Project.type`) and drives the scaffold. `Android App` is offered
   but disabled with a reason, because the build toolchain is Phase 11 — creating it would otherwise be
   the exact "fake success" the spec forbids.
5. **Terminal (§25)** — header shows the workspace and the real working directory, a toolbar with
   Start/Restart, Interrupt, Clear and the real command history, and selectable/copyable output.
6. **Design system** — shared `AgentUi` components, a `Motion`-free visual language consistent with the
   existing scale, `StatusChip`/`ErrorState`/`EmptyState`/`LoadingState` reused everywhere so dialogs,
   empty states and errors look the same across screens.

Deferred on purpose (later phases, listed so nothing is silently dropped): command diagnostics + toolchain
repair UI (7-8), GitHub (§29-33), Android build/install (§34-39), web export/download (§41-42), context
budgeting (§19-20), storage manager (§52), multi-session terminal (§26), persistence migration to Room
(§57) and the QA matrix (§58-59).

## PHASE 2 — what this change set does (§11, §45, §60 Phase 2)

The project type is no longer a label — it is real metadata the whole app consumes.

1. **Type-aware defaults.** `ProjectType` now carries `defaultPort`, `devCommand`, `runCommand`,
   `buildCommand`, `requiredTools` and `languages` per type. `Workspace.create` stamps the project's
   `previewPort` from the chosen type instead of a hard-coded 5173.
2. **Scaffold is provable (§3).** The file contents moved out of `Workspace` into a pure, unit-testable
   `ProjectScaffold` object. `Template` now declares `declaredFiles`, and `ProjectScaffold.write` throws
   if any declared file is missing after it writes — a template that claims a file it does not create is a
   hard error, never silent. `ProjectScaffoldTest` asserts every template writes every declared file.
3. **Preview is type-aware (§40-43).** `PreviewScreen` shows the project's real preview port and the
   type's dev command (with a dedicated "Run default (<cmd>)" button), and the dev-command dialog is
   pre-filled with the type's own command rather than a hard-coded `npm run dev`.
4. **Toolchain hints (§21-23).** `Toolchains.CORE` grew `java` + `gradle` probes (both reported honestly,
   never assumed present). The Toolchain screen now has a "This <type> needs" section listing the
   current project's required tools with real availability status.
5. **Agent prompt context (§45-46).** `HarnessViewModel.typeContext` injects the project's actual type,
   language, run/dev/build commands, required tools and preview port into the system prompt, and
   instructs the agent to only run commands that actually appear in the project files — no invented
   commands.
6. **Dashboard / detail are type-aware (§10, §12).** Project cards, the active banner, the search filter
   and the detail screen now use `Project.kind` (icon/colour/preview/build info) instead of the raw
   template id.

`Android App` remains gated: it is selectable metadata-wise but not creatable until Phase 11 provides the
real Gradle build/install pipeline, so it never produces a project that cannot be built (no fake success).

## PHASE 3 — what this change set does (§60 Phase 3, §41, §52)

File system completeness, real import/export and a storage manager.

1. **Browser CRUD is now complete.** Long-pressing a file/folder opens a menu with Rename and Delete
   (delete is behind a confirm dialog). `ProjectFiles.rename` was already there but unused — it is now
   wired (tab paths are remapped on rename). Create-file/folder and search were already working.
2. **Real ZIP export (§41).** `ProjectArchive.exportZip` writes the true bytes of every project file into
   a `.zip`. The Project detail screen exposes "Export as ZIP" which writes to `<filesDir>/exports/` and
   shares it through a `FileProvider` content Uri (never the raw path).
3. **Real ZIP import (§41).** A SAF `GetContent` picker on the New Project screen imports a zip as a new
   project (`Workspace.createFromZip`), reading the actual bytes via the content resolver. Zip-slip is
   blocked, and a single wrapping folder (GitHub "Download ZIP" style) is stripped. `importFolder` copies
   a real folder into a new/current project.
4. **Storage manager (§52).** New Storage screen shows per-project size, the Linux runtime rootfs size,
   the exports folder and a real total, plus safe cleanup (clear exported archives, clear terminal logs).
   Project files are never deleted silently.

`ProjectArchiveTest` covers export→import round-trip, zip-slip refusal, top-level-folder stripping and
folder copy.

## PHASE 4 — what this change set does (§60 Phase 4)

The editor is usable for real editing workflows.

1. **Find / Replace.** A toggle in the editor toolbar opens an in-file bar with a match count and both
   "Replace" (first occurrence) and "All" buttons. It always operates on the actual tab content — the
   result is pushed through `updateTab`, so the file becomes dirty and undoable.
2. **Undo.** `HarnessViewModel` keeps a per-tab undo stack (capped at 50) pushed on every real change.
   An "Undo" chip on the toolbar restores the previous content; it is enabled only when there is
   history to undo.
3. **AI actions.** An "AI actions" menu offers Explain / Find bugs & fix / Refactor, each producing a
   pending prompt containing the file content. The prompt is routed to the chat composer
   (`vm.pendingPrompt`), so the agent starts with the file in context.
4. **Editor route** gains an `onChat` navigation callback.

(Previously-present tabs, dirty state, save, line numbers, keyword highlighting and project search are
retained; the `combinedClickable` import needed for the long-press row was also fixed.)

## PHASE 5 — what this change set does (§60 Phase 5, §50)

OpenRouter is now a real, configurable provider rather than a hard-coded stream.

1. **Secure API key.** The key lives in `SecureStore` (Android Keystore), set/verified/tested from Settings
   → AI. It is never logged, never echoed back in full, and the settings screen only shows a masked form.
2. **Real model list.** `OpenRouterProvider.listModels` fetches `/models`, reads the real `id`, `name`,
   `context_length` and per-million `pricing`, and caches it in memory for 10 minutes. A manual refresh
   bypasses the cache (`loadModels(force = true)`).
3. **Model selector.** A searchable, categorised picker (Recent / Fast / Coding / Reasoning / Low Cost /
   Premium / All) that applies the real pricing as per-million chips and shows real context length. Free
   models are flagged from real pricing, never guessed.
4. **Streaming + tool calls.** SSE is parsed line-by-line; content deltas stream into the message and tool
   call fragments are accumulated by index across deltas (real `ToolCall`s). The assistant's tool calls are
   stored in the history and replayed on the next turn.
5. **Retry / backoff.** `stream()` retries up to 3 times. 429 honours the `Retry-After` header; other 5xx /
   IO errors back off exponentially (1s, 2s). The last attempt surfaces the real error instead of retrying.
6. **Real usage / cost (§50).** The final SSE `usage` chunk is parsed into a real `Usage` (prompt / completion
   / total tokens) and propagated through `AgentEvent.Usage` → `UiMessage.usage`. The chat renders a compact
   usage line only when tokens are non-zero, and an approximate cost only when the selected model's real
   per-million pricing is known — never invented.
7. **Configurable endpoint.** Settings → AI has an Endpoint field; blank restores the OpenRouter default
   (`https://openrouter.ai/api/v1`), and any compatible endpoint changes where models and completions come
   from (`vm.setEndpoint`).

Error handling maps HTTP status codes (401 / 402 / 404 / 408 / 429 / 5xx) to actionable messages. Conversation
persistence (`<id>.chat.json`) and the existing Stop (`stopGeneration()`) path are retained; both cover the
Phase 5 deliverable of cancellation and persistence.

## PHASES 6-13 — what those change sets do

Summarised here; each item names the file that implements it.

- **§19-20, §46-47 agent** — `ai/AgentContext.kt` prunes the replayed history to a token budget,
  keeping the system prompt and the newest turn and announcing what it dropped. `ai/Agent.kt` runs a
  bounded loop with a real verification command (`Verification`), feeds the *actual* failing output
  back to the model, and stops at `maxAttempts`. `ai/CommandPlanner.kt` only ever proposes commands
  that a file on disk proves exist (lockfiles, `package.json` scripts, a Gradle wrapper).
- **§21-26 terminal** — `runtime/TerminalSessions.kt` (multiple named shells with real pids and
  working directories), `runtime/ShellSession.kt` (bounded scrollback, interrupt, `exec` with an exit
  code), `runtime/CommandDiagnostics.kt` (exit 126/127/130/137 → WHAT/WHY/HOW plus Install/Run/Open
  runtime/Retry actions), and terminal settings that are actually honoured.
- **§27-28 runtime** — `runtime/EnvHealth.kt` probes each tool for real, `runtime/RuntimeRepair.kt`
  diagnoses and repairs, and refuses to install a rootfs on a build with no PRoot loader.
- **§29-33 GitHub** — `runtime/GitHubService.kt` + `runtime/ProjectSync.kt`. Blob SHAs are computed
  exactly like `git hash-object`, so "changed" means changed. Conflicts are reported before a push,
  and the token never leaves the `Authorization` header.
- **§34-39 Android build** — `runtime/AndroidBuildService.kt` reports missing requirements instead of
  starting an impossible build; `runtime/ApkVerifier.kt` opens the produced APK and checks the
  manifest, dex, signature and ABIs before the app calls it a build.
- **§40-44 preview/export** — `runtime/DevServer.kt` (static server + real dev-server processes with
  console and exit codes) and `data/ProjectArchive.kt` (zip/source/production/selection exports).
- **§12, §48 diff/permissions** — `data/DiffEngine.kt` (LCS unified diffs, binary detection),
  `runtime/ChangeTracker.kt` (per-file review/revert against a session baseline), and the approval
  gate inside `ai/AgentTools.kt`.
- **§13, §51 background** — `runtime/TaskRegistry.kt` drives the task strip and the foreground
  notification, so nothing runs invisibly.

## PHASE 14 — security & performance

- `docs/SECURITY_AUDIT.md` is the §53 audit: credentials, logs, prompts, exports, network,
  permissions, execution safety, and the accepted risks.
- §55 **offline mode**: `runtime/Connectivity.kt` exposes the OS's validated-network state.
  `ChatScreen`, the model selector and the GitHub screen show an inline offline strip, and the
  view-model refuses network calls with a plain explanation rather than a socket timeout.
- §56 **recovery**: `runtime/Recovery.kt` writes a marker before every long operation. If the
  process is killed, the next launch reports exactly what was interrupted and what to do about it,
  then sweeps the scratch files that run left behind.
- §54 **performance**: bounded terminal scrollback and build logs, `Dispatchers.IO` for every file
  walk and process call, streaming diffs computed on a background dispatcher, and a 5 MB/2 MB cap on
  files considered for sync and editing.

## PHASE 15 — QA

15 test classes, 111 tests, all executed locally and green:

`AgentContextTest`, `AgentLoopTest`, `AgentPermissionTest`, `AgentToolsTest`, `ApkVerifierTest`,
`ChangeTrackerTest`, `CheckpointTest`, `CommandDiagnosticsTest`, `CommandPlannerTest`,
`DiffEngineTest`, `ProjectArchiveTest`, `ProjectFilesTest`, `ProjectScaffoldTest`, `ProjectSyncTest`,
`RecoveryTest`.

Two real bugs were found by writing them: `DiffEngine` never set its `binary` flag, and the Android
template wrote two files it did not declare. Both are fixed.

## PHASE 16 — the APK

Build: GitHub Actions run **34002043430**, commit `36d75d0`, ubuntu-24.04, Temurin JDK 17,
Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, Android SDK build-tools 35.0.0.

Every step is green: `lintDebug` → `testDebugUnitTest` → `assembleDebug` → `assembleRelease` →
APK verification → artifact upload.

```
--- Generated APKs ---
app/build/outputs/apk/debug/app-debug.apk
-rw-r--r-- 1 runner runner 18M app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
-rw-r--r-- 1 runner runner 12M app/build/outputs/apk/release/app-release.apk
PASS: app/build/outputs/apk/debug/app-debug.apk is a valid Android package
PASS: app/build/outputs/apk/release/app-release.apk is a valid Android package
BUILD SUCCESSFUL in 1m 36s
```

Three real compile errors had to be fixed to get here, and they are worth recording because two of
them could not be seen from the sandbox (there is no Android SDK in it):

1. `ui/build/BuildScreen.kt` was **invisible to the build**. The Gradle `.gitignore` line `build/`
   matches *any* directory called `build`, including a Kotlin source package — so the file was never
   committed. The screen now lives in `ui/apk/` (package `com.sufyan.harness.ui.apk`).
2. `ui/components/AgentUi.kt` used `11.sp` without `import androidx.compose.ui.unit.sp`.
3. `ui/settings/StorageScreen.kt` read `StorageSnapshot?` as if it were non-null; it now falls back
   to a zeroed snapshot until the first real measurement lands.

## Post-release fix — crash on the second launch

Reported from a device: the app started once, then crashed on every launch afterwards with

```
java.lang.RuntimeException: Cannot create an instance of class com.sufyan.harness.HarnessViewModel
Caused by: java.lang.NullPointerException: Attempt to invoke interface method
  'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)' on a null object reference
    at com.sufyan.harness.HarnessViewModel.open(HarnessViewModel.kt:136)
    at com.sufyan.harness.HarnessViewModel.<init>(HarnessViewModel.kt:122)
```

**Cause.** `HarnessViewModel`'s `init` block ended with
`settings.lastProjectId?.let { ... open(it) }`. Kotlin initialises properties in declaration order,
and `open()` writes `_tabs`, `_activeTab`, `_messages`, `_git`, `_checkpoints`, `_changes`,
`_githubState` and `_buildState` — every one of them declared *below* the `init` block (lines 344 to
1519) and therefore still `null` while the constructor runs. The first ever launch has no
`lastProjectId`, so the branch is skipped and the app works; from the second launch on, the
constructor throws, `ViewModelProvider` rethrows it as *Cannot create an instance*, and the activity
dies before any UI exists. Permanent, unrecoverable from the user's side.

**Fix** (`HarnessViewModel`, `data/StartupGuard.kt`, `runtime/CrashLog.kt`, `ui/HarnessRoot.kt`):

1. The `init` block is gone. Start-up is an explicit `vm.start()` that `HarnessRoot` calls from a
   `LaunchedEffect(Unit)` — after every property exists, and where a failure can be shown.
2. Each start-up step is individually guarded, and so is `open()`: an unreadable conversation, git
   directory or dev-server port now degrades that one feature and reports it, instead of throwing.
3. `StartupGuard` (pure, unit-tested) decides whether the last project may be restored. A marker
   (`Settings.pendingRestoreId`, written with `commit()`) is set before the restore and cleared
   after; finding it still set at launch means the previous attempt died, so the project is *not*
   re-opened and the user is told why. A crash there can never become a crash loop again.
4. `CrashLog` installs an uncaught-exception handler that writes `time / msg / stacktrace` to
   `filesDir/crash/last-crash.txt`. The next launch shows it in a dialog with **Copy log**, so the
   app reports its own crashes instead of the user having to find them in logcat.

Tests: `StartupGuardTest` (6) and `CrashLogTest` (8), including the exact device crash text as a
parser fixture. Total **125 tests**.

## Follow-up — cloud build (§34-§39 completed for real devices)

The Build APK screen honestly reported five missing requirements on a phone (JDK 17, Gradle, Android
SDK, platform, build-tools), and none of them can be satisfied there: Android has no JVM, this build
ships no PRoot loader (`libproot.so` is absent, so `LinuxRuntime.install` correctly refuses), and
Google publishes `aapt2`/`zipalign`/`apksigner` only for x86_64 desktops. Rather than leave the
screen as a dead end, the build now happens where a toolchain exists.

- `runtime/CloudBuild.kt` — the workflow the app installs into the *user's* repository, artifact
  selection, run-status interpretation and zip-slip/size-safe APK extraction. Pure and unit tested.
- `GitHubService` — Actions API: `dispatchWorkflow`, `latestWorkflowRun`, `workflowRun`,
  `workflowRunSteps`, `runArtifacts`, `downloadArtifact`.
- `HarnessViewModel.startCloudBuild(variant)` — push → dispatch → follow the real run step by step →
  download → `ApkVerifier.verify` → the APK joins the artifact list with Install/Share. Every failure
  path reports WHAT/WHY/HOW; `stopFollowingCloudBuild()` says plainly that GitHub keeps building.
- Build screen — a *Build in the cloud* card with live step list and a link to the run, above the
  on-device section.

Tests: `CloudBuildTest` (14). Total **139 tests**, green in CI run 34004002555.

## Follow-up — agent step budget and the cloud shell

Two things a real device exposed.

**"Agent reached its 12 step limit"** was hard-coded and reported as a failure. Now:
`Settings.agentMaxSteps` (4-100, default 12) is passed to `Agent`, the loop emits a dedicated
`AgentEvent.StepLimit` instead of `AgentEvent.Failed`, and `HarnessViewModel` resumes the same turn
automatically while `Settings.agentAutoContinue` is on, bounded by `MAX_AUTO_CONTINUE = 5` and
announced in the chat on every continuation. A turn that really does stop says "paused, not failed".

**Terminal packages** cannot be installed on Android from inside a sandboxed app — an app targeting
a modern API level may not exec binaries from its data directory, and this build ships no PRoot
loader. So `runCloudCommand()` runs the typed command in the project's GitHub repository on Ubuntu
through a second workflow (`.github/workflows/sufyan-harness-command.yml`, command passed via an env
var so the workflow cannot be injected), follows the run, downloads the log artifact and shows the
real output and exit code in a cloud panel in the Terminal (cloud icon in the top bar).
`cloudPrepare()` and `followCloudRun()` are now shared by both cloud build and cloud command.

Tests: `CloudBuildTest` grew to 18, `AgentLoopTest` to 10. Total **145 tests**, green in CI run
34005358118.

## Follow-up — reading a failed cloud build

A failed build is only useful with the reason attached. `GitHubService.runLogs()` downloads the run's
log bundle, `CloudBuild.readLogBundle()` reads its text entries with a 6 MB cap, and
`CloudBuild.buildErrors()` keeps only the lines that explain the failure (compiler `e:` lines,
`FAILURE:`, `Caused by:`, `Execution failed for task`, test failures) while dropping timestamps,
warnings and stack frames. The build screen shows them and offers **Ask the AI to fix it**, which
starts a normal agent turn with those exact lines — the loop this repo's own CI has been proving all
along, now inside the app.

Tests: `CloudBuildTest` 23. Total **150 tests**, green in CI run 34017467247.
