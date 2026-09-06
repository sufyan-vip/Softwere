# Known issues & limitations

Honest list, per RULE 3 (no fake success) and RULE 4 (no hidden failures). Everything here is a
real constraint of the current build — not a TODO that pretends to be a feature elsewhere in the UI.
Where the app hits one of these at runtime it says so on screen, with a reason and a next step.

Last updated: 2026-09-06.

## 1. Linux runtime (PRoot)

| Issue | Detail |
|---|---|
| **No PRoot loader is shipped in this APK** | `LinuxRuntime.prootAvailable()` returns false because no `libproot.so` is bundled, so `install()` refuses to download a rootfs rather than leaving an unusable one on disk. The Terminal falls back to the Android shell (`/system/bin/sh`), which is real but limited: no `apt`, no glibc, and a read-only `/system`. |
| **Consequence for toolchains** | Node/Python/Java are only detected if the device already provides them. `Toolchains` reports each one as genuinely missing instead of claiming an install succeeded. |
| **Consequence for Gradle builds** | `AndroidBuildService` reports `BuildOutcome.Blocked` with the specific missing requirement (JDK, Android SDK, Gradle wrapper) instead of starting a build that cannot finish. Building an Android project on-device therefore needs a device that already has a JDK + SDK reachable from the shell. |

## 2. Android build & install

- **Release signing uses the debug keystore** (`app/build.gradle.kts`). Output APKs install fine but
  are not distributable through Play. Replace `signingConfigs.debug` with a real keystore before
  shipping.
- **`REQUEST_INSTALL_PACKAGES` still needs the per-app switch.** On Android 8+ the user must allow
  "Install unknown apps" for Sufyan Harness. `builder.canRequestInstall()` checks this first and the
  Build screen sends the user to the right settings page; the app never claims an install happened
  when the system dialog was dismissed.
- **The build log is capped at 500 lines** in the UI (`buildState.log`). The full Gradle output is
  still what drives the diagnosis; only the on-screen tail is bounded.

## 3. GitHub

- **The API is used, not `git`.** Push builds a commit through the GitHub REST API from the files
  that actually differ. This means: no rebase, no merge, no partial staging, and no submodules.
  Conflicts are detected (`ProjectSync.conflicts`) and shown before anything is written, but they
  must be resolved by choosing a side, not with a three-way merge.
- **Files larger than 5 MB are not pushed.** `ProjectSync.MAX_FILE_BYTES`; oversized files are listed
  in the push sheet rather than silently skipped.
- **The agent has no GitHub tool at all** (§33, deliberate). The model cannot push, force-push or
  delete a branch; every GitHub action requires a tap.

## 4. Preview & export

- **The static server binds to `127.0.0.1` only.** The preview is reachable from this device, not
  from another machine on the network. That is a deliberate security choice.
- **Framework dev servers need their toolchain.** `npm run dev` only works if Node really exists on
  the device (see §1). Otherwise `DevServer` reports the missing executable with a WHAT/WHY/HOW
  diagnosis instead of a spinner that never resolves.
- **Production export requires a real build directory.** `exportProduction()` fails with a clear
  message when `dist/`, `build/` or `out/` does not exist — it never zips an empty folder and calls
  it a production build.

## 5. AI

- **Token counts are estimates unless the provider reports them.** `AgentContext.estimateTokens()`
  uses a 4-characters-per-token approximation for the context budget. Real usage numbers are only
  shown when OpenRouter returns a `usage` block (§50), and they are labelled as coming from the
  provider.
- **Context pruning drops whole old turns.** Long conversations lose their oldest turns; the system
  prompt and the newest turn always survive, and a visible system note says how many messages were
  dropped. Nothing is summarised by a second model, so nothing is invented.
- **Cancelling mid-turn keeps the edits already made.** Stopping the agent does not roll back files
  it already wrote; those changes are listed in Review changes so they can be kept or reverted
  individually.

## 6. Editor

- **Files above 2 MB are not opened** (`ProjectFiles.MAX_EDITABLE_BYTES`) and files with a binary
  extension are refused with a message, because rendering them in a Compose text field would freeze
  the UI.
- **Undo is per-tab and capped at 50 steps**, held in memory only — closing a tab clears its history.
- **Syntax highlighting is lightweight** (keyword/string/comment classes per language), not a full
  parser. It never rewrites the file; it only styles what is displayed.

## 7. Testing & verification in this environment

- The development sandbox has **no JDK, no Android SDK, no Gradle**, and Maven/Google/Gradle hosts
  are network-blocked, so `./gradlew` cannot run here. See `docs/IMPLEMENTATION_STATUS.md` for what
  *was* verified locally: the full non-Compose source set plus all unit tests were type-checked and
  **executed** against a stub Android/coroutines/JSON rig (111 tests, 0 failures).
- **Compose UI files cannot be compiled locally** (no Compose artefacts available offline). They are
  verified by a static cross-check that every `vm.…` and `project.…` member referenced from `ui/`
  exists on the real class, plus review. The authoritative compile is the GitHub Actions build.
- **No instrumented (device) tests exist.** §59 real-device testing is a manual checklist; it has
  not been executed on hardware from this environment.

## 8. Smaller nits

- Unused locals remain in `HarnessViewModel.startShell()` and `ShellSession.send()`, and a few new
  UI files carry unused imports. These are warnings, not errors (`lint.abortOnError = false`).
- `Recovery.Operation.RuntimeInstall` exists but is not reachable yet, because rootfs installation is
  refused on a build with no PRoot loader (§1).
- The chat's activity timeline keeps every tool row for the session; very long sessions grow the
  message list in memory until the conversation is cleared.
