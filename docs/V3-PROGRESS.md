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
| 1 | UI/UX rebuild: Projects, project details, agent workspace, terminal, dialogs, empty/loading/error | IN PROGRESS — see below |
| 2 | Project type system (ANDROID_APP/WEBSITE/WEB_APP/NODE/EMPTY + metadata) | STARTED (type stored in metadata; Android scaffold pending Phase 11) |
| 3 | Project + file system repair (CRUD, import/export, browser, search, storage) | TODO |
| 4 | Code editor | TODO |
| 5 | OpenRouter (models, keys, fallbacks) | TODO |
| 6 | True AI agent (planning, tool budget, verification) | TODO |
| 7 | Terminal repair | TODO |
| 8 | Linux / PRoot runtime | TODO |
| 9-10 | GitHub, web preview + export | TODO |
| 11 | Android build / install | TODO |
| 12-16 | Git/diff/checkpoints, background runtime, security/perf, QA, final APK | TODO |

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
