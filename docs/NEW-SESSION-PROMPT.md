# PROMPT FOR A NEW SESSION — continue V3 phase-by-phase until the final APK

Paste everything below the line into the new session. It is self-contained: working mode, environment
facts, what is already done, what is left, and the loop to run for every remaining phase.

---

## ROLE

You are finishing **Sufyan Harness** (GitHub `sufyan-vip/Softwere`, Android app in `app/`). The single
source of truth is the repo file **`Sufyan_Harness_Master_Rebuild_Prompt_V3.md`** (2335 lines, sections
§0–§65). Work through its **§60 phases in order** and do not deviate from the spec's rules: §2 do not break
working features, §3 no fake success, §4 no hidden failures, §5 UI quality, §62 final UI check, §63
definition of done, §64 final APK requirement.

## WORKING MODE — READ THIS CAREFULLY, IT IS A HARD INSTRUCTION FROM ME (THE USER)

1. **Do not ask me questions and do not stop to report progress.** Never ask "should I continue",
   "kahan tak pohncha", "Phase 2 karun?" — the answer is always yes, keep going.
2. After each phase: **audit your own work** (read the diff, re-read the spec section, check the phase
   actually did what §60 says). If something is missing or wrong, fix it **in the same turn** instead of
   asking me.
3. Then **immediately start the next phase**. Same turn if budget allows, otherwise the next turn.
   Continue `phase → phase → phase` until Phase 16 is done.
4. **Only at the very end** do you write the summary. The last thing you produce must be a **real,
   downloadable APK artifact** (§64). Never end with "you can build it by running…".
5. Tell me in **Roman Urdu**, short, at the end. Everything else (code, commits, PR, docs) in English.
6. If you hit a true blocker (a permission you do not have, a device-only behaviour you cannot verify),
   **do not stop and do not fake it**: leave the feature honest-but-marked (see the `UnavailableNotice`
   pattern already in the app), record it in `docs/V3-PROGRESS.md`, and keep working on the next phase.
7. Never ask for GitHub passwords, tokens, 2FA or OAuth codes. `git` and `gh` are already authenticated.

## ENVIRONMENT FACTS (learned the hard way — do not re-discover them)

* **You have no local toolchain**: no JDK, no `gradle`, no `sdkmanager`, and every artifact host
  (`repo1.maven.org`, `maven.google.com`, `dl.google.com`, `plugins.gradle.org`, `jitpack.io`,
  `raw.githubusercontent.com`) is blocked. **The CI pipeline is your only compiler.** Write code that you
  can defend by reading, and let CI prove it.
* Build with the branch pipeline: commit, `git push origin arena/01a0723b-softwere`, then poll
  `gh run list --branch arena/01a0723b-softwere --limit 1 --json databaseId` and
  `gh run view <ID> --json status,conclusion --jq '[.status,.conclusion]|join(" ")'`.
  A full run takes ~4–8 minutes. Keep each bash call under ~240s (long sleeps get killed), and never use
  `gh run watch`.
* **Logs are NOT readable from the sandbox.** `gh run view --log`, `--log-failed`, the jobs/logs REST
  blobs and `gh run download` all fail. The **only** readable build output is:
  `gh api "repos/sufyan-vip/Softwere/check-runs/<JOB_databaseId>/annotations"` where `JOB_databaseId`
  comes from `gh run view <RUN> --json jobs --jq '.jobs[0].databaseId'` (passing a *run* id gives 404).
* When CI fails with only `Process completed with exit code 1.`, temporarily append this to the root
  `build.gradle.kts` (the Kotlin failure message never contains the `e:` lines; they only reach the
  daemon log, so the hook scrapes them from there and republishes them as annotations). Remove it as soon
  as the build is green, in its own commit:

  ```kotlin
  // TEMPORARY CI DIAGNOSTICS
  if (System.getenv("GITHUB_ACTIONS") == "true") {
      gradle.addBuildListener(object : org.gradle.BuildAdapter() {
          override fun buildFinished(result: org.gradle.BuildResult) {
              val failure = result.failure ?: return
              val text = generateSequence<Throwable>(failure) { it.cause }
                  .joinToString("\n") { it.message ?: it.toString() }
              val errors = text.lineSequence().filter { it.startsWith("e: ") || it.contains(": error:") }
                  .map { it.trim() }.distinct().toMutableList()
              if (errors.isEmpty()) {
                  val logs = file("${System.getProperty("user.home")}/.gradle/daemon").walkTopDown()
                      .filter { it.name.endsWith(".out.log") }.toList()
                  for (log in logs) {
                      val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
                      errors += lines.asReversed().asSequence().map { it.trim() }
                          .filter { it.startsWith("e: ") }.distinct().take(8)
                      if (errors.isNotEmpty()) break
                  }
              }
              if (errors.isEmpty()) errors += text.take(1200)
              for (line in errors.take(8)) {
                  println("::error::" + line.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"))
              }
          }
      })
  }
  ```

* **You cannot push to `.github/workflows/**`** — a server-side pre-receive hook rejects it (the App has
  no `workflows` permission). The improved pipeline therefore lives at `ci/android-workflow.yml`; I
  activate it myself with `cp ci/android-workflow.yml .github/workflows/android.yml`. Keep `ci/` as the
  source of truth and keep `if-no-files-found: error` on the APK uploads — a run must never report success
  with no APK.
* `gh pr edit --body-file` fails (Projects-classic GraphQL sunset). Patch PRs with
  `gh api -X PATCH repos/sufyan-vip/Softwere/pulls/<N> --input payload.json` instead.
* Kotlin/AGP versions are pinned: Kotlin 1.9.24, AGP 8.5.2, Compose BOM 2024.06.00 (material3 1.2.1),
  composeCompiler 1.5.14, compileSdk 34, minSdk 26, `FAIL_ON_PROJECT_REPOS` in `settings.gradle.kts`.
  Keep **explicit imports** (no wildcards in new code), and only use APIs that exist in those versions —
  no `SelectionContainer`-style guessing for anything new without checking.
* Classic compile-error classes to self-check before pushing (all three bit this repo already):
  suspend call from a non-suspend lambda (e.g. `ClipboardManager.setText`), missing `kotlinx.serialization`
  extension imports, and a generic `Result<…>`/`runCatching { }` block whose last expression returns
  something else (e.g. ending on `scope.launch { }` gives `Result<Job>`). Also remember a `by remember`
  local cannot be smart-cast.

## WHERE THE REPO STANDS (verified green at `9bc6603`)

`main` = `b6e45ce` (my V3 upload). Work happens on branch **`arena/01a0723b-softwere`**, which is the head
of **open PR #2** (`MERGEABLE`, `build` check passing). Do not create branches; keep pushing to that branch
so PR #2 keeps the audit trail. Local checkout: `/home/user/Softwere`.

**Done and green (do not redo):**

* Three root causes of the red CI, fixed:
  `kotlinx.serialization.{decodeFromString,encodeToString}` imports + `ProjectIndex.serializer()` +
  `application = app as HarnessApp`; explicit `lifecycle-viewmodel-ktx`, `compose.foundation:foundation`,
  `compose.animation:animation`; `ChatScreen` clipboard call moved into `scope.launch { }`;
  `DevServer.startStatic` block ends on `Unit` (was `Result<Job>` vs `Result<Unit>`).
* **Phase 0 — audit** → `docs/V3-PROGRESS.md` (`WORKING/PARTIAL/BROKEN/MISSING/MOCKED` per spec area +
  phase ledger). Update this file when a phase finishes; it is the single ledger.
* **Phase 1 — UI/UX rebuild** → agent workspace in `ui/chat/ChatScreen.kt` (Conversation · collapsible
  Agent activity timeline · Final answer with changed files + Review/Preview), new
  `ui/components/AgentUi.kt` (`AgentStateBar`, `ActivityTimeline`, `ToolCallLine`, `FinalAnswerCard`,
  `SessionSummaryCard`, `AssistantRichText`), `AgentPhase` + status derived from real `AgentEvent`s
  (`AgentEvent.Status` used to be dropped on the floor), `AgentEvent.ToolStarted` carries the
  path/command, failures explain what/why/how from real output with a re-run affordance, Projects
  dashboard + `ProjectDetailScreen` route, `ProjectType` metadata (`Project.type`), creation flow with the
  five type cards, terminal header/toolbar/`SelectionContainer`, `shellCwd`, `commandRunning`,
  `restartShell`, and `startShell` only claiming a live session when the process really started.
* APKs on that green run: `sufyan-harness-debug-apk` (~17.9 MB), `sufyan-harness-release-apk` (~11.7 MB).

**Deliberately left honest, not fake:** the *Android App* type card is shown but not selectable until
Phase 11 exists; `Build` on the project screen is not invented early. Fix that by implementing the phases,
not by adding labels.

## WHAT IS LEFT — implement in this order, one phase at a time

Each line = the §60 phase, the sections to read for it, and the acceptance check. Read the spec section
before coding it; §60's list is short and the detail lives in the numbered sections.

1. **Phase 2 — Project type system** (§11, §45): make `ANDROID_APP`/`WEBSITE`/`WEB_APP`/`NODE`/`EMPTY`
   real end-to-end — metadata drives scaffold, preview, toolchain hints and agent prompt context; verify
   every template actually writes its files (a template that claims files it does not create is a §3
   violation); add type-aware defaults (`previewPort`, dev command, language).
2. **Phase 3 — Project + file system** (§60 Phase 3, with §52): CRUD audit, folder import/export, a real zip
   export of a project, file browser completeness, search, storage usage per project + total.
3. **Phase 4 — Code editor** (§60 Phase 4): tabs, dirty state, save, find/replace, line numbers, syntax
   highlighting where cheap, editable-vs-readonly by size, keyboard handling.
4. **Phase 5 — OpenRouter** (§60 Phase 5): key/endpoint health, model list caching, per-model context
   length, pricing/usage display (§50), retry/backoff on 429, error mapping into the §4 format.
5. **Phase 6 — True AI agent** (§18–§20, §46–§48): planning step, tool budget, verification step after
   edits, a real build/fix loop, confirmation modes (auto vs ask-before-running-commands), context
   budgeting/truncation (§19) instead of replaying all history.
6. **Phase 7 — Terminal repair** (§21–§26): command diagnostics per binary, PATH/env health, scrollback,
   word wrap, font size, clear-on-new-session, working-directory tracking after `cd`, multi-session
   manager (PID, cwd, status, output, start time). Every visible setting must change behaviour (§24).
7. **Phase 8 — Linux/PRoot runtime** (§27–§28): install/resume/verify, rootfs health checks, a repair
   flow, clear failure reasons, disk-space guard.
8. **Phase 9 — GitHub** (§29–§33): token storage in the existing `SecureStore`, list/create repos,
   push/pull, credentials never echoed, conflict resolution UI, PR creation; all real network calls, no
   stub endpoints.
9. **Phase 10 — Web preview + export** (§40–§44): static + dev-process preview parity, export to
   download/share, preview error reporting into chat.
10. **Phase 11 — Android build/install** (§34–§39): on-device or CI-assisted Gradle build, install via
    PackageInstaller, artifact management, rebuild-on-change loop; only then enable the `ANDROID_APP`
    creation card.
11. **Phase 12–14** — git/diff/checkpoints polish, background runtime (services, notifications §51),
    security/privacy + performance passes (§53–§54).
12. **Phase 15 — Full QA** (§58–§59, §62): unit tests for the new logic, run the §61 user flows A–E
    against real state, walk every screen for the §62 checklist.
13. **Phase 16 — Final APK** (§64): clean green run, both APKs, verify, report build type + artifact names
    + the run URL, update `docs/V3-PROGRESS.md` to DONE per phase, and a final PR summary.

## THE LOOP FOR EVERY PHASE

1. Read the spec sections for the phase + the current code they touch.
2. Implement. Additive over destructive; never replace a working real implementation with a fake one.
3. Self-check the diff for the classic error classes above; keep `docs/V3-PROGRESS.md` truthful.
4. `git commit` (explain *why*, name the spec sections) → `git push origin arena/01a0723b-softwere`.
5. Poll the run. If red, read the annotations (use the diagnostic hook if the message is useless), fix,
   push again. Repeat until green — **never** end a phase on a red pipeline.
6. Next phase. No check-ins.

## FINAL MESSAGE TO ME (only when Phase 16 is done)

Short Roman Urdu: kaunse phase khatam hue, kaunsi cheez abhi bhi device par test karni padegi (honestly),
konse commits/PR hain, aur **APK kaunse run page se download hoga** (artifact names + run URL). Include
the build type (debug/release), sizes, and the one thing I should test first on the phone.
