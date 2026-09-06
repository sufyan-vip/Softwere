# Security & privacy audit (spec §53)

Date: 2026-09-06 · Branch: `arena/01a07329-softwere` · Scope: every source file under
`app/src/main/java/com/sufyan/harness/`, the manifest, and the resource XML.

This is a real read-through of the code, not a checklist copied from the spec. Each row names the
file that backs the claim, so it can be re-checked. Where something is **not** hardened, it is
listed under "Accepted risks" instead of being quietly omitted (RULE 3, RULE 4).

## 1. Credentials

| Secret | Where it lives | How it is protected |
|---|---|---|
| OpenRouter API key | `data/SecureStore.kt` | `EncryptedSharedPreferences` (`AES256_SIV` keys / `AES256_GCM` values) with an Android Keystore `MasterKey`. Never written to `Settings` (plain prefs). |
| GitHub personal access token | `data/SecureStore.kt` | Same encrypted store, separate key. Deleting the connection calls `clearGithubToken()`, which also removes the cached login. |
| GitHub login name | `data/SecureStore.kt` | Stored alongside the token; not a secret, but removed with it. |

- Neither secret is ever rendered in full. `maskedApiKey()` / `maskedGithubToken()` return
  `sk-or…abcd`-style masks and are the only accessors the UI calls
  (`ui/settings/SettingsScreen.kt`, `ui/github/GitHubScreen.kt`).
- `secure.clearAll()` exists and is wired to Settings → "Delete all credentials".

## 2. Where credentials are *not*

Checked by grep across the whole source tree:

| Sink | Result |
|---|---|
| Logs | The app contains **no** `Log.*` and **no** `println` calls at all. Nothing can leak through logcat because nothing is written to it. |
| AI prompts | The system prompt is built in `HarnessViewModel.DEFAULT_SYSTEM_PROMPT` + `typeContext()`; it contains project metadata and detected commands only. `SecureStore` is never read while building a prompt. |
| Tool payloads sent to the model | `ai/AgentTools.kt` reads project files under the project directory only. The encrypted prefs live in `filesDir/../shared_prefs`, outside every project root, and `ProjectFiles` refuses paths that escape the root (`ProjectFilesTest.path traversal is blocked`). |
| Terminal / command history | `data/Settings.commandHistory()` stores what the user typed. The app never *injects* a token into a command line: GitHub is driven by the REST API (`runtime/GitHubService.kt`), never by `git push https://<token>@…`. |
| Git commits | Commits are created through the GitHub API with a JSON payload built from project files; credentials are only ever in the `Authorization` header. |
| Error messages | `GitHubService.errorFor()` maps status codes to advice ("GitHub rejected the token (401)…") and never echoes the header or body containing the token. `OpenRouterProvider` reports the provider's `error.message` field only. |
| Analytics / crash reporting | None. The app has no analytics SDK, no crash reporter, and no third-party telemetry dependency. |
| Backups | `android:allowBackup="false"` — the encrypted prefs cannot be pulled off the device with `adb backup`. |

## 3. Network

| Item | Finding |
|---|---|
| Endpoints contacted | `openrouter.ai` (AI), `api.github.com` + `codeload.github.com` (GitHub), and whatever URL the user types for a custom OpenAI-compatible endpoint (`Settings → Endpoint`). Nothing else. |
| Transport | HTTPS via OkHttp defaults. `res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="false"` for everything except `127.0.0.1` / `localhost`, which the preview server needs. The blanket `android:usesCleartextTraffic="true"` was removed during this audit. |
| Offline behaviour | `runtime/Connectivity.kt` reports the OS's validated-network state; `HarnessViewModel.send()`, `loadModels()` and every GitHub action refuse early with a plain "you are offline" message instead of a socket timeout (§55). |
| Auth headers | `Authorization: Bearer …` only, added per request in `OpenRouterProvider.authHeader` / `GitHubService.request()`. No token is placed in a URL or query string, so it cannot end up in a redirect or a server log. |

## 4. Files, exports and temporary data

| Item | Finding |
|---|---|
| Project storage | `filesDir/workspace/<projectId>` — app-private. No `READ/WRITE_EXTERNAL_STORAGE` permission is requested. |
| Exports | Written to `filesDir/exports` and shared through a `FileProvider` limited to that one directory (`res/xml/file_paths.xml`, `android:exported="false"`, `grantUriPermissions="true"`). A share grants read access to a single URI, not to the workspace. |
| Export contents | `ProjectArchive` walks the project directory; `exportSource()` skips `node_modules`, `build`, `dist`, `.gradle`. Credentials are not in the project directory in the first place. |
| Zip import | `ProjectArchive.importZip()` rejects entries that escape the destination (zip-slip), covered by `ProjectArchiveTest.zip-slip entry is refused`. |
| Temporary files | Imports/pulls use `cacheDir` and are deleted after use; `runtime/Recovery.sweep()` removes any that a killed process left behind on the next launch. |
| Checkpoints | Stored under the project's own `.harness/checkpoints`; deleting the project deletes them. |

## 5. Permissions requested

| Permission | Why it is needed | What would break without it |
|---|---|---|
| `INTERNET` | OpenRouter, GitHub | AI and GitHub |
| `ACCESS_NETWORK_STATE` | §55 offline detection | Honest offline messages |
| `POST_NOTIFICATIONS` | §51 task-complete notices (runtime-requested on Android 13+) | Background task results |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | §13 keeps a running server/build alive with a visible notification | Long tasks killed in the background |
| `REQUEST_INSTALL_PACKAGES` | §37 hands a built APK to the system installer | Installing your own build |

No location, contacts, camera, microphone, SMS, or external-storage permission is requested.

## 6. Execution safety

- **The agent cannot touch GitHub.** By design (§33) there is no GitHub tool in `AgentTools`, so no
  model output can push, force-push, or delete a branch. Every GitHub action starts with a tap.
- **Destructive file actions are gated** in `AgentTools.gate()` — overwriting an existing file,
  deleting a file, and running a command require approval under the default
  `AgentPermission.AskDestructive`. With no approval channel wired, the tool returns
  `ok = false, "Approval required"` and *does not touch the disk*
  (`AgentPermissionTest.without an approval channel a gated action is refused, not performed`).
- **The tool sandbox is the project directory.** `ProjectFiles` resolves and canonicalises every
  path and rejects anything outside the root.
- **Commands run in the project directory** through `ShellSession`/`LinuxRuntime`; the app never
  runs anything as root and ships no `su` path.
- **`run_command` can be switched off entirely** — Settings → Agent → *Allow commands*
  (`Settings.agentCommandsEnabled`). When it is off the tool is not even present in the schema sent
  to the model (`AgentToolsTest.schemas exclude run_command when disabled`), so no prompt injection
  can reach it.

## 7. Accepted risks (documented, not hidden)

1. **A pasted token is as strong as the device lock.** `EncryptedSharedPreferences` keys are
   Keystore-backed but not tied to user authentication, so an attacker with an unlocked, rooted
   device can read them. Mitigation offered to the user: use a fine-grained PAT with `repo` scope
   only, and disconnect when finished.
2. **The AI provider sees your code.** Any file the agent reads is sent to the model you selected.
   This is inherent to the product; the chat screen names the model on every turn, and the agent's
   file reads are listed in the activity timeline so it is never invisible.
3. **`run_command` runs real commands.** The default permission mode asks before every command; the
   `AutoSafe` mode does not. That trade-off is the user's to make and is labelled as such in
   Settings.
4. **A custom endpoint is trusted by the user.** If you point the app at your own OpenAI-compatible
   server, your key is sent there. The endpoint is shown in Settings and defaults to OpenRouter.

## 8. Verdict

No credential leak was found in logs, prompts, exports, commit payloads, error messages, or shared
URIs. The two fixes made during this audit were: removing the blanket
`android:usesCleartextTraffic="true"` from the manifest (the network security config already scoped
cleartext to localhost), and documenting the accepted risks above.
