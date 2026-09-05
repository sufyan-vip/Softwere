# SUFYAN HARNESS — MASTER BUILD SPECIFICATION
## Premium AI Coding Workspace for Android • OpenRouter • Real Terminal • Linux Runtime • Live Preview • APK

> **MASTER INSTRUCTION — READ THIS FIRST**
>
> You are the lead Android engineer, product designer, UX designer, systems engineer, AI-agent engineer, security engineer, and QA engineer for this project.
>
> Build **Sufyan Harness**, a serious, polished, phone-first AI development environment that lets a user build and manage software directly from Android.
>
> This document is the source of truth for implementation. **Follow the phases in order. Do not skip phases, do not replace real functionality with fake UI, and do not declare the project complete until the final APK has been generated and verified.**
>
> The supplied Mobile Harness README is the reference inspiration for the overall concept: Android-native workspace, projects, AI coding agent, Linux/PRoot runtime, terminal, toolchains, live web preview, secure credentials, file workflow, Git/diffs/checkpoints, and background runtime. Use it as architectural inspiration, but create an original product, original branding, and original UI. Do not copy its logo, screenshots, proprietary code, or exact visual design.

---

# 1. PRODUCT

## Name

**Sufyan Harness**

## Tagline

**AI Development Workspace for Android**

## Product goal

Turn an Android phone into a practical AI-assisted software development workstation.

The user should eventually be able to:

```text
Create Project
      ↓
Tell AI what to build
      ↓
AI inspects project
      ↓
AI creates/edits files
      ↓
AI runs terminal commands
      ↓
AI reads errors
      ↓
AI fixes errors
      ↓
AI runs tests/build
      ↓
Start local server
      ↓
Live Preview
      ↓
Review Diff
      ↓
Accept / Reject / Rollback
```

The product must feel like a **real development environment**, not a chatbot with a terminal attached.

---

# 2. NON-NEGOTIABLE RULES

## 2.1 Follow phases exactly

Implement:

```text
PHASE 0 → PHASE 1 → PHASE 2 → ... → FINAL APK
```

After every phase:

1. Compile/build.
2. Run relevant tests.
3. Check navigation.
4. Test the implemented functionality.
5. Fix blocking errors.
6. Update implementation status.
7. Continue only when the phase is stable.

Do not skip ahead just to make the app appear feature-rich.

---

## 2.2 Never fake functionality

Do NOT use fake:

- AI responses
- terminal output
- project creation
- file operations
- Linux runtime
- package installation
- Git operations
- preview
- API connection
- tool execution
- build results
- checkpoints

A button may exist before a feature is implemented, but it must not falsely report success.

If a feature belongs to a later phase, keep it clearly marked as unavailable or implement it only when its dependencies are ready.

---

## 2.3 Do not destroy existing work

Before changing an existing repository:

- inspect it
- understand its architecture
- identify existing working functionality
- preserve useful code
- avoid unnecessary rewrites
- avoid destructive migrations
- create backups/checkpoints where appropriate

---

## 2.4 Mobile-first is mandatory

The app is designed primarily for Android phones.

It must handle:

- small screens
- large phones
- portrait mode
- software keyboard
- touch input
- scrolling
- long terminal output
- long code files
- dark/light themes
- accessibility font scaling where practical
- Android lifecycle changes
- low-memory situations gracefully

Do not simply shrink a desktop UI onto a phone.

---

## 2.5 Final APK is mandatory

The final task is incomplete without an APK.

At the end:

```text
Build
 ↓
Verify
 ↓
Generate APK
 ↓
Confirm APK exists
 ↓
Validate APK
 ↓
Provide APK artifact
```

If release signing is unavailable, produce the best valid installable debug APK and explicitly report that it is a debug APK.

Do not finish with source code only.

---

# 3. VISUAL / UI QUALITY BAR
# THIS SECTION IS MANDATORY

The UI must **NOT look like a cheap template, generated demo, or generic Android CRUD application.**

Design it like a premium developer product.

## 3.1 Design language

Use:

- modern dark-first interface
- deep near-black background
- subtle elevated surfaces
- restrained accent color
- excellent typography
- consistent spacing
- rounded but not childish components
- subtle borders
- professional icons
- clear hierarchy
- smooth transitions
- compact developer-oriented layouts

Avoid:

- excessive giant cards
- random gradients
- neon overload
- excessive shadows
- huge empty spaces
- inconsistent corner radii
- inconsistent icon sizes
- tiny text
- excessive animations
- meaningless decorative elements
- stock-template appearance

The design should communicate:

**Developer tool + AI workstation + premium Android application.**

---

# 4. UI SYSTEM

Create a centralized design system.

Define:

```text
Colors
Typography
Spacing
Corner radius
Elevation
Icons
Buttons
Inputs
Dialogs
Bottom sheets
Cards
Tabs
Chips
Code blocks
Terminal components
Status indicators
```

Do not define random values on individual screens.

Create reusable components such as:

```text
AppTopBar
ProjectCard
FileRow
CodeEditor
TerminalView
ChatMessage
ToolCallCard
ModelSelector
StatusChip
EmptyState
ErrorState
LoadingState
ConfirmDialog
BottomSheet
PrimaryButton
SecondaryButton
IconButton
```

---

# 5. APP NAVIGATION

Main navigation should be simple.

Recommended:

```text
┌──────────────────────────────────┐
│        SUFYAN HARNESS             │
├──────────────────────────────────┤
│                                  │
│        CURRENT WORKSPACE         │
│                                  │
├──────────────────────────────────┤
│ Projects   AI Chat   Terminal    │
│              Editor              │
│             Settings             │
└──────────────────────────────────┘
```

Do not overload the bottom navigation.

Use contextual navigation for:

- editor
- preview
- Git
- project settings
- toolchains

---

# 6. HOME / PROJECTS UI

The Projects screen should be the home/dashboard.

Include:

```text
Good evening

Your Workspace

[ + New Project ]

Recent Projects

┌──────────────────────────────┐
│ 🟧 Kitchen POS               │
│ React • Node • SQLite        │
│ Updated 4 min ago            │
│                         ⋮    │
└──────────────────────────────┘

┌──────────────────────────────┐
│ 🟦 Sufyan AI                 │
│ Android                      │
│ Updated yesterday            │
│                         ⋮    │
└──────────────────────────────┘
```

Include:

- recent projects
- search
- sort
- project menu
- import
- new project
- project status
- last modified
- storage information

Empty state must look polished.

---

# 7. PROJECT CREATION

New Project flow:

```text
Create Project

Project name
[________________]

Location
[ Workspace      ]

Template
○ Empty
○ HTML/CSS/JS
○ Node.js
○ React
○ Custom

[ Create Project ]
```

Do not pretend templates work if they are not implemented.

---

# 8. AI CHAT UI

This is one of the most important screens.

The experience should resemble a professional AI coding workspace.

Example:

```text
┌──────────────────────────────────┐
│ ← Kitchen POS      GPT Model  ⋮ │
├──────────────────────────────────┤
│                                  │
│ You                              │
│ Build a dashboard for this app.  │
│                                  │
│ AI                               │
│ I'll inspect the project first.  │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ 🔧 list_files                │ │
│ │ Inspecting project...        │ │
│ │ ✓ Completed                 │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ 🔧 edit_file                 │ │
│ │ src/App.jsx                 │ │
│ │ ✓ Completed                 │ │
│ └──────────────────────────────┘ │
│                                  │
├──────────────────────────────────┤
│ Ask AI...                 ↑ Send │
└──────────────────────────────────┘
```

Required:

- streaming text
- stop generation
- retry
- copy
- regenerate where appropriate
- model indicator
- tool activity
- errors
- code blocks
- command blocks
- file references
- compact action summaries

Never expose hidden chain-of-thought.

Show concise summaries such as:

```text
Inspecting project...
Editing 3 files...
Running tests...
Build failed...
Fixing error...
```

---

# 9. AI COMPOSER

The composer should support:

- normal prompt
- multiline prompt
- attach file
- attach project context
- mention file
- cancel
- send
- model selection
- optional reasoning/tool mode indicator

Example:

```text
+ Attach
@ File
# Project

Ask Sufyan Harness AI...

[Model: selected-model ▼]       ↑
```

Keyboard must not cover the composer.

---

# 10. OPENROUTER INTEGRATION

OpenRouter is the primary AI gateway.

Architecture:

```text
UI
 ↓
Chat Repository
 ↓
AI Provider Interface
 ↓
OpenRouter Provider
 ↓
HTTP Client
 ↓
Streaming
 ↓
Agent
```

Required:

- API key
- model ID
- endpoint configuration
- streaming
- cancellation
- timeout
- retries where appropriate
- rate-limit handling
- malformed response handling
- connection errors
- persistent conversations

Never hardcode a single model as the only supported model.

---

# 11. MODEL SELECTOR

Create a polished model selector.

```text
AI Model

Search models...

⭐ Recent
⚡ Fast
💻 Coding
🧠 Reasoning
💰 Low Cost
👑 Premium

[ Model Name ]
Provider / model ID
```

Store the selected model per project if useful.

Architecture must allow additional providers later.

---

# 12. SECURE API KEY STORAGE

Never store API keys as plaintext.

Use Android Keystore-backed encryption or an equally secure Android-native mechanism.

Requirements:

- encrypted at rest
- decrypted only when needed
- never log key
- never send key to analytics
- never display full key
- masked input
- test connection
- delete key
- replace key

Settings should show:

```text
OpenRouter
● Connected

API Key
••••••••••••••••

[ Test Connection ]
[ Replace Key ]
```

---

# 13. AI AGENT ARCHITECTURE

The AI must eventually be a genuine coding agent.

Agent loop:

```text
User Request
    ↓
Model
    ↓
Tool Call
    ↓
Tool Executor
    ↓
Tool Result
    ↓
Model
    ↓
More Tools
    ↓
Final Response
```

Tools:

```text
list_files
read_file
write_file
edit_file
delete_file
search_files
run_command
get_terminal_output
start_server
stop_server
git_status
git_diff
create_checkpoint
restore_checkpoint
```

Only expose tools that are actually implemented.

---

# 14. AI TOOL SAFETY

Classify actions:

### Safe
- list files
- read file
- search files
- git status
- git diff

### Mutating
- write file
- edit file
- rename
- delete
- install package

### Potentially destructive
- recursive delete
- overwrite large sections
- destructive Git operations
- runtime removal

For destructive operations, require explicit confirmation when appropriate.

Do not let an AI tool silently perform dangerous actions without safeguards.

---

# 15. FILE EXPLORER

Create a professional file browser.

Example:

```text
Kitchen POS
────────────────────
📁 src
📁 public
📁 components
📄 package.json
📄 README.md
📄 vite.config.js
```

Actions:

- open
- rename
- delete
- new file
- new folder
- copy
- move
- search
- sort
- refresh

Show hidden files through an option.

---

# 16. CODE EDITOR

The editor must be genuinely useful on a phone.

Features:

- syntax highlighting
- line numbers
- tabs
- search
- replace
- undo
- redo
- save
- unsaved state
- bracket support
- indentation
- copy/paste
- long-line scrolling
- file navigation
- AI actions

AI context actions:

```text
Explain
Fix
Refactor
Optimize
Generate tests
Continue
```

Changes must affect actual files.

---

# 17. DIFF REVIEW

Whenever AI changes files, make the changes reviewable.

Example:

```text
AI changed 4 files

App.jsx
+ 12
- 5

style.css
+ 31
- 8

[ Review Changes ]
```

Diff viewer should show:

- file name
- additions
- removals
- unchanged context
- total changed lines

Actions:

```text
Accept
Reject
Restore
```

---

# 18. CHECKPOINTS / ROLLBACK

Implement project checkpoints.

Example:

```text
Checkpoint 03
Before AI dashboard changes
2 minutes ago

Checkpoint 02
After API integration
20 minutes ago

Checkpoint 01
Initial project
```

Actions:

- create checkpoint
- restore
- delete checkpoint
- compare

Do not silently destroy newer user work during rollback.

---

# 19. TERMINAL UI

Terminal should feel like a real developer terminal.

Example:

```text
Terminal
──────────────────────────

$ npm run build

> project@1.0.0 build
> vite build

✓ built successfully

──────────────────────────
$ _
```

Features:

- persistent session
- command history
- stdout
- stderr
- exit code
- current directory
- clear
- copy
- cancel process
- process status
- scroll to bottom
- selectable text
- keyboard-friendly command input

Use monospace typography.

Do not use fake terminal logs.

---

# 20. REAL LINUX RUNTIME

Target architecture:

```text
Android
 ↓
Foreground Runtime Service
 ↓
Process Bridge
 ↓
PRoot userspace
 ↓
Linux shell
 ↓
Development tools
```

Where technically appropriate, support an ARM64 private Linux userspace without requiring root.

Base tools:

```text
shell
coreutils
Git
Node.js
npm
curl
OpenSSL
```

Optional toolchains:

```text
Python
Java/JVM
C/C++
PHP
```

Do not claim VM/container-level isolation when using PRoot.

Do not claim Docker/KVM/systemd support unless genuinely implemented.

---

# 21. RUNTIME INSTALLER

Create a first-run runtime setup wizard.

Steps:

```text
1. Device readiness
2. Storage check
3. Architecture check
4. Runtime installation
5. Toolchain selection
6. AI provider setup
7. Finish
```

Show:

- progress
- download size
- installed size
- current operation
- retry
- error explanation
- storage requirement

Handle interrupted downloads safely.

Verify downloaded runtime assets/checksums where practical.

Never leave the user with a half-installed runtime without a recoverable state.

---

# 22. TOOLCHAIN MANAGER

Screen:

```text
Toolchains

✓ Git
  v2.x

✓ Node.js
  vXX

○ Python
  Not installed

○ Java
  Not installed

○ C/C++
  Not installed

○ PHP
  Not installed

[ Manage ]
```

Each tool must have:

- install
- status
- version
- health check
- uninstall where safe

Never display "Installed" unless the executable actually works.

---

# 23. LIVE WEB PREVIEW

Implement:

```text
Project
 ↓
Start development server
 ↓
Detect port
 ↓
Embedded WebView
```

Support common local workflows such as:

- Vite
- Node/Express
- compatible HTTP servers

UI:

```text
Preview
────────────────────

http://127.0.0.1:5173

[ ↻ Refresh ] [ Stop ]

┌──────────────────────────┐
│                          │
│      LIVE WEBSITE        │
│                          │
└──────────────────────────┘

Console
✓ Server running
```

Features:

- refresh
- stop
- restart
- port display
- basic console/error telemetry
- navigation controls where useful

---

# 24. GIT

Implement real Git integration.

Support where runtime allows:

```text
git init
git status
git diff
git log
git branch
git checkout
git add
git commit
```

UI should expose safe/common operations without forcing users to type every command.

Show:

```text
Changes
3 modified
1 new
0 deleted
```

---

# 25. BACKGROUND RUNTIME

Use Android-compatible foreground services where required for:

- long-running terminal processes
- development servers
- runtime installation
- legitimate long-running agent operations

Requirements:

- visible state
- stop action
- graceful shutdown
- lifecycle handling
- battery optimization guidance where relevant
- no hidden persistent behavior

Follow the target Android version's current permission and foreground-service requirements.

---

# 26. PROJECT SETTINGS

Each project may have:

```text
Project Settings

General
 ├─ Name
 ├─ Location
 └─ Default shell

AI
 ├─ Model
 ├─ System prompt
 └─ Agent permissions

Runtime
 ├─ Node
 ├─ Python
 └─ Environment

Git
 ├─ Repository
 └─ Branch

Preview
 ├─ Port
 └─ Auto-start
```

---

# 27. GLOBAL SETTINGS

Settings must be organized and clean.

Sections:

```text
AI
Runtime
Toolchains
Appearance
Editor
Terminal
Security
Storage
Notifications
About
```

Include:

### AI
- provider
- OpenRouter key
- model
- generation settings
- system prompt

### Appearance
- dark
- light
- system
- accent

### Editor
- font size
- line numbers
- word wrap
- tab size

### Terminal
- font size
- shell
- history

### Security
- credentials
- clear credentials
- privacy information

---

# 28. ERROR HANDLING

Every failure must be understandable.

Bad:

```text
Error 500
```

Better:

```text
OpenRouter connection failed

The server did not respond.
Check your internet connection and API key.

[ Retry ]
```

Terminal:

```text
Command failed

Exit code: 1

npm could not find package.json.

[ Open Project Folder ]
```

Runtime:

```text
Runtime installation interrupted

Downloaded files are incomplete.
You can safely retry the installation.

[ Resume ]
```

Never swallow errors.

---

# 29. EMPTY / LOADING / OFFLINE STATES

Every important screen needs:

### Empty state
Useful explanation + action.

### Loading state
Progress indicator + meaningful status.

### Error state
Explanation + recovery action.

### Offline state
Clearly indicate network-dependent features.

No blank screens.

---

# 30. ACCESSIBILITY & USABILITY

Ensure:

- readable text
- touch targets
- content descriptions
- sufficient contrast
- keyboard navigation where practical
- screen-reader-friendly labels
- no critical information communicated only by color

---

# 31. PERFORMANCE

Optimize for phones.

Avoid:

- blocking UI thread
- loading huge files entirely into memory when unnecessary
- rendering massive terminal output at once
- unnecessary recompositions
- repeated API calls
- runaway processes

Use:

- pagination/virtualization where useful
- streaming
- background execution
- bounded logs
- lifecycle-aware coroutines/tasks

---

# 32. DATA PERSISTENCE

Persist:

- projects
- recent projects
- conversations
- model selection
- settings
- checkpoints metadata
- terminal preferences

Do not persist secrets insecurely.

Handle app restart gracefully.

---

# 33. OFFLINE BEHAVIOR

The application itself should still open offline.

Offline:

- projects can be browsed
- local files can be edited
- terminal/runtime can work if installed
- Git can work locally

Network-required features should show clear status:

```text
AI unavailable — no internet connection.
```

Do not crash.

---

# 34. PHASE PLAN

# PHASE 0 — INSPECTION

Inspect the repository.

Check:

- Gradle
- AndroidManifest
- SDK
- dependencies
- source tree
- native code
- tests
- existing UI
- storage
- current build

Create:

```text
docs/ARCHITECTURE.md
docs/IMPLEMENTATION_STATUS.md
```

Do not blindly rewrite the repository.

---

# PHASE 1 — PREMIUM UI FOUNDATION

Build:

- theme
- typography
- spacing
- navigation
- Projects
- AI Chat shell
- Terminal shell
- Settings
- reusable components

At this phase, UI-only controls must clearly indicate unavailable functionality rather than fake success.

Verify:

- navigation
- keyboard
- scrolling
- screen sizes
- dark theme
- visual consistency

---

# PHASE 2 — PROJECTS + FILE SYSTEM

Implement:

- project CRUD
- folders
- files
- import/export
- search
- scoped storage
- persistence

Verify actual filesystem behavior.

---

# PHASE 3 — CODE EDITOR

Implement real editing.

Verify:

- save
- reopen
- syntax highlighting
- search
- undo/redo
- file tabs
- large files

---

# PHASE 4 — OPENROUTER

Implement:

- secure key storage
- provider layer
- model selection
- streaming
- cancellation
- persistence
- errors
- retry

Test with a real API configuration when available.

---

# PHASE 5 — AI AGENT

Implement:

- tool schema
- tool executor
- agent loop
- project context
- tool activity UI
- file tools
- controlled command tools

Test:

```text
User → AI → read file → edit file → result → final response
```

---

# PHASE 6 — REAL TERMINAL

Implement:

- process execution
- streaming output
- stderr
- exit code
- persistent sessions
- command history
- cancellation
- working directory

Do not fake output.

---

# PHASE 7 — LINUX / PRoot RUNTIME

Implement the private Linux userspace/runtime.

Prioritize:

```text
shell
Git
Node
npm
curl
OpenSSL
```

Then optional packs.

Verify actual commands.

---

# PHASE 8 — LIVE PREVIEW

Implement:

- local server detection
- port handling
- WebView
- refresh
- stop/restart
- console/error display

Verify using a real project.

---

# PHASE 9 — GIT / DIFF / CHECKPOINTS

Implement:

- Git integration
- status
- diff
- history
- checkpoints
- rollback
- AI change review

Verify rollback does not corrupt the project.

---

# PHASE 10 — TOOLCHAIN MANAGER

Implement:

- detection
- install
- version
- health
- optional packs

No fake installed states.

---

# PHASE 11 — BACKGROUND RUNTIME

Implement lifecycle-safe background/foreground runtime behavior.

Verify:

- app minimize
- restore
- process continuation where allowed
- stop
- cleanup

---

# PHASE 12 — SECURITY / PRIVACY

Audit:

- API keys
- logs
- permissions
- storage
- destructive actions
- runtime boundaries
- network behavior

Remove accidental secret exposure.

---

# PHASE 13 — PREMIUM UX POLISH

Perform a complete visual pass.

Fix:

- spacing
- typography
- icons
- navigation
- dialogs
- animation
- empty states
- loading states
- error states
- keyboard behavior
- accessibility
- dark/light themes

The app must look like a finished product.

---

# PHASE 14 — TESTING

Run:

```text
Unit tests
Integration tests
UI tests
Lint/static analysis
Debug build
Release build
```

Test real flows:

```text
Create project
 ↓
Create file
 ↓
Edit file
 ↓
Save
 ↓
Open AI
 ↓
Send prompt
 ↓
AI tool call
 ↓
File modification
 ↓
Terminal
 ↓
Build
 ↓
Preview
 ↓
Diff
 ↓
Checkpoint
 ↓
Restore
```

Fix all blocking errors.

---

# PHASE 15 — FINAL RELEASE / APK

THIS PHASE IS MANDATORY.

Perform:

```text
Clean
 ↓
Build
 ↓
Test
 ↓
Generate APK
 ↓
Verify APK
```

Prefer:

```bash
./gradlew assembleRelease
```

If release signing is unavailable:

```bash
./gradlew assembleDebug
```

Then verify:

```text
APK exists
APK size > 0
APK is a valid Android package
Correct architecture is present where applicable
Application version is correct
```

If an emulator/device is available:

```text
Install APK
Launch APK
Smoke test
```

Record:

```text
Application:
Sufyan Harness

Version:
...

Build:
Release / Debug

APK:
<exact path>

Architecture:
...

Tests:
PASS / FAIL

Lint:
PASS / FAIL

APK validation:
PASS / FAIL
```

**The final response MUST provide the generated APK artifact when the environment supports file attachments.**

---

# 35. FINAL DEFINITION OF DONE

The project is NOT complete if:

```text
[ ] UI only
[ ] Fake AI
[ ] Fake terminal
[ ] Fake Linux
[ ] Fake preview
[ ] Fake tool installation
[ ] Broken navigation
[ ] API key stored insecurely
[ ] Compilation errors
[ ] Tests failing
[ ] APK missing
```

The project is complete only when the implemented phases are genuinely functional and:

```text
✓ Premium Android UI
✓ Projects
✓ File system
✓ Code editor
✓ OpenRouter
✓ AI coding agent
✓ Tool calling
✓ Real terminal
✓ Linux/PRoot runtime where implemented
✓ Node/npm/Git
✓ Live preview
✓ Diff
✓ Checkpoints
✓ Toolchain manager
✓ Secure credentials
✓ Background runtime where required
✓ Tests
✓ Final APK
```

---

# 36. FINAL INSTRUCTION TO THE IMPLEMENTING AI

**START NOW.**

1. Read this entire document.
2. Inspect the repository.
3. Start with PHASE 0.
4. Do not skip phases.
5. Do not replace difficult functionality with fake functionality.
6. Keep the UI premium and mobile-first.
7. Keep the architecture modular.
8. Preserve existing working code.
9. Verify every phase.
10. Fix errors before moving forward.
11. Maintain `docs/IMPLEMENTATION_STATUS.md`.
12. Build the application continuously during development.
13. Complete the final APK phase.
14. Verify the APK.
15. Deliver the APK.

## CRITICAL FINAL REQUIREMENT

> **SOURCE CODE ALONE IS NOT ACCEPTED AS THE FINAL DELIVERABLE.**
>
> **A WORKING INSTALLABLE APK MUST BE GENERATED AND DELIVERED AT THE END.**

# END OF MASTER BUILD SPECIFICATION
