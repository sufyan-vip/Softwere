# SUFYAN HARNESS — MASTER REBUILD & UPGRADE PROMPT
## Premium Android AI Coding Agent + Real Linux Terminal + GitHub + APK/Web Export

> **THIS IS THE MASTER IMPLEMENTATION SPECIFICATION.**
>
> You are not being asked to make a demo. You are being asked to turn the existing **Sufyan Harness** project into a polished, reliable, production-quality Android AI development workspace.
>
> **Follow this document phase-by-phase. Audit the existing implementation first, preserve working functionality, repair broken functionality, then add the missing capabilities.**
>
> **Do not stop at UI. Do not fake terminal output, AI actions, GitHub synchronization, builds, previews, installations, or downloads.**
>
> **The final deliverable MUST include a working installable APK.**

---

# 0. CURRENT PRODUCT / PROBLEM STATEMENT

The current application already has a promising foundation:

- Projects
- AI Chat
- Terminal
- Editor
- Settings
- OpenRouter-style AI model selection
- AI tool-call cards such as `read_file`, `write_file`, `run_command`
- Project/file interaction

However, the current implementation needs a serious engineering and UX upgrade.

The current UI screenshot shows problems that MUST be addressed:

1. The AI chat does not clearly separate the user's conversation from the agent's live work.
2. Tool calls are visually repetitive and consume too much vertical space.
3. The agent appears to repeatedly say it is checking/rechecking files instead of presenting a clean execution timeline.
4. Terminal commands can fail because required executables/runtime components are unavailable, yet the UI does not explain the real cause well enough.
5. Terminal functionality/settings appear present but some options are not actually connected to the runtime.
6. The terminal can execute at least some commands, but command compatibility and runtime health are inconsistent.
7. The project creation flow does not clearly establish whether the user is creating:
   - Android app
   - Website
   - Web app
   - Node project
   - React project
   - Other project
8. Android app preview/install workflow is missing or incomplete.
9. Website/web-app export/download workflow is missing or incomplete.
10. GitHub integration is missing/incomplete.
11. The product needs a much more professional UI and information architecture.

**Fix all of these.**

---

# 1. GOLDEN RULES

## RULE 1 — AUDIT BEFORE EDITING

Do not immediately rewrite the application.

First:

- inspect repository
- inspect current screens
- inspect navigation
- inspect AI implementation
- inspect tool executor
- inspect terminal implementation
- inspect runtime implementation
- inspect settings
- inspect storage
- inspect Git integration
- inspect build system
- inspect tests
- inspect Android manifest
- inspect Gradle configuration
- inspect native/JNI/PRoot code if present

Determine exactly what is:

```text
WORKING
PARTIALLY WORKING
BROKEN
MISSING
FAKE/MOCKED
```

Then create/update:

```text
docs/IMPLEMENTATION_STATUS.md
docs/ARCHITECTURE.md
docs/KNOWN_ISSUES.md
```

---

# 2. DO NOT BREAK WORKING FEATURES

The current application already has functionality.

Before modifying a feature:

1. inspect it
2. test it
3. preserve working behavior
4. improve it incrementally

Do not replace a working implementation with a fake abstraction merely to simplify development.

---

# 3. NO FAKE SUCCESS

Never show:

```text
✓ Installed
✓ Build successful
✓ GitHub connected
✓ Server started
✓ APK generated
✓ Command completed
```

unless it actually happened.

Every status must come from real backend/runtime state.

---

# 4. NO HIDDEN FAILURES

When something fails, show:

```text
WHAT FAILED
WHY IT FAILED
HOW TO FIX IT
[Retry]
```

Example:

```text
Command unavailable

python3 was not found in the current runtime.

Install the Python toolchain or use an available interpreter.

[Install Python]
[Retry]
```

Do not simply display:

```text
exit 127
```

without explaining it.

---

# 5. UI QUALITY IS NON-NEGOTIABLE

The UI must be **substantially better than the current screenshot**.

Do NOT produce:

- generic AI-generated dashboard UI
- excessive cards
- repetitive tool-call blocks
- giant empty spaces
- tiny text
- random colors
- inconsistent spacing
- inconsistent icons
- excessive borders
- childish gradients
- unnecessary animations
- desktop UI squeezed onto a phone

The final app must look like a premium developer product.

---

# 6. PRODUCT IDENTITY

Name:

# Sufyan Harness

Tagline:

**Build. Code. Run. Ship — from Android.**

Use original branding.

Do not copy Mobile Harness branding, logo, screenshots, or exact visual layout.

---

# 7. PREMIUM UI DESIGN SYSTEM

Create a centralized design system.

## Visual direction

Dark-first developer workstation.

Use:

- near-black base
- slightly lighter surfaces
- restrained cyan/teal accent
- subtle separators
- professional typography
- monospace font for code/terminal
- clean Material-style controls
- moderate corner radius
- compact information density
- clear hierarchy

The UI should feel:

```text
VS Code
+
Modern AI Agent
+
Android-native polish
```

not:

```text
random Android template
```

---

# 8. GLOBAL UI RULES

Use consistent:

- 4/8-based spacing
- typography scale
- icon sizing
- corner radius
- elevation
- component heights
- animation duration

Touch targets should be comfortable.

Support:

- portrait phones
- different screen sizes
- keyboard
- system font scaling
- dark/light/system theme
- accessibility labels

---

# 9. MAIN NAVIGATION

Use five primary destinations:

```text
Projects
AI
Terminal
Editor
Settings
```

But do not force every feature into bottom navigation.

Contextual screens:

```text
Project Details
Git
Preview
Build
Toolchains
Checkpoints
Files
```

should open from the relevant project.

---

# 10. PROJECT HOME — REDESIGN

The Projects page should feel like a workspace dashboard.

Example:

```text
Sufyan Harness

Your Workspace

[ + New Project ]

Recent

┌────────────────────────────┐
│ Kitchen POS          ⋮     │
│ React • Node               │
│ ● Ready                    │
│ Updated 4 min ago          │
└────────────────────────────┘

┌────────────────────────────┐
│ Weather Web App      ⋮     │
│ HTML • CSS • JS            │
│ ● Ready                    │
│ Updated yesterday          │
└────────────────────────────┘
```

Include:

- search
- sort
- recent projects
- project type
- runtime status
- Git status
- last modified
- storage
- project actions

---

# 11. NEW PROJECT FLOW — IMPORTANT

The current project creation flow must clearly ask what the user is building.

Screen:

```text
Create New Project

What are you building?

┌─────────────────────┐
│ 📱 Android App      │
│ APK / mobile app    │
└─────────────────────┘

┌─────────────────────┐
│ 🌐 Website          │
│ HTML/CSS/JS         │
└─────────────────────┘

┌─────────────────────┐
│ ⚡ Web App           │
│ React / Vite / Node │
└─────────────────────┘

┌─────────────────────┐
│ 🟢 Node.js          │
│ Backend / CLI       │
└─────────────────────┘

┌─────────────────────┐
│ 📦 Empty Project    │
│ Start from scratch  │
└─────────────────────┘
```

Then configure:

```text
Project Name
Framework
Language
Runtime
Template
Git
```

The selected project type must be stored in project metadata.

Example:

```text
type = ANDROID_APP
type = WEBSITE
type = WEB_APP
type = NODE
type = EMPTY
```

Do not claim a template exists unless it actually creates the files.

---

# 12. PROJECT DETAIL SCREEN

When a project opens, show:

```text
Kitchen POS

React • Node • Git
● Runtime Ready

[ AI Build ]
[ Open Editor ]
[ Terminal ]
[ Preview ]

Files
Git
Build
Checkpoints
```

Include project-level actions.

---

# 13. AI AGENT — MAJOR REDESIGN

## THIS IS CRITICAL

The current AI chat should become a real **Agent Workspace**.

The user needs to clearly understand:

```text
WHAT I ASKED
WHAT AI IS THINKING/DOING AT A HIGH LEVEL
WHAT TOOLS IT IS USING
WHAT THE TOOLS RETURNED
WHAT CHANGED
WHAT THE FINAL ANSWER IS
```

Do NOT expose private chain-of-thought.

Instead show concise action summaries.

---

# 14. AI CHAT LAYOUT

Use three conceptual layers:

## A. Conversation

Normal messages:

```text
You
Build a login page.

Sufyan Harness AI
I'll inspect the project and then implement it.
```

## B. Agent Activity

Collapsible execution timeline:

```text
Agent activity

✓ Listed 14 files
✓ Read src/App.jsx
✓ Read src/styles.css
✓ Created Login.jsx
✓ Updated App.jsx
✓ Ran npm run build
✓ Build successful
```

## C. Final Answer

```text
Done.

I created the login page and verified the production build.

Changed:
• Login.jsx
• App.jsx
• styles.css

[Review Changes]
[Open Preview]
```

This is much cleaner than displaying every tool call as a giant repeated card.

---

# 15. TOOL CALL UI

Tool calls should be compact and collapsible.

Example:

```text
✓ read_file
  src/App.jsx

✓ edit_file
  src/App.jsx
  +18 -6

✓ run_command
  npm run build
  Exit 0
```

For an error:

```text
✕ run_command
  npm run build

Exit 127

Command unavailable:
npm was not found.

[Install Node.js]
[Retry]
```

Do not display huge command strings unless the user expands the tool.

---

# 16. AGENT STATE

The AI screen must visibly show:

```text
● Working
● Waiting for confirmation
● Running command
● Installing package
● Building
● Preview running
✓ Complete
✕ Failed
```

When AI is working:

```text
AI is working...

Inspecting project
Editing files
Running build
```

Provide:

```text
[Stop]
```

The Stop button must actually cancel the agent operation where technically possible.

---

# 17. AGENT SESSION SUMMARY

At the end of an agent task:

```text
Task completed

Files changed: 5
Commands run: 4
Build: PASS
Preview: RUNNING

[Review Diff]
[Open Preview]
[Continue]
```

This makes the agent feel like an actual coding agent.

---

# 18. AI AGENT TOOLS

Implement real tools:

```text
list_files
read_file
write_file
edit_file
delete_file
move_file
search_files
run_command
get_process_status
get_terminal_output
start_server
stop_server
detect_project_type
git_status
git_diff
git_log
create_checkpoint
restore_checkpoint
build_project
install_dependency
```

Only expose tools that exist.

---

# 19. AGENT CONTEXT MANAGEMENT

Do not blindly send the entire project to the AI.

Use:

```text
project metadata
relevant files
user-selected files
recent tool results
terminal errors
Git diff
```

Support:

```text
@filename
@folder
Attach file
Current project
```

Limit huge file contents intelligently.

---

# 20. AI AUTONOMOUS BUILD LOOP

When the user asks:

> Build this website.

The agent should be capable of:

```text
Understand request
 ↓
Detect project type
 ↓
Inspect files
 ↓
Plan concise actions
 ↓
Modify files
 ↓
Install dependencies if needed
 ↓
Run build
 ↓
Read errors
 ↓
Fix errors
 ↓
Run build again
 ↓
Start preview
 ↓
Report result
```

Do not stop at:

> "I wrote the code."

---

# 21. TERMINAL — FULL REPAIR

The terminal currently executes some commands but has reliability/compatibility problems.

Audit the terminal end-to-end.

Check:

- shell executable
- PATH
- HOME
- working directory
- environment variables
- process creation
- stdout
- stderr
- exit codes
- command cancellation
- session persistence
- runtime initialization
- available binaries
- permissions
- PRoot/rootfs state

---

# 22. TERMINAL MUST SHOW REAL ENVIRONMENT HEALTH

Create:

```text
Terminal Health

Shell       ✓
Runtime     ✓
PATH        ✓
Home        ✓
Git         ✓ 2.x
Node        ✓ XX
npm         ✓ XX
Python      ✕ Not installed
curl        ✓
```

A user should immediately know why a command fails.

---

# 23. TERMINAL COMMAND DIAGNOSTICS

Exit code 127 generally means a command/executable could not be found.

Do not hardcode that assumption for every failure.

Actually inspect:

```text
command -v <command>
which <command>
echo $PATH
<command> --version
```

where safe.

Then explain the actual problem.

Example:

```text
Command failed

curl was not found.

Detected runtime:
Ubuntu userspace

Suggested fix:
Install the base networking tools.

[Install / Repair Runtime]
```

If Python is missing:

```text
python3: not found

Python toolchain is not installed.

[Install Python]
```

---

# 24. TERMINAL SETTINGS MUST ACTUALLY WORK

Audit every terminal setting.

Possible settings:

```text
Font size
Shell
Command history
Scrollback limit
Word wrap
Cursor style
Working directory
Environment variables
Clear on new session
```

If a setting is visible, it must actually affect behavior.

Remove fake settings.

---

# 25. TERMINAL UI

Make it professional:

```text
Terminal
────────────────────────────

Workspace: Kitchen POS
/home/projects/kitchen-pos

$ npm run build

> kitchen-pos build
> vite build

✓ built successfully

────────────────────────────
$ _
```

Toolbar:

```text
+ New session
⌘ History
↻ Restart
□ Stop
⌫ Clear
```

Support selectable/copyable output.

---

# 26. TERMINAL SESSION MANAGER

Support multiple sessions:

```text
Terminal Sessions

● bash — Kitchen POS
● dev server — port 5173
○ bash — Sufyan AI
```

Each session tracks:

- PID/process
- working directory
- status
- output
- start time

---

# 27. LINUX / PRoot RUNTIME

Implement a real private userspace runtime where supported.

Architecture:

```text
Android
 ↓
Foreground Runtime Service
 ↓
Native/process bridge
 ↓
PRoot userspace
 ↓
Linux shell
 ↓
Tools
```

Do not claim PRoot is a hardened VM.

Base environment should provide, where supported:

```text
shell
coreutils
Git
Node.js
npm
curl
OpenSSL
```

Optional:

```text
Python
Java/JVM
C/C++
PHP
```

---

# 28. RUNTIME REPAIR SYSTEM

Add:

```text
Runtime Health
[ Run Diagnostics ]

If broken:

[ Repair Runtime ]
[ Reinstall Runtime ]
[ Clear Cache ]
```

Diagnostics should test actual components.

Example:

```text
Filesystem       ✓
PRoot            ✓
Shell            ✓
PATH             ✓
Git              ✓
Node             ✓
npm              ✓
Python           ✕
```

---

# 29. GITHUB INTEGRATION — NEW MAJOR FEATURE

The user must be able to connect a project to GitHub.

Provide:

```text
GitHub
[ Connect GitHub ]

Repositories
[ Select Repository ]

Branch
[ main ▼ ]

[ Clone ]
[ Pull ]
[ Push ]
```

Support common workflows:

```text
Connect account/credentials
Clone repository
Open repository
Pull
Commit
Push
Create branch
Switch branch
View commits
View diff
```

---

# 30. GITHUB SECURITY

Never store GitHub credentials as plaintext.

Use secure storage.

Prefer an official OAuth/device authorization flow if practical.

If using a Personal Access Token:

- encrypted storage
- masked input
- never log it
- never send it to the AI
- never display it in terminal output
- provide revoke/remove action

The AI must not automatically obtain or expose the GitHub credential.

---

# 31. GITHUB UI

Project:

```text
GitHub

● Connected

Repository
sufyan/project-name

Branch
main

Status
↑ 2 commits
↓ 0 commits

[Pull]
[Push]
[Commit]
```

Show conflicts clearly.

---

# 32. GIT CONFLICT RESOLUTION

If pull causes conflicts:

```text
Merge conflict

3 files need attention

App.jsx
package.json
README.md

[Open Conflict Resolver]
```

Provide a usable mobile conflict UI.

Do not silently choose one side.

---

# 33. AI + GITHUB

The AI may assist with Git operations, but safety is mandatory.

AI can suggest:

```text
Create branch
Commit changes
Prepare commit message
Show diff
```

For potentially destructive operations, require confirmation.

Never allow an AI agent to silently force-push.

Never expose credentials.

---

# 34. ANDROID APP PROJECT TYPE — NEW MAJOR FEATURE

If the user chooses:

```text
Android App
```

the project must use an Android-compatible project structure.

The app should identify:

```text
Android Project
Gradle
Manifest
Source
Resources
```

Provide:

```text
[ Build APK ]
[ Install APK ]
[ Build & Install ]
```

---

# 35. ANDROID BUILD SYSTEM

The runtime/toolchain must contain the required Android build dependencies for actual Android builds.

Do not claim Android APK support if:

- Gradle is missing
- JDK is missing
- Android SDK is missing
- required SDK platforms are missing
- build tools are missing

Create a diagnostic:

```text
Android Build Environment

JDK             ✓
Gradle          ✓
Android SDK     ✓
Platform SDK    ✓
Build Tools     ✓

Status: Ready
```

If something is missing:

```text
Android SDK platform not installed.

[Install Required SDK]
```

---

# 36. BUILD SCREEN

Create a proper Build screen:

```text
Build

Project:
My Android App

Variant:
Debug ▼

[ Build APK ]

Build output:

> Task :app:compileDebugKotlin
> Task :app:packageDebug

✓ APK generated

app-debug.apk
12.4 MB

[ Install ]
[ Share ]
[ Open Folder ]
```

No fake success.

---

# 37. APK INSTALLATION

For an Android app project:

```text
Build APK
 ↓
Verify APK
 ↓
Install
```

Use Android's supported package installation flow.

Handle:

- install permission
- incompatible APK
- package conflict
- failed installation
- architecture mismatch
- signature mismatch

If installation cannot happen automatically due to Android security restrictions, provide the correct system installation flow rather than pretending installation succeeded.

---

# 38. BUILD → CHANGE → REBUILD → REINSTALL

This must be a complete workflow.

Example:

```text
User:
Change button color.

AI:
Edits code.

Build:
✓ APK generated.

[Install Update]

Android:
Existing app detected.

Update package?

[Install]
```

The user must be able to repeatedly:

```text
Edit
 ↓
Build
 ↓
Install
 ↓
Test
 ↓
Edit again
 ↓
Build
 ↓
Install again
```

---

# 39. APK ARTIFACT MANAGEMENT

Every successful build should be discoverable.

```text
Build Artifacts

app-debug.apk
app-release.apk
```

Actions:

```text
Install
Share
Open
Delete
```

Show:

- version
- build type
- size
- created time
- package name

---

# 40. WEBSITE / WEB APP PROJECT TYPE

For:

```text
Website
Web App
React
Vite
Node
```

provide:

```text
[ Run ]
[ Preview ]
[ Download Project ]
[ Export ZIP ]
```

---

# 41. WEBSITE DOWNLOAD / EXPORT

The user must be able to download the code created by the AI.

Options:

```text
Export

○ Entire project ZIP
○ Source only
○ Production build
○ Selected files

[ Export ]
```

The generated ZIP must contain the actual files.

Never create a fake ZIP.

---

# 42. WEB BUILD EXPORT

If a web project supports production builds:

```text
npm run build
```

Then:

```text
dist/
```

can be exported.

Provide:

```text
[Download Source ZIP]
[Download Production ZIP]
```

Only show production download if the build actually succeeded.

---

# 43. WEB PREVIEW

Flow:

```text
Project
 ↓
Detect dev server
 ↓
Start
 ↓
Detect port
 ↓
WebView
```

UI:

```text
Preview

● Running
127.0.0.1:5173

[Refresh] [Stop] [Restart]

┌────────────────────────┐
│                        │
│      LIVE WEBSITE      │
│                        │
└────────────────────────┘
```

---

# 44. PREVIEW ERROR REPORTING

If website crashes:

```text
Preview failed

Server exited with code 1.

Last error:
Module not found...

[Ask AI to Fix]
[Open Terminal]
```

The AI should be able to receive the actual error and fix it.

---

# 45. PROJECT TYPE AWARENESS

The agent must detect project type.

Examples:

```text
Android
package.json + vite.config
HTML
Node
Python
```

Use project metadata plus filesystem detection.

The AI should know:

```text
How to build
How to run
How to preview
What dependencies are required
```

without inventing commands.

---

# 46. SMART COMMAND PLANNER

Do not blindly execute:

```text
npm run build
```

if the project is not a Node project.

Instead inspect:

```text
package.json
build.gradle
settings.gradle
requirements.txt
pyproject.toml
index.html
```

Then choose the correct workflow.

---

# 47. AI BUILD / FIX MODE

Add a high-level action:

```text
🤖 Build & Fix
```

The agent:

```text
Inspect
 ↓
Build
 ↓
Read error
 ↓
Fix
 ↓
Build again
 ↓
Repeat within safe limit
 ↓
Report
```

Avoid infinite loops.

Use a configurable maximum attempt count.

---

# 48. AI CONFIRMATION MODES

Provide:

```text
Agent permissions

○ Ask before every change
○ Ask before destructive actions
● Auto-approve safe actions
```

Safe operations may include:

- reading
- searching
- diagnostics

Mutating/destructive operations should follow configured permissions.

---

# 49. CHAT HISTORY

Persist conversations per project.

Example:

```text
Project
 ├── New dashboard
 ├── Fix login bug
 ├── Build release
 └── GitHub sync
```

Allow:

- rename conversation
- delete
- search
- continue conversation

---

# 50. COST / USAGE INFORMATION

Where OpenRouter response data supports it, show:

```text
Model
Tokens
Approx. cost
Latency
```

Do not invent cost data.

Keep this optional and compact.

---

# 51. NOTIFICATIONS

For long-running tasks:

```text
Build completed
AI task completed
Build failed
Runtime installation completed
```

Only request the minimum required Android permissions.

---

# 52. STORAGE MANAGER

Show:

```text
Storage

Projects       1.8 GB
Linux Runtime  2.4 GB
Toolchains     850 MB
Build Cache    620 MB

Total          5.67 GB
```

Allow safe cleanup:

```text
Clear build cache
Clear terminal logs
Remove unused toolchain
```

Never delete project files silently.

---

# 53. SECURITY / PRIVACY AUDIT

Check:

- API keys
- GitHub credentials
- logs
- exported files
- runtime
- storage permissions
- network requests
- temporary files

No credentials in:

```text
logs
AI prompts
terminal history
Git commits
error messages
analytics
```

---

# 54. PERFORMANCE

Do not block the UI thread.

Handle:

- large projects
- large files
- long terminal output
- streaming AI responses
- multiple processes

Use bounded terminal logs and sensible resource management.

---

# 55. OFFLINE MODE

The application should open without internet.

Offline capabilities:

```text
Browse projects
Edit files
Use installed local runtime
Git local operations
Build local projects when dependencies exist
```

Network-dependent features:

```text
OpenRouter
GitHub
Downloads
```

must show a clear offline state.

---

# 56. RECOVERY

If app is killed during:

- runtime installation
- build
- server start
- AI task
- Git operation

the app should recover safely where technically possible.

Never leave corrupted state without recovery.

---

# 57. DATABASE / PERSISTENCE

Persist:

```text
Projects
Project type
Project paths
AI conversations
Model settings
Terminal sessions metadata
Git metadata
Build artifacts metadata
Checkpoints
Toolchain state
User preferences
```

Keep secrets in secure storage, not normal database fields.

---

# 58. TESTING STRATEGY

Create tests for:

## AI
- OpenRouter connection
- streaming
- cancellation
- malformed responses
- tool calls

## Agent
- read file
- edit file
- command
- build
- error recovery

## Terminal
- valid command
- invalid command
- missing executable
- exit code
- cancellation
- persistent working directory

## Projects
- create
- rename
- delete
- import
- export

## Git
- status
- diff
- commit
- branch
- pull
- push
- conflicts

## Build
- website
- web app
- Android APK

## UI
- navigation
- project creation
- AI chat
- terminal
- settings
- build
- preview

---

# 59. REAL DEVICE TESTING

If an Android device/emulator is available:

Test:

```text
Launch
Create project
Open project
Edit file
Run command
Chat with AI
AI changes file
Build
Preview
Export ZIP
GitHub
Build APK
Install APK
Reopen app
```

Do not claim device installation succeeded without actually verifying it.

---

# 60. PHASES

## PHASE 0 — COMPLETE AUDIT

Audit everything.

Output:

```text
WORKING
PARTIAL
BROKEN
MISSING
MOCKED
```

Do not start a massive rewrite until the audit is complete.

---

## PHASE 1 — UI/UX REBUILD

Redesign:

- Projects
- Project details
- AI Agent
- Terminal
- Editor
- Settings
- dialogs
- empty states
- loading states
- errors

Make it premium.

---

## PHASE 2 — PROJECT TYPE SYSTEM

Implement:

- Android App
- Website
- Web App
- Node
- Empty

Add proper project metadata and creation flow.

---

## PHASE 3 — PROJECT + FILE SYSTEM

Repair and verify:

- CRUD
- import/export
- file browser
- search
- storage

---

## PHASE 4 — CODE EDITOR

Repair and improve:

- syntax
- tabs
- search
- replace
- save
- undo
- AI actions

---

## PHASE 5 — OPENROUTER

Implement/repair:

- secure API key
- model selector
- streaming
- cancellation
- errors
- retry
- conversation persistence

---

## PHASE 6 — TRUE AI AGENT

Implement:

- agent loop
- tools
- context
- activity timeline
- permission modes
- build/fix loop

---

## PHASE 7 — TERMINAL REPAIR

Do a full backend/runtime audit.

Make terminal commands genuinely reliable.

Implement diagnostics and tool detection.

Every visible terminal setting must work.

---

## PHASE 8 — LINUX / PRoot RUNTIME

Implement/repair:

- shell
- filesystem
- PATH
- Git
- Node
- npm
- curl
- OpenSSL

Then optional toolchains.

---

## PHASE 9 — GITHUB

Implement:

- connect
- repositories
- clone
- pull
- push
- branch
- commit
- diff
- conflicts

Secure credentials.

---

## PHASE 10 — WEB PREVIEW + EXPORT

Implement:

- server start
- port detection
- WebView
- refresh
- stop
- restart
- console
- error forwarding
- source ZIP
- production ZIP

---

## PHASE 11 — ANDROID BUILD / INSTALL

Implement:

- JDK detection
- Gradle detection
- Android SDK detection
- SDK/build tools detection
- build APK
- verify APK
- install APK
- reinstall updated APK
- artifact management

---

## PHASE 12 — GIT / DIFF / CHECKPOINTS

Implement/repair:

- diff
- checkpoint
- restore
- history
- AI change review

---

## PHASE 13 — BACKGROUND RUNTIME

Implement safe Android-compatible foreground runtime behavior for:

- terminal
- build
- server
- runtime installation
- long AI tasks

---

## PHASE 14 — SECURITY + PERFORMANCE

Full audit.

No leaked secrets.

No unnecessary permissions.

No UI-thread blocking.

No runaway processes.

---

## PHASE 15 — FULL QA

Run:

```text
Unit tests
Integration tests
UI tests
Lint
Static analysis
Debug build
Release build
```

Fix all blocking issues.

---

## PHASE 16 — FINAL APK

MANDATORY.

Build:

```bash
./gradlew assembleRelease
```

If release signing is not available:

```bash
./gradlew assembleDebug
```

Verify:

```text
APK exists
APK > 0 bytes
APK is valid
Version correct
Architecture correct
Installable
```

If device/emulator is available:

```text
Install
Launch
Smoke test
```

Then provide the APK artifact.

---

# 61. FINAL USER FLOWS THAT MUST WORK

## FLOW A — WEBSITE

```text
New Project
 ↓
Website
 ↓
AI: Build a modern portfolio
 ↓
AI creates files
 ↓
npm/build if required
 ↓
Preview
 ↓
User requests change
 ↓
AI edits
 ↓
Preview updates
 ↓
Export ZIP
 ↓
Download
```

---

## FLOW B — WEB APP

```text
New Project
 ↓
Web App
 ↓
React/Vite/etc.
 ↓
AI builds
 ↓
Install dependencies
 ↓
Run
 ↓
Preview
 ↓
Fix errors
 ↓
Production build
 ↓
Download ZIP
```

---

## FLOW C — ANDROID APP

```text
New Project
 ↓
Android App
 ↓
AI builds project
 ↓
Install required toolchains
 ↓
Build APK
 ↓
Verify
 ↓
Install on phone
 ↓
Test
 ↓
Ask AI for changes
 ↓
Edit
 ↓
Build again
 ↓
Install update
```

This repeated edit → build → install loop is a core product feature.

---

## FLOW D — GITHUB

```text
Connect GitHub
 ↓
Select repository
 ↓
Clone
 ↓
Open project
 ↓
AI edits
 ↓
Review diff
 ↓
Commit
 ↓
Push
```

---

## FLOW E — AI ERROR FIX

```text
User asks AI to build
 ↓
Build fails
 ↓
AI receives real error
 ↓
AI identifies cause
 ↓
AI edits file
 ↓
Build again
 ↓
Success
```

---

# 62. FINAL UI QUALITY CHECK

Before release, inspect every screen visually.

Ask:

```text
Does this look premium?
Is the hierarchy obvious?
Is the primary action obvious?
Is there unnecessary clutter?
Are cards being overused?
Is text readable?
Does the keyboard work?
Does scrolling work?
Does every button do something real?
```

If the answer is no, fix it.

---

# 63. FINAL DEFINITION OF DONE

The project is NOT complete if:

```text
[ ] Only UI exists
[ ] AI is mocked
[ ] Terminal is mocked
[ ] GitHub is mocked
[ ] Preview is mocked
[ ] APK build is mocked
[ ] Install button only shows a message
[ ] Download creates fake data
[ ] Settings don't affect runtime
[ ] Errors are hidden
[ ] Compilation fails
[ ] Tests fail
[ ] APK is missing
```

Complete means the implemented features actually work.

---

# 64. FINAL APK REQUIREMENT

## THIS IS NON-NEGOTIABLE

At the end:

1. Build the application.
2. Fix build errors.
3. Generate APK.
4. Verify APK.
5. Install/test if possible.
6. Report build type.
7. Report APK path.
8. **Attach/provide the APK artifact.**

Do NOT end with:

> "The APK can be generated later."

Do NOT end with:

> "Run this command yourself."

Do NOT end with source code only.

---

# 65. START NOW

### STEP 1
Read this entire specification.

### STEP 2
Inspect the existing repository.

### STEP 3
Audit the current implementation.

### STEP 4
Fix the existing broken functionality.

### STEP 5
Implement the phases sequentially.

### STEP 6
Continuously build and test.

### STEP 7
Do the final visual polish.

### STEP 8
Run full QA.

### STEP 9
Generate and verify the APK.

### STEP 10
Deliver the APK.

---

# FINAL COMMAND

**START WITH PHASE 0 NOW.**

Do not ask unnecessary questions.

Do not skip the audit.

Do not fake missing functionality.

Do not sacrifice UI quality.

Do not expose secrets.

Do not declare success without verification.

And most importantly:

# **FINISH WITH A WORKING APK.**

# END OF MASTER SPECIFICATION
