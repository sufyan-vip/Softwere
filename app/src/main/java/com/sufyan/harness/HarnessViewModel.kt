package com.sufyan.harness

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sufyan.harness.ai.*
import com.sufyan.harness.data.*
import com.sufyan.harness.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ToolActivity(
    val id: String,
    val name: String,
    val summary: String,
    val done: Boolean = false,
    val ok: Boolean = true,
    val detail: String = "",
    /** Path for file tools, command line for run_command — taken from the real tool call. */
    val target: String = "",
)

/**
 * §16 of the V3 spec: the AI screen must show what the agent is doing right now. Every value
 * below is derived from an actual event stream (tool names / the command being run), never
 * animated on a timer, so a stalled request cannot look busy and a finished one cannot.
 */
enum class AgentPhase(val label: String, val busy: Boolean = true) {
    Idle("Idle", false),
    Thinking("AI is working"),
    Inspecting("Inspecting project"),
    Editing("Editing files"),
    Running("Running command"),
    Installing("Installing package"),
    Building("Building"),
    Verifying("Verifying build"),
    WaitingApproval("Waiting for your approval"),
    Complete("Complete", false),
    Failed("Failed", false),
    ;
}

@Serializable
data class UiMessage(
    val role: String,          // "user" | "assistant" | "error" | "status"
    var text: String,
    var tools: List<ToolActivity> = emptyList(),
    var usage: Usage? = null,  // §50 — only set when the provider reports real token usage
    val timestamp: Long = System.currentTimeMillis(),
)

data class OpenTab(val path: String, var content: String, var dirty: Boolean = false)

/** §52 — one snapshot of storage, computed from real directories, never estimated. */
data class StorageSnapshot(
    val projectsTotal: Long,
    val runtimeSize: Long,
    val exportsSize: Long,
    val buildCacheSize: Long = 0,
    val projects: List<Pair<String, Long>> = emptyList(),
)

/** §31 — everything the GitHub screen needs about the active project's link. */
data class GitHubState(
    val user: GitHubUser? = null,
    val repos: List<GitHubRepo> = emptyList(),
    val branches: List<String> = emptyList(),
    val commits: List<GitHubCommit> = emptyList(),
    val status: SyncStatus? = null,
    val conflicts: List<SyncConflict> = emptyList(),
    val busy: String? = null,
    val error: String? = null,
    val lastResult: String? = null,
)

/** §36 — build screen state, driven entirely by the real build process. */
data class BuildState(
    val environment: BuildEnvironment? = null,
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val artifacts: List<BuildArtifact> = emptyList(),
    val outcome: BuildOutcome? = null,
)

class HarnessViewModel(app: Application) : AndroidViewModel(app) {

    // Cast here instead of AndroidViewModel.getApplication<HarnessApp>(), whose
    // reified form only exists in some lifecycle artefact versions.
    private val application: HarnessApp = app as HarnessApp
    val workspace get() = application.workspace
    val settings get() = application.settings
    val secure get() = application.secure
    val provider get() = application.provider
    val linux get() = application.linux
    val toolchains get() = application.toolchains
    val github get() = application.github
    val tasks get() = application.tasks
    val builder get() = application.builder
    val envHealth get() = application.envHealth
    val runtimeRepair get() = application.runtimeRepair
    val recovery get() = application.recovery
    val crashLog get() = application.crashLog

    /** §55 — live connectivity, so network features can explain themselves instead of timing out. */
    val online get() = application.connectivity.online

    fun isOnline(): Boolean = application.connectivity.currentlyOnline()

    fun offlineReason(feature: String): String = application.connectivity.offlineReason(feature)

    private val json = Json { ignoreUnknownKeys = true }

    // ---- projects -----------------------------------------------------------
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _active = MutableStateFlow<Project?>(null)
    val active = _active.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    /** Pre-filled prompt that the next visit to the chat consumes (editor AI actions, §4). */
    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt = _pendingPrompt.asStateFlow()

    fun notify(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    fun setPendingPrompt(text: String) { _pendingPrompt.value = text }
    fun consumePendingPrompt(): String? = _pendingPrompt.value.also { _pendingPrompt.value = null }

    // undo history for editor tabs: path -> stack of previous contents (cap 50)
    private val undoStacks = mutableMapOf<String, MutableList<String>>()

    fun undoTab(path: String) {
        val tab = _tabs.value.find { it.path == path } ?: return
        val stack = undoStacks[path] ?: return
        if (stack.isEmpty()) return
        val prev = stack.removeAt(stack.lastIndex)
        _tabs.value = _tabs.value.map { if (it.path == path) it.copy(content = prev, dirty = true) else it }
        notify("Undid last edit in $path")
    }

    fun canUndo(path: String): Boolean = (undoStacks[path]?.isNotEmpty()) == true

    val projectDir: File? get() = _active.value?.let { workspace.projectDir(it) }
    val files: ProjectFiles? get() = projectDir?.let { ProjectFiles(it) }

    /** §56 — operations that were still running when the process was last killed. */
    private val _interrupted = MutableStateFlow<List<Recovery.Interrupted>>(emptyList())
    val interrupted = _interrupted.asStateFlow()

    /** The uncaught exception that killed the previous run, if there was one. */
    private val _lastCrash = MutableStateFlow<CrashLog.Report?>(null)
    val lastCrash = _lastCrash.asStateFlow()

    /** Marks the crash report as read; the file is deleted so it is shown exactly once. */
    fun dismissCrashReport() {
        runCatching { crashLog.clear() }
        _lastCrash.value = null
    }

    /** Dismisses the recovery notice; the markers are cleared so it is shown exactly once. */
    fun dismissRecovery() {
        recovery.clear()
        _interrupted.value = emptyList()
    }

    /**
     * Startup work that used to live in an `init` block and crashed the app on every launch after
     * the first one.
     *
     * Two things were wrong with doing this in the constructor. Kotlin initialises properties in
     * declaration order, so calling [open] from `init` wrote to `_tabs`, `_messages`, `_git`,
     * `_buildState` and friends while they were still `null` — an immediate
     * `NullPointerException: ... MutableStateFlow.setValue ... on a null object reference`, which
     * `ViewModelProvider` rethrows as *Cannot create an instance of class HarnessViewModel*. And
     * anything that throws in a view model constructor takes the whole activity down before a
     * single pixel is drawn, so there is no screen left to report the failure on.
     *
     * So startup is now an explicit call the UI makes once it is composed, it runs after every
     * property exists, and every step is individually guarded: a broken project or an unreadable
     * conversation shows a message and leaves you on the project list (RULE 4).
     */
    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching { refreshProjects() }
            .onFailure { notify("Could not read the workspace: ${it.message ?: it::class.java.simpleName}") }
        runCatching { recoverFromLastRun() }
        _lastCrash.value = runCatching { crashLog.last() }.getOrNull()
        runCatching {
            (provider as? OpenRouterProvider)?.endpointValue =
                settings.endpoint.ifBlank { OpenRouterProvider.DEFAULT_ENDPOINT }
        }
        restoreLastProject()
    }

    /**
     * Re-opens the project from the previous session, unless [StartupGuard] says that would be
     * unsafe. The marker written around [open] means a crash here can never become a crash loop.
     */
    private fun restoreLastProject() {
        val decision = StartupGuard.decide(
            lastProjectId = settings.lastProjectId,
            pendingRestoreId = settings.pendingRestoreId,
            knownProjectIds = _projects.value.map { it.id },
        )
        when (decision) {
            is StartupGuard.Decision.None -> Unit

            is StartupGuard.Decision.Skip -> {
                settings.pendingRestoreId = null
                settings.lastProjectId = null
                notify(decision.reason)
            }

            is StartupGuard.Decision.Open -> {
                val project = _projects.value.firstOrNull { it.id == decision.projectId } ?: return
                settings.pendingRestoreId = project.id
                runCatching { open(project) }
                    .onFailure {
                        _active.value = null
                        settings.lastProjectId = null
                        notify("Could not open ${project.name}: ${it.message ?: it::class.java.simpleName}")
                    }
                settings.pendingRestoreId = null
            }
        }
    }

    /**
     * §56 — reads the markers left by a killed process, then removes the scratch files that run
     * left behind. Nothing is repaired silently: whatever is found is reported to the user.
     */
    private fun recoverFromLastRun() {
        val pending = runCatching { recovery.pending() }.getOrDefault(emptyList())
        // A preview server never survives the process, so it is expected rather than newsworthy.
        _interrupted.value = pending.filter { it.operation != Recovery.Operation.Server }
        recovery.end(Recovery.Operation.Server)
        runCatching { recovery.sweep(application.cacheDir, workspace.root, exportsDir()) }
    }

    fun refreshProjects() { _projects.value = workspace.list() }

    fun createProject(name: String, template: Template, type: ProjectType = ProjectType.from(null, template.id)): Result<Project> =
        workspace.create(name, template, type).onSuccess {
            refreshProjects()
            open(it)
        }

    /**
     * Opens [project]. Every step that touches disk is guarded on its own: a corrupt conversation
     * file or an unreadable git directory degrades that one thing and still leaves you with an
     * open, usable project instead of taking the app down.
     */
    fun open(project: Project) {
        _active.value = project
        settings.lastProjectId = project.id
        _tabs.value = emptyList()
        _activeTab.value = null
        _messages.value = runCatching { loadConversation(project) }.getOrElse {
            notify("Previous conversation for ${project.name} could not be read; starting a new one.")
            emptyList()
        }
        _git.value = null
        _checkpoints.value = emptyList()
        _changes.value = emptyList()
        _githubState.value = GitHubState(user = _githubState.value.user, repos = _githubState.value.repos)
        _buildState.value = BuildState()
        runCatching { devServer?.dispose() }
        val dir = workspace.projectDir(project)
        devServer = runCatching { DevServer(dir) }.getOrNull()
        changeTracker = runCatching { ChangeTracker(dir) }.getOrNull()
        runCatching { refreshGit() }
        runCatching { refreshCheckpoints() }
    }

    fun closeProject() {
        stopShell()
        devServer?.stop()
        _active.value = null
        settings.lastProjectId = null
    }

    fun deleteProject(project: Project) {
        if (_active.value?.id == project.id) closeProject()
        workspace.delete(project).fold({ notify("Deleted ${project.name}") }, { notify(it.message ?: "Delete failed") })
        refreshProjects()
    }

    // ---- Phase 3 / 10: import / export / storage ----------------------------
    /** §41 — zip the active project into <filesDir>/exports and return the file (real bytes). */
    fun exportProject(): Result<File> = exportWith("zip") { dir, dest -> ProjectArchive.exportZip(dir, dest) }

    /** §41 — source only: no node_modules, build or dist directories. */
    fun exportSourceOnly(): Result<File> = exportWith("source") { dir, dest -> ProjectArchive.exportSource(dir, dest) }

    /** §42 — the production build, only when one really exists on disk. */
    fun exportProduction(): Result<File> = exportWith("production") { dir, dest -> ProjectArchive.exportProduction(dir, dest) }

    /** §41 — exactly the files the user selected. */
    fun exportSelection(paths: Collection<String>): Result<File> =
        exportWith("selection") { dir, dest -> ProjectArchive.exportSelection(dir, dest, paths) }

    private fun exportWith(suffix: String, block: (File, File) -> Result<Unit>): Result<File> {
        val p = _active.value ?: return Result.failure(IllegalStateException("Open a project first."))
        return runCatching {
            val out = File(exportsDir(), "${p.id}-$suffix.zip")
            block(workspace.projectDir(p), out).getOrThrow()
            out
        }
    }

    /** §42 — is there a production build to export? Checked on disk, never assumed. */
    fun hasProductionBuild(): Boolean = projectDir?.let { ProjectArchive.productionDir(it) != null } ?: false

    fun exportsDir(): File = File(application.filesDir, "exports").apply { mkdirs() }

    /** §41 — create a project from a picked zip (content Uri), reading the real bytes. */
    fun importProjectFromUri(name: String, type: ProjectType, uri: Uri): Result<Project> = runCatching {
        val tmp = File(application.cacheDir, "import-${System.currentTimeMillis()}.zip")
        application.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Could not read the selected archive.")
        val result = workspace.createFromZip(name, type, tmp)
        tmp.delete()
        result.getOrThrow()
    }

    /** §41 — create a project from a real folder on disk. */
    fun importProjectFromFolder(name: String, type: ProjectType, dir: File): Result<Project> =
        workspace.createFromFolder(name, type, dir)

    // §52 — storage is computed off the main thread; the screen observes the result.
    private val _storage = MutableStateFlow<StorageSnapshot?>(null)
    val storage = _storage.asStateFlow()

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { storageSnapshot() }
        }
    }

    /** §52 — snapshot of storage, aggregated from real directories. */
    fun storageSnapshot(): StorageSnapshot {
        val projects = workspace.list().map { it to workspace.sizeOf(it) }
        val runtime = application.linux.rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val exports = exportsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val buildCache = workspace.list().sumOf { p ->
            listOf("build", ".gradle", "node_modules/.cache", "dist")
                .map { File(workspace.projectDir(p), it) }
                .filter { it.isDirectory }
                .sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        }
        return StorageSnapshot(
            projectsTotal = projects.sumOf { it.second },
            runtimeSize = runtime,
            exportsSize = exports,
            buildCacheSize = buildCache,
            projects = projects.map { (p, s) -> (p.name to s) },
        )
    }

    /** §52 — safe cleanup: does not touch project source files. */
    fun clearExports(): Result<Unit> = runCatching {
        exportsDir().walkTopDown().filter { it.isFile }.forEach { it.delete() }
        notify("Cleared exported archives.")
        refreshStorage()
    }

    /** §52 — removes build output only. Source files are never deleted. */
    fun clearBuildCache(): Result<Unit> = runCatching {
        var removed = 0L
        workspace.list().forEach { p ->
            listOf("build", ".gradle", "dist", "node_modules/.cache").forEach { rel ->
                val dir = File(workspace.projectDir(p), rel)
                if (dir.isDirectory) {
                    removed += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    dir.deleteRecursively()
                }
            }
        }
        notify("Cleared ${removed / (1024 * 1024)} MB of build output. No source file was touched.")
        refreshStorage()
    }

    /** §52 — clears terminal buffers and the persisted command history. */
    fun clearTerminalLogs(): Result<Unit> = runCatching {
        sessions.info().forEach { sessions.get(it.id)?.clear() }
        _terminalLines.value = emptyList()
        commandHistory.clear()
        settings.clearCommandHistory()
        notify("Terminal output and history cleared.")
    }

    fun renameProject(project: Project, name: String) {
        workspace.rename(project, name).fold({
            refreshProjects()
            if (_active.value?.id == project.id) _active.value = workspace.list().find { it.id == project.id }
        }, { notify(it.message ?: "Rename failed") })
    }

    // ---- editor -------------------------------------------------------------
    private val _tabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val tabs = _tabs.asStateFlow()
    private val _activeTab = MutableStateFlow<String?>(null)
    val activeTab = _activeTab.asStateFlow()
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded = _expanded.asStateFlow()

    fun toggleDir(path: String) {
        _expanded.value = if (path in _expanded.value) _expanded.value - path else _expanded.value + path
    }

    fun openFile(relative: String) {
        val f = files ?: return
        if (_tabs.value.any { it.path == relative }) {
            _activeTab.value = relative
            return
        }
        f.read(relative).fold(
            { content ->
                _tabs.value = (_tabs.value + OpenTab(relative, content)).takeLast(8)
                _activeTab.value = relative
            },
            { notify(it.message ?: "Could not open file") },
        )
    }

    fun updateTab(path: String, content: String) {
        val current = _tabs.value.find { it.path == path }
        // Push the previous content onto the undo stack once per real change, so a keystroke can be
        // undone (capped at 50 steps). No-op when the value did not actually change.
        if (current != null && current.content != content) {
            undoStacks.getOrPut(path) { mutableListOf() }.apply {
                add(current.content)
                if (size > 50) removeAt(0)
            }
        }
        _tabs.value = _tabs.value.map { if (it.path == path) it.copy(content = content, dirty = true) else it }
    }

    fun saveTab(path: String) {
        val f = files ?: return
        val tab = _tabs.value.find { it.path == path } ?: return
        f.write(path, tab.content).fold({
            _tabs.value = _tabs.value.map { if (it.path == path) it.copy(dirty = false) else it }
            _active.value?.let { workspace.touch(it); refreshProjects() }
            notify("Saved $path")
            refreshGit()
        }, { notify(it.message ?: "Save failed") })
    }

    fun closeTab(path: String) {
        _tabs.value = _tabs.value.filterNot { it.path == path }
        if (_activeTab.value == path) _activeTab.value = _tabs.value.lastOrNull()?.path
    }

    fun selectTab(path: String) { _activeTab.value = path }

    fun createFile(relative: String) {
        files?.createFile(relative)?.fold({ notify("Created $relative"); openFile(relative) }, { notify(it.message ?: "Failed") })
    }

    fun createDir(relative: String) {
        files?.createDir(relative)?.fold({ notify("Created $relative/") }, { notify(it.message ?: "Failed") })
    }

    fun deleteEntry(relative: String) {
        files?.delete(relative)?.fold({ notify("Deleted $relative"); closeTab(relative) }, { notify(it.message ?: "Failed") })
    }

    /** Phase 3 — rename a file or directory in the browser; tabs are remapped if needed. */
    fun renameEntry(relative: String, newName: String) {
        val f = files ?: return
        val result = f.rename(relative, newName)
        result.fold({
            val old = if (relative.contains('/')) relative.substringBeforeLast('/') + "/" + newName else newName
            _tabs.value = _tabs.value.map { t ->
                when {
                    t.path == relative -> t.copy(path = old)
                    t.path.startsWith("$relative/") -> t.copy(path = old + t.path.removePrefix(relative))
                    else -> t
                }
            }
            if (activeTab.value?.startsWith("$relative/") == true || activeTab.value == relative) {
                _activeTab.value = _tabs.value.firstOrNull { it.path == old }?.path
                    ?: _tabs.value.firstOrNull { it.path.startsWith(old) }?.path
            }
            notify("Renamed $relative → $newName")
        }, { notify(it.message ?: "Rename failed") })
    }

    // ---- conversation -------------------------------------------------------
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages = _messages.asStateFlow()
    private val _generating = MutableStateFlow(false)
    val generating = _generating.asStateFlow()
    private val _agentPhase = MutableStateFlow(AgentPhase.Idle)
    val agentPhase = _agentPhase.asStateFlow()
    private val _agentStatus = MutableStateFlow("")
    val agentStatus = _agentStatus.asStateFlow()
    private var agentJob: Job? = null
    private val apiHistory = mutableListOf<ChatMessage>()

    /** §48 — the action currently waiting for the user's decision, if any. */
    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval = _pendingApproval.asStateFlow()
    private var approvalGate: CompletableDeferred<Boolean>? = null

    /** §12 — before/after review of what the agent changed this session. */
    private var changeTracker: ChangeTracker? = null
    private val _changes = MutableStateFlow<List<ReviewedChange>>(emptyList())
    val changes = _changes.asStateFlow()

    private fun conversationFile(project: Project) = File(workspace.projectDir(project).parentFile, "${project.id}.chat.json")

    private fun loadConversation(project: Project): List<UiMessage> {
        val f = conversationFile(project)
        if (!f.exists()) return emptyList()
        val loaded = runCatching { json.decodeFromString<List<UiMessage>>(f.readText()) }.getOrElse { emptyList() }
        apiHistory.clear()
        loaded.forEach {
            when (it.role) {
                "user" -> apiHistory += ChatMessage("user", it.text)
                "assistant" -> if (it.text.isNotBlank()) apiHistory += ChatMessage("assistant", it.text)
            }
        }
        return loaded
    }

    private fun persistConversation() {
        val p = _active.value ?: return
        runCatching { conversationFile(p).writeText(json.encodeToString(_messages.value)) }
    }

    fun clearConversation() {
        _messages.value = emptyList()
        apiHistory.clear()
        persistConversation()
    }

    /** §46 — the commands this project genuinely supports, detected from its own files. */
    fun planFor(project: Project): ProjectPlan {
        val dir = workspace.projectDir(project)
        val entries = (dir.listFiles() ?: emptyArray()).map { it.name }.toSet()
        val pkg = File(dir, "package.json").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        return CommandPlanner.plan(entries, pkg)
    }

    fun send(prompt: String) {
        val project = _active.value ?: return notify("Open a project first.")
        val f = files ?: return
        if (prompt.isBlank()) return
        if (!secure.hasApiKey()) {
            _messages.value = _messages.value + UiMessage("error", "No OpenRouter API key configured. Add one in Settings → AI, then retry.")
            return
        }
        // §55 — say "offline" before spending 30 seconds in a socket timeout.
        if (!isOnline()) {
            _messages.value = _messages.value + UiMessage(
                "error",
                offlineReason("The AI model") + " Reconnect and use Retry last message.",
            )
            return
        }

        _messages.value = _messages.value + UiMessage("user", prompt)
        val assistant = UiMessage("assistant", "")
        _messages.value = _messages.value + assistant
        persistConversation()

        val plan = planFor(project)
        if (apiHistory.none { it.role == "system" }) {
            val extra = settings.systemPrompt.trim()
            apiHistory.add(
                0,
                ChatMessage("system", DEFAULT_SYSTEM_PROMPT + "\n\n" + typeContext(project, plan) + if (extra.isNotEmpty()) "\n\n$extra" else ""),
            )
        }
        apiHistory += ChatMessage("user", prompt)

        val dir = workspace.projectDir(project)
        changeTracker?.capture()

        val tools = AgentTools(
            files = f,
            projectDir = dir,
            commandsEnabled = settings.agentCommandsEnabled,
            permission = settings.agentPermission,
            approver = { request -> requestApproval(request) },
            probeFor = { command -> envHealth.probeFor(command, dir, settings.terminalShell) },
            projectSummary = typeContext(project, plan),
        )

        // §20/§47 — verification uses a command the planner actually found; when there is none, the
        // agent simply reports instead of inventing a build step.
        val verifyCommand = plan.of("build") ?: plan.of("test")
        val verification = if (settings.buildFixAttempts > 0 && verifyCommand != null &&
            verifyCommand.command != "(built-in static server)"
        ) {
            Verification(
                command = verifyCommand.command,
                evidence = verifyCommand.evidence,
                maxAttempts = settings.buildFixAttempts,
            ) {
                val res = ShellSession.exec(verifyCommand.command, dir, 240_000, settings.terminalShell)
                VerificationResult(res.ok, res.exitCode, res.combined(6000))
            }
        } else null

        val agent = Agent(
            provider = provider,
            tools = tools,
            contextBudget = settings.contextBudget,
            verification = verification,
            fallbackModel = settings.fallbackModelId,
        )
        val model = project.modelId ?: settings.modelId

        _generating.value = true
        _agentPhase.value = AgentPhase.Thinking
        _agentStatus.value = "Waiting for $model"
        tasks.start(TASK_AGENT, "AI agent working on ${project.name}", RuntimeTask.Kind.Agent)
        recovery.begin(Recovery.Operation.AgentTurn, "${project.name}: ${prompt.take(120)}")
        agentJob = viewModelScope.launch {
            try {
                agent.run(model, apiHistory, settings.temperature).collect { ev ->
                    when (ev) {
                        is AgentEvent.TextDelta -> {
                            if (_agentPhase.value == AgentPhase.Thinking) _agentStatus.value = "Writing answer"
                            mutateLast { it.copy(text = it.text + ev.delta) }
                        }
                        is AgentEvent.Status -> {
                            _agentStatus.value = ev.text
                            mutateLast { it.copy(text = it.text) }
                        }
                        is AgentEvent.ToolStarted -> {
                            _agentPhase.value = phaseFor(ev.name, ev.target)
                            _agentStatus.value = ev.summary
                            mutateLast {
                                it.copy(tools = it.tools + ToolActivity(ev.id, ev.name, ev.summary, target = ev.target))
                            }
                        }
                        is AgentEvent.ToolFinished -> mutateLast { m ->
                            m.copy(
                                tools = m.tools.map { t ->
                                    if (t.id == ev.id) t.copy(done = true, ok = ev.ok, summary = ev.summary, detail = ev.detail) else t
                                },
                            )
                        }
                        is AgentEvent.Verified -> {
                            _agentStatus.value = if (ev.ok) "Verified with ${ev.command}" else "Verification failed — fixing"
                            _agentPhase.value = if (ev.ok) AgentPhase.Complete else AgentPhase.Building
                        }
                        is AgentEvent.Usage -> mutateLast { m -> m.copy(usage = ev.usage) }
                        is AgentEvent.Failed -> {
                            _agentPhase.value = AgentPhase.Failed
                            _agentStatus.value = ev.error.title
                            _messages.value = _messages.value + UiMessage("error", "${ev.error.title}\n\n${ev.error.detail}")
                        }
                        AgentEvent.TurnFinished -> if (_agentPhase.value != AgentPhase.Failed) _agentPhase.value = AgentPhase.Complete
                    }
                }
            } finally {
                _generating.value = false
                resolveApproval(false)
                if (_agentPhase.value.busy) _agentPhase.value = AgentPhase.Complete
                tasks.finish(TASK_AGENT)
                recovery.end(Recovery.Operation.AgentTurn)
                workspace.touch(project)
                refreshProjects()
                refreshGit()
                reloadOpenTabs()
                persistConversation()
                refreshChanges()
                if (settings.notifyOnTaskComplete && tools.changedFiles.isNotEmpty()) {
                    RuntimeService.notifyCompleted(
                        application,
                        "AI task finished",
                        "${tools.changedFiles.size} file(s) changed in ${project.name}.",
                    )
                }
            }
        }
    }

    /** §48 — suspends the tool call until the user approves or declines. */
    private suspend fun requestApproval(request: ApprovalRequest): Boolean {
        val gate = CompletableDeferred<Boolean>()
        approvalGate = gate
        _pendingApproval.value = request
        _agentPhase.value = AgentPhase.WaitingApproval
        _agentStatus.value = request.description
        return gate.await()
    }

    fun resolveApproval(approved: Boolean) {
        val gate = approvalGate ?: return
        approvalGate = null
        _pendingApproval.value = null
        gate.complete(approved)
    }

    /** §48 — turning commands off removes the tool from the schema on the next turn. */
    fun setAgentCommandsEnabled(enabled: Boolean) {
        settings.agentCommandsEnabled = enabled
        notify(if (enabled) "The agent may run commands (subject to your permission mode)." else "The agent can no longer run commands.")
    }

    fun setAgentPermission(permission: AgentPermission) {
        settings.agentPermission = permission
        notify("Agent permission: ${permission.label}")
    }

    /** §12 — recompute the before/after review of the current session. */
    fun refreshChanges() {
        val tracker = changeTracker ?: return
        viewModelScope.launch {
            _changes.value = withContext(Dispatchers.IO) { tracker.review() }
        }
    }

    fun revertChange(change: ReviewedChange) {
        val tracker = changeTracker ?: return
        tracker.revert(change).fold({
            notify("Reverted ${change.path}")
            reloadOpenTabs()
            refreshChanges()
        }, { notify(it.message ?: "Revert failed") })
    }

    fun revertAllChanges() {
        val tracker = changeTracker ?: return
        tracker.revertAll(_changes.value).fold({
            notify("Reverted $it file(s) to the state before this session.")
            reloadOpenTabs()
            refreshChanges()
        }, { notify(it.message ?: "Revert failed") })
    }

    fun acceptChanges() {
        changeTracker?.accept()
        _changes.value = emptyList()
        notify("Changes accepted.")
    }

    /** §47 — the one-tap "Build & Fix" action. */
    fun buildAndFix() {
        val project = _active.value ?: return notify("Open a project first.")
        val plan = planFor(project)
        val cmd = plan.of("build") ?: plan.of("test")
        if (cmd == null) {
            notify("No build command was detected in this project, so there is nothing to verify.")
            return
        }
        send(
            "Run the project's real build command `${cmd.command}` (detected from ${cmd.evidence}). " +
                "If it fails, read the actual error, fix the cause in the code, and build again until it passes " +
                "or you have tried ${settings.buildFixAttempts} times. Report what you changed.",
        )
    }

    /**
     * §45 — the agent gets the project's actual type plus the real commands it can run, so it never
     * invents a build/preview command. Every line is derived from [Project.kind] metadata and from
     * [CommandPlanner], which only reports commands backed by a file on disk.
     */
    private fun typeContext(p: Project, plan: ProjectPlan): String {
        val k = p.kind
        return buildString {
            append("Project: ${p.name} (${k.label})")
            append("\nType: ${k.id}")
            if (k.languages.isNotBlank()) append("\nLanguage: ${k.languages}")
            append("\nDetected stack: ${plan.stack}")
            if (plan.commands.isEmpty()) {
                append("\nNo build or run command was detected in this project's files.")
            } else {
                plan.commands.forEach { c ->
                    append("\n${c.kind.replaceFirstChar { ch -> ch.uppercase() }}: ${c.command}   (evidence: ${c.evidence})")
                }
            }
            plan.notes.forEach { append("\nNote: $it") }
            if (k.requiredTools.isNotEmpty()) append("\nRequires: ${k.requiredTools.joinToString(", ")}")
            append("\nPreview port: ${p.previewPort}")
            append(
                "\nRules for this project: only run a command listed above or one you can see in the project " +
                    "files. Never guess a command. After any edit, run the real build or dev command and report " +
                    "the actual output.",
            )
        }
    }

    /** Maps a tool call onto the state shown in the agent status bar. No guessing, no timers. */
    private fun phaseFor(tool: String, target: String): AgentPhase = when (tool) {
        "list_files", "read_file", "search", "project_info" -> AgentPhase.Inspecting
        "write_file", "edit_file", "delete_file" -> AgentPhase.Editing
        "verify" -> AgentPhase.Verifying
        "run_command" -> when {
            listOf("install", "add ", " i ", "i ", "pip ", "apk ").any { target.contains(it) } -> AgentPhase.Installing
            listOf("build", "vite", "webpack", "gradle", "tsc", "assemble").any { target.contains(it) } -> AgentPhase.Building
            else -> AgentPhase.Running
        }
        else -> AgentPhase.Running
    }

    private fun mutateLast(block: (UiMessage) -> UiMessage) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" }
        if (idx >= 0) { list[idx] = block(list[idx]); _messages.value = list }
    }

    fun stopGeneration() {
        resolveApproval(false)
        agentJob?.cancel()
        _generating.value = false
        _agentPhase.value = AgentPhase.Idle
        _agentStatus.value = "Stopped by you"
        tasks.finish(TASK_AGENT)
        notify("Generation stopped.")
    }

    fun retryLast() {
        val lastUser = _messages.value.lastOrNull { it.role == "user" } ?: return
        _messages.value = _messages.value.dropLastWhile { it.role != "user" }.dropLast(1)
        send(lastUser.text)
    }

    private fun reloadOpenTabs() {
        val f = files ?: return
        _tabs.value = _tabs.value.map { tab ->
            if (tab.dirty) tab else f.read(tab.path).fold({ tab.copy(content = it) }, { tab })
        }
    }

    // ---- models -------------------------------------------------------------
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models = _models.asStateFlow()
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError = _modelsError.asStateFlow()
    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading = _modelsLoading.asStateFlow()

    fun loadModels(force: Boolean = false) {
        if (_modelsLoading.value) return
        if (!isOnline() && _models.value.isEmpty()) {
            _modelsError.value = offlineReason("The OpenRouter model list")
            return
        }
        _modelsLoading.value = true
        _modelsError.value = null
        viewModelScope.launch {
            provider.listModels(force).fold(
                { _models.value = it },
                { _modelsError.value = it.message ?: "Could not load the model list." },
            )
            _modelsLoading.value = false
        }
    }

    /** §5 — point the provider at a compatible endpoint; blank restores the OpenRouter default. */
    fun setEndpoint(url: String) {
        settings.endpoint = url
        (provider as? OpenRouterProvider)?.endpointValue = url.ifBlank { OpenRouterProvider.DEFAULT_ENDPOINT }
        notify("Endpoint set to ${url.ifBlank { OpenRouterProvider.DEFAULT_ENDPOINT }}")
    }

    fun selectModel(id: String) {
        settings.modelId = id
        settings.pushRecentModel(id)
        _active.value?.let { p -> p.modelId = id; workspace.update(p); _active.value = p.copy() }
        notify("Model set to $id")
    }

    /** §5 — the model used automatically when the primary one is unavailable. */
    fun setFallbackModel(id: String) {
        settings.fallbackModelId = id
        notify(if (id.isBlank()) "Fallback model cleared." else "Fallback model set to $id")
    }

    // ---- api key ------------------------------------------------------------
    private val _connectionResult = MutableStateFlow<String?>(null)
    val connectionResult = _connectionResult.asStateFlow()

    fun saveApiKey(key: String) {
        secure.setApiKey(key)
        _connectionResult.value = null
        notify("API key saved securely.")
    }

    fun clearApiKey() {
        secure.clearApiKey()
        _connectionResult.value = null
        notify("API key removed.")
    }

    fun testConnection() {
        _connectionResult.value = "Testing..."
        viewModelScope.launch {
            provider.testConnection().fold(
                { _connectionResult.value = it },
                { _connectionResult.value = "Failed: ${it.message}" },
            )
        }
    }

    // ---- terminal (§21-§26) -------------------------------------------------
    val sessions = TerminalSessions()
    private val _sessionInfo = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionInfo = _sessionInfo.asStateFlow()
    val activeSessionId = sessions.activeId

    private val _terminalLines = MutableStateFlow<List<TermLine>>(emptyList())
    val terminalLines = _terminalLines.asStateFlow()
    private val _shellRunning = MutableStateFlow(false)
    val shellRunning = _shellRunning.asStateFlow()
    private val _shellCwd = MutableStateFlow<String?>(null)
    /** The real working directory, updated from the shell's own `pwd` after every command. */
    val shellCwd = _shellCwd.asStateFlow()
    private val _commandRunning = MutableStateFlow(false)
    /** True only while the shell process itself says it is executing a command. */
    val commandRunning = _commandRunning.asStateFlow()
    /** Seeded from the persisted history (§24) so it survives a restart. */
    val commandHistory: MutableList<String> = settings.commandHistory().toMutableList()

    /** §23 — explanation of the last failed command, or null when the last one succeeded. */
    private val _lastDiagnosis = MutableStateFlow<Diagnosis?>(null)
    val lastDiagnosis = _lastDiagnosis.asStateFlow()

    /** §22 — environment health report. */
    private val _envReport = MutableStateFlow<EnvReport?>(null)
    val envReport = _envReport.asStateFlow()
    private val _envScanning = MutableStateFlow(false)
    val envScanning = _envScanning.asStateFlow()

    private var sessionWatchers = mutableListOf<Job>()

    fun startShell() {
        val dir = projectDir ?: return notify("Open a project first.")
        if (sessions.active?.running?.value == true) return
        openSession(_active.value?.name ?: "shell")
    }

    /** §26 — opens an additional session; each one is a separate process. */
    fun openSession(name: String) {
        val dir = projectDir ?: return notify("Open a project first.")
        sessions.open(
            name = name,
            workingDir = dir,
            shell = settings.terminalShell,
            env = settings.terminalEnvMap(),
            scrollback = settings.terminalScrollback,
        ).fold(
            { _ ->
                if (settings.terminalClearOnNewSession) _terminalLines.value = emptyList()
                bindActiveSession()
                _sessionInfo.value = sessions.info()
                tasks.start(TASK_SHELL, "Terminal session running", RuntimeTask.Kind.Shell)
            },
            { notify(it.message ?: "Shell failed to start.") },
        )
    }

    fun selectSession(id: String) {
        sessions.select(id)
        bindActiveSession()
        _sessionInfo.value = sessions.info()
    }

    fun closeSession(id: String) {
        sessions.close(id)
        bindActiveSession()
        _sessionInfo.value = sessions.info()
        if (sessions.runningCount == 0) tasks.finish(TASK_SHELL)
    }

    /** Rewires the exposed flows onto whichever session is active. */
    private fun bindActiveSession() {
        sessionWatchers.forEach { it.cancel() }
        sessionWatchers.clear()
        val s = sessions.active
        if (s == null) {
            _terminalLines.value = emptyList()
            _shellRunning.value = false
            _commandRunning.value = false
            _shellCwd.value = null
            return
        }
        sessionWatchers += viewModelScope.launch { s.lines.collect { _terminalLines.value = it } }
        sessionWatchers += viewModelScope.launch { s.running.collect { _shellRunning.value = it } }
        sessionWatchers += viewModelScope.launch { s.busy.collect { _commandRunning.value = it } }
        sessionWatchers += viewModelScope.launch { s.cwd.collect { _shellCwd.value = it } }
        sessionWatchers += viewModelScope.launch {
            s.lastCommand.collect { last ->
                _sessionInfo.value = sessions.info()
                if (last != null && last.exitCode != 0 && last.command.isNotBlank()) {
                    diagnose(last.command, last.exitCode, last.stderrTail)
                } else if (last != null && last.exitCode == 0) {
                    _lastDiagnosis.value = null
                }
            }
        }
    }

    /** §23 — probes the real environment and explains the failure. */
    private fun diagnose(command: String, exitCode: Int, stderr: String) {
        val dir = projectDir ?: return
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) { envHealth.probeFor(command, dir, settings.terminalShell) }
            _lastDiagnosis.value = CommandDiagnostics.diagnose(command, exitCode, stderr, probe = probe)
        }
    }

    fun dismissDiagnosis() { _lastDiagnosis.value = null }

    /** §4 — runs the fix the diagnosis offered. Every action does something real. */
    fun applyFix(action: FixAction) {
        when (action) {
            is FixAction.RunCommand -> runCommand(action.command)
            is FixAction.Retry -> sessions.active?.lastCommand?.value?.command?.let { runCommand(it) }
            is FixAction.InstallTool -> notify(
                "${Toolchains.labelFor(action.toolId)} must come from the Linux runtime — open Settings → Toolchains.",
            )
            FixAction.OpenRuntime -> notify("Open Settings → Toolchains to install or repair the Linux runtime.")
        }
    }

    fun restartShell() {
        val id = sessions.activeId.value
        val name = sessions.active?.name ?: (_active.value?.name ?: "shell")
        if (id != null) sessions.close(id)
        openSession(name)
    }

    fun runCommand(cmd: String) {
        if (sessions.active == null) startShell()
        commandHistory += cmd
        settings.pushCommand(cmd)
        _lastDiagnosis.value = null
        sessions.active?.send(cmd)
        _sessionInfo.value = sessions.info()
    }

    fun interruptShell() = sessions.active?.interrupt() ?: Unit

    fun clearTerminal() {
        sessions.active?.clear()
        _terminalLines.value = emptyList()
    }

    fun stopShell() {
        sessions.closeAll()
        sessionWatchers.forEach { it.cancel() }
        sessionWatchers.clear()
        _shellRunning.value = false
        _commandRunning.value = false
        _shellCwd.value = null
        _sessionInfo.value = emptyList()
        tasks.finish(TASK_SHELL)
    }

    /** §22 — runs the real health probes. */
    fun inspectEnvironment() {
        if (_envScanning.value) return
        _envScanning.value = true
        viewModelScope.launch {
            _envReport.value = withContext(Dispatchers.IO) {
                envHealth.inspect(projectDir ?: workspace.root, settings.terminalShell)
            }
            _envScanning.value = false
        }
    }

    // ---- toolchains / runtime ----------------------------------------------
    private val _tools = MutableStateFlow<List<ToolStatus>>(emptyList())
    val toolStatuses = _tools.asStateFlow()
    private val _toolsScanning = MutableStateFlow(false)
    val toolsScanning = _toolsScanning.asStateFlow()

    private val _runtimeDiagnosis = MutableStateFlow<RuntimeDiagnosis?>(null)
    val runtimeDiagnosis = _runtimeDiagnosis.asStateFlow()
    private val _runtimeBusy = MutableStateFlow(false)
    val runtimeBusy = _runtimeBusy.asStateFlow()

    fun scanToolchains() {
        if (_toolsScanning.value) return
        _toolsScanning.value = true
        viewModelScope.launch {
            _tools.value = withContext(Dispatchers.IO) { toolchains.detectAll(projectDir ?: workspace.root) }
            _toolsScanning.value = false
        }
    }

    fun refreshRuntime() { viewModelScope.launch { linux.refresh() } }

    /** §28 — real diagnostics of the Linux runtime. */
    fun diagnoseRuntime() {
        if (_runtimeBusy.value) return
        _runtimeBusy.value = true
        viewModelScope.launch {
            _runtimeDiagnosis.value = withContext(Dispatchers.IO) { runtimeRepair.diagnose() }
            _runtimeBusy.value = false
        }
    }

    /** §28 — performs a repair and reports exactly what it did. */
    fun repairRuntime(action: RuntimeRepairAction) {
        if (_runtimeBusy.value) return
        _runtimeBusy.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runtimeRepair.repair(action) }.fold(
                { notify(it) },
                { notify(it.message ?: "Repair failed.") },
            )
            _runtimeDiagnosis.value = withContext(Dispatchers.IO) { runtimeRepair.diagnose() }
            linux.refresh()
            _runtimeBusy.value = false
        }
    }

    // ---- preview (§43, §44) -------------------------------------------------
    var devServer: DevServer? = null
        private set

    fun startPreviewStatic() {
        val p = _active.value ?: return notify("Open a project first.")
        devServer?.startStatic(p.previewPort)?.onSuccess {
            recovery.begin(Recovery.Operation.Server, p.name)
            tasks.start(TASK_SERVER, "Preview server running for ${p.name}", RuntimeTask.Kind.Server)
        }?.onFailure { notify(it.message ?: "Server failed to start.") }
    }

    fun startPreviewProcess(command: String) {
        val p = _active.value ?: return notify("Open a project first.")
        viewModelScope.launch {
            devServer?.startProcess(command, p.previewPort)?.onSuccess {
                recovery.begin(Recovery.Operation.Server, command)
                tasks.start(TASK_SERVER, "Dev server running for ${p.name}", RuntimeTask.Kind.Server)
            }?.onFailure { notify(it.message ?: "Dev server failed.") }
        }
    }

    /** §43 — restart uses exactly the configuration that was started before. */
    fun restartPreview() {
        viewModelScope.launch {
            devServer?.restart()?.onFailure { notify(it.message ?: "Restart failed.") }
        }
    }

    fun stopPreview() {
        devServer?.stop()
        tasks.finish(TASK_SERVER)
    }

    /** §44 — hands the real server error to the agent as a normal prompt. */
    fun askAiToFixPreview() {
        val report = devServer?.state?.value?.errorReport()
            ?: return notify("The preview has not reported an error.")
        setPendingPrompt("The preview failed. Here is the real output:\n\n$report\n\nFind the cause and fix it.")
        notify("Preview error sent to the AI chat.")
    }

    // ---- GitHub (§29-§33) ---------------------------------------------------
    private val _githubState = MutableStateFlow(GitHubState())
    val githubState = _githubState.asStateFlow()

    private fun githubBusy(label: String?) {
        _githubState.value = _githubState.value.copy(busy = label, error = null)
    }

    /** §55 — returns true (and reports it) when a GitHub call cannot possibly succeed offline. */
    private fun githubOffline(): Boolean {
        if (isOnline()) return false
        _githubState.value = _githubState.value.copy(busy = null, error = offlineReason("GitHub"))
        return true
    }

    private fun githubFail(t: Throwable) {
        _githubState.value = _githubState.value.copy(busy = null, error = t.message ?: "GitHub request failed.")
    }

    fun githubConnected(): Boolean = secure.hasGithubToken()

    fun connectGithub(token: String) {
        if (githubOffline()) return
        githubBusy("Verifying token...")
        viewModelScope.launch {
            github.connect(token).fold(
                { user ->
                    _githubState.value = _githubState.value.copy(user = user, busy = null, lastResult = "Connected as ${user.login}")
                    loadRepos()
                },
                { githubFail(it) },
            )
        }
    }

    fun disconnectGithub() {
        github.disconnect()
        _githubState.value = GitHubState()
        notify("GitHub disconnected and the token deleted.")
    }

    fun refreshGithubUser() {
        if (!secure.hasGithubToken()) return
        viewModelScope.launch {
            github.me().fold({ _githubState.value = _githubState.value.copy(user = it) }, { githubFail(it) })
        }
    }

    fun loadRepos() {
        if (!secure.hasGithubToken()) return
        if (githubOffline()) return
        githubBusy("Loading repositories...")
        viewModelScope.launch {
            github.listRepos().fold(
                { _githubState.value = _githubState.value.copy(repos = it, busy = null) },
                { githubFail(it) },
            )
        }
    }

    fun createGithubRepo(name: String, private: Boolean) {
        if (githubOffline()) return
        githubBusy("Creating repository...")
        viewModelScope.launch {
            github.createRepo(name, private, "Created from Sufyan Harness").fold(
                { repo ->
                    _githubState.value = _githubState.value.copy(
                        busy = null,
                        repos = listOf(repo) + _githubState.value.repos,
                        lastResult = "Created ${repo.fullName}",
                    )
                    _active.value?.let { linkRepo(repo.fullName, repo.defaultBranch) }
                },
                { githubFail(it) },
            )
        }
    }

    /** §29 — clone: downloads the branch tree into a brand-new project. */
    fun cloneRepo(repo: GitHubRepo, branch: String) {
        if (githubOffline()) return
        githubBusy("Cloning ${repo.fullName}...")
        viewModelScope.launch {
            val tmp = File(application.cacheDir, "clone-${System.currentTimeMillis()}.zip")
            github.downloadZip(repo.fullName, branch, tmp).fold(
                { bytes ->
                    val created = withContext(Dispatchers.IO) { workspace.createFromZip(repo.name, ProjectType.Empty, tmp) }
                    tmp.delete()
                    created.fold(
                        { project ->
                            val sha = github.headSha(repo.fullName, branch).getOrNull()
                            // The type is decided from the files that actually arrived (§45).
                            project.type = detectTypeFromDir(workspace.projectDir(project)).id
                            project.repoFullName = repo.fullName
                            project.repoBranch = branch
                            project.lastSyncSha = sha
                            workspace.update(project)
                            refreshProjects()
                            open(project)
                            _githubState.value = _githubState.value.copy(
                                busy = null,
                                lastResult = "Cloned ${repo.fullName} (${bytes / 1024} KB) into ${project.name}",
                            )
                            refreshSync()
                        },
                        { githubFail(it) },
                    )
                },
                { tmp.delete(); githubFail(it) },
            )
        }
    }

    /**
     * §45 — the type of a cloned repository, decided by looking at the files that were actually
     * downloaded rather than by guessing from the repository name.
     */
    private fun detectTypeFromDir(dir: File): ProjectType {
        val entries = (dir.listFiles() ?: emptyArray()).map { it.name }.toSet()
        val pkg = File(dir, "package.json").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        return when {
            entries.any { it.startsWith("settings.gradle") } && File(dir, "app/src/main/AndroidManifest.xml").exists() ->
                ProjectType.AndroidApp
            pkg != null && (pkg.contains("\"react\"") || pkg.contains("\"vite\"") || pkg.contains("\"next\"")) ->
                ProjectType.WebApp
            pkg != null -> ProjectType.Node
            "index.html" in entries -> ProjectType.Website
            else -> ProjectType.Empty
        }
    }

    /** §29 — links the open project to a repository without downloading anything. */
    fun linkRepo(fullName: String, branch: String) {
        val p = _active.value ?: return notify("Open a project first.")
        p.repoFullName = fullName
        p.repoBranch = branch
        workspace.update(p)
        _active.value = p.copy()
        refreshProjects()
        notify("Linked to $fullName ($branch)")
        refreshSync()
    }

    fun unlinkRepo() {
        val p = _active.value ?: return
        p.repoFullName = null
        p.repoBranch = null
        p.lastSyncSha = null
        workspace.update(p)
        _active.value = p.copy()
        notify("Repository unlinked. Nothing was deleted.")
    }

    /** §31 — compares the working copy with the linked branch using real content hashes. */
    fun refreshSync() {
        val p = _active.value ?: return
        val full = p.repoFullName ?: return
        val branch = p.repoBranch ?: "main"
        githubBusy("Comparing with $full...")
        viewModelScope.launch {
            val dir = workspace.projectDir(p)
            val remoteSha = github.headSha(full, branch).getOrNull()
            github.listTree(full, branch).fold(
                { remote ->
                    val status = withContext(Dispatchers.IO) {
                        ProjectSync.status(ProjectSync.collect(dir), remote, remoteSha, p.lastSyncSha)
                    }
                    _githubState.value = _githubState.value.copy(
                        busy = null,
                        status = status,
                        conflicts = ProjectSync.conflicts(status, p.lastSyncSha),
                    )
                },
                { githubFail(it) },
            )
            github.listBranches(full).onSuccess { list ->
                _githubState.value = _githubState.value.copy(branches = list)
            }
            github.listCommits(full, branch).onSuccess { list ->
                _githubState.value = _githubState.value.copy(commits = list)
            }
        }
    }

    /** §29/§31 — a real commit + branch update through the GitHub API. */
    fun pushProject(message: String, force: Boolean = false) {
        val p = _active.value ?: return notify("Open a project first.")
        val full = p.repoFullName ?: return notify("Link this project to a repository first.")
        val branch = p.repoBranch ?: "main"
        if (githubOffline()) return
        githubBusy("Preparing push...")
        tasks.start(TASK_SYNC, "Pushing ${p.name} to GitHub", RuntimeTask.Kind.Install)
        viewModelScope.launch {
            val dir = workspace.projectDir(p)
            val local = withContext(Dispatchers.IO) { ProjectSync.collect(dir) }
            val oversized = ProjectSync.oversized(local)
            val remoteSha = github.headSha(full, branch).getOrNull()
            val remote = github.listTree(full, branch).getOrElse { emptyList() }
            val status = withContext(Dispatchers.IO) { ProjectSync.status(local, remote, remoteSha, p.lastSyncSha) }
            if (status.clean) {
                _githubState.value = _githubState.value.copy(busy = null, status = status, lastResult = "Nothing to push — the branch already matches.")
                tasks.finish(TASK_SYNC)
                return@launch
            }
            val payload = withContext(Dispatchers.IO) { ProjectSync.payload(local, status) }
            github.pushFiles(
                fullName = full,
                branch = branch,
                message = message,
                files = payload,
                deletions = status.deleted,
                expectedSha = p.lastSyncSha ?: remoteSha,
                force = force,
                onProgress = { line -> _githubState.value = _githubState.value.copy(busy = line) },
            ).fold(
                { outcome ->
                    when (outcome) {
                        is PushOutcome.Success -> {
                            p.lastSyncSha = outcome.commitSha
                            workspace.update(p)
                            _active.value = p.copy()
                            _githubState.value = _githubState.value.copy(
                                busy = null,
                                conflicts = emptyList(),
                                lastResult = "Pushed ${outcome.filesPushed} file(s) as ${outcome.commitSha.take(7)}" +
                                    if (oversized.isNotEmpty()) " (skipped ${oversized.size} file(s) over 5 MB)" else "",
                            )
                            refreshSync()
                            if (settings.notifyOnTaskComplete) {
                                RuntimeService.notifyCompleted(application, "Pushed to GitHub", "$full ($branch)")
                            }
                        }
                        is PushOutcome.Rejected -> {
                            _githubState.value = _githubState.value.copy(
                                busy = null,
                                error = outcome.reason + " Pull first, or choose how to resolve the conflict.",
                                conflicts = ProjectSync.conflicts(status.copy(remoteSha = outcome.remoteSha), p.lastSyncSha),
                            )
                        }
                    }
                },
                { githubFail(it) },
            )
            tasks.finish(TASK_SYNC)
        }
    }

    /** §29 — pull: replaces the working copy with the remote branch, after a safety checkpoint. */
    fun pullProject() {
        val p = _active.value ?: return notify("Open a project first.")
        val full = p.repoFullName ?: return notify("Link this project to a repository first.")
        val branch = p.repoBranch ?: "main"
        if (githubOffline()) return
        githubBusy("Pulling $full...")
        viewModelScope.launch {
            // A checkpoint first: pulling overwrites files, so the previous state stays recoverable.
            checkpointStore?.create("Before pull from $full")
            val tmp = File(application.cacheDir, "pull-${System.currentTimeMillis()}.zip")
            github.downloadZip(full, branch, tmp).fold(
                {
                    val dir = workspace.projectDir(p)
                    val result = withContext(Dispatchers.IO) { ProjectArchive.importZip(dir, tmp) }
                    tmp.delete()
                    result.fold(
                        {
                            p.lastSyncSha = github.headSha(full, branch).getOrNull()
                            workspace.update(p)
                            _active.value = p.copy()
                            reloadOpenTabs()
                            refreshCheckpoints()
                            _githubState.value = _githubState.value.copy(busy = null, lastResult = "Pulled $full ($branch).")
                            refreshSync()
                        },
                        { githubFail(it) },
                    )
                },
                { tmp.delete(); githubFail(it) },
            )
        }
    }

    fun createGithubBranch(name: String) {
        val p = _active.value ?: return
        val full = p.repoFullName ?: return notify("Link this project to a repository first.")
        val from = p.repoBranch ?: "main"
        githubBusy("Creating branch $name...")
        viewModelScope.launch {
            github.createBranch(full, name, from).fold(
                {
                    p.repoBranch = name
                    workspace.update(p)
                    _active.value = p.copy()
                    _githubState.value = _githubState.value.copy(busy = null, lastResult = "Created and switched to $name")
                    refreshSync()
                },
                { githubFail(it) },
            )
        }
    }

    fun switchGithubBranch(name: String) {
        val p = _active.value ?: return
        p.repoBranch = name
        p.lastSyncSha = null
        workspace.update(p)
        _active.value = p.copy()
        refreshSync()
    }

    /** §32 — resolve one conflicted file by taking the remote copy; the local one is checkpointed. */
    fun resolveConflictTakeRemote(path: String) {
        val p = _active.value ?: return
        val full = p.repoFullName ?: return
        val branch = p.repoBranch ?: "main"
        viewModelScope.launch {
            github.fetchFile(full, path, branch).fold(
                { content ->
                    withContext(Dispatchers.IO) { files?.write(path, content) }
                    reloadOpenTabs()
                    notify("$path replaced with the GitHub version.")
                    refreshSync()
                },
                { githubFail(it) },
            )
        }
    }

    /** §32 — keep the local file; it will overwrite the remote one on the next push. */
    fun resolveConflictKeepLocal(path: String) {
        _githubState.value = _githubState.value.copy(
            conflicts = _githubState.value.conflicts.filterNot { it.path == path },
            lastResult = "Keeping the local version of $path.",
        )
    }

    // ---- Android build (§34-§39) -------------------------------------------
    private val _buildState = MutableStateFlow(BuildState())
    val buildState = _buildState.asStateFlow()

    fun detectBuildEnvironment() {
        val dir = projectDir ?: return
        viewModelScope.launch {
            val env = builder.detect(dir)
            _buildState.value = _buildState.value.copy(
                environment = env,
                artifacts = withContext(Dispatchers.IO) { builder.artifacts(dir) },
            )
        }
    }

    fun buildApk(variant: String) {
        val p = _active.value ?: return notify("Open a project first.")
        val dir = workspace.projectDir(p)
        if (_buildState.value.running) return
        _buildState.value = _buildState.value.copy(running = true, log = emptyList(), outcome = null)
        tasks.start(TASK_BUILD, "Building ${p.name} ($variant)", RuntimeTask.Kind.Build)
        recovery.begin(Recovery.Operation.Build, "${p.name} ($variant)")
        viewModelScope.launch {
            val outcome = builder.build(dir, variant) { line ->
                _buildState.value = _buildState.value.copy(log = (_buildState.value.log + line).takeLast(500))
            }
            _buildState.value = _buildState.value.copy(
                running = false,
                outcome = outcome,
                artifacts = withContext(Dispatchers.IO) { builder.artifacts(dir) },
            )
            tasks.finish(TASK_BUILD)
            recovery.end(Recovery.Operation.Build)
            if (settings.notifyOnTaskComplete) {
                val (title, text) = when (outcome) {
                    is BuildOutcome.Success -> "Build completed" to outcome.artifact.name
                    is BuildOutcome.Failed -> "Build failed" to outcome.diagnosis.what
                    is BuildOutcome.Blocked -> "Build blocked" to outcome.requirement.label
                }
                RuntimeService.notifyCompleted(application, title, text)
            }
        }
    }

    // ---- §34-§39: cloud build (GitHub Actions) ------------------------------

    private val _cloudBuild = MutableStateFlow(CloudBuildState())
    val cloudBuildState = _cloudBuild.asStateFlow()
    private var cloudJob: Job? = null

    /** True when this project can be built in the cloud right now — each part is checked, not assumed. */
    fun cloudBuildBlockers(): List<String> {
        val p = _active.value
        return buildList {
            if (p == null) add("No project is open.")
            if (!github.hasToken()) add("GitHub is not connected. Connect a token with the `repo` and `workflow` scopes.")
            if (p != null && p.repoFullName == null) add("This project is not linked to a GitHub repository.")
            if (!isOnline()) add("This device is offline.")
        }
    }

    /**
     * Builds the APK on GitHub's machines and brings it back to the phone.
     *
     * The phone cannot do this itself: Android ships no JDK, and Google publishes `aapt2` and
     * build-tools only for x86_64 desktops. Rather than fake a local build, this pushes the project,
     * dispatches the workflow, follows the real run step by step, downloads the artifact, verifies
     * it is a genuine package and only then offers to install it.
     */
    fun startCloudBuild(variant: String) {
        val p = _active.value ?: return notify("Open a project first.")
        val blockers = cloudBuildBlockers()
        if (blockers.isNotEmpty()) {
            _cloudBuild.value = CloudBuildState(error = blockers.joinToString(" "))
            return
        }
        if (_cloudBuild.value.running) return
        val full = p.repoFullName!!
        val branch = p.repoBranch ?: "main"
        val wanted = CloudBuild.normalise(variant)

        _cloudBuild.value = CloudBuildState(running = true, phase = "Preparing...")
        tasks.start(TASK_CLOUD, "Cloud build: ${p.name}", RuntimeTask.Kind.Build)

        cloudJob = viewModelScope.launch {
            try {
                // 1 — the repository has to contain the project *and* a workflow that can build it.
                cloudPhase("Checking the workflow on GitHub...")
                val hasWorkflow = github.fetchFile(full, CloudBuild.WORKFLOW_PATH, branch).isSuccess

                cloudPhase("Collecting local changes...")
                val dir = workspace.projectDir(p)
                val local = withContext(Dispatchers.IO) { ProjectSync.collect(dir) }
                val remoteSha = github.headSha(full, branch).getOrNull()
                val remote = github.listTree(full, branch).getOrElse { emptyList() }
                val status = withContext(Dispatchers.IO) { ProjectSync.status(local, remote, remoteSha, p.lastSyncSha) }
                val payload = withContext(Dispatchers.IO) { ProjectSync.payload(local, status) }.toMutableMap()
                if (!hasWorkflow) {
                    payload[CloudBuild.WORKFLOW_PATH] = CloudBuild.workflowYaml().toByteArray()
                }

                if (payload.isNotEmpty() || status.deleted.isNotEmpty()) {
                    cloudPhase("Pushing ${payload.size} file(s) to $full...")
                    val push = github.pushFiles(
                        fullName = full,
                        branch = branch,
                        message = if (hasWorkflow) "Sufyan Harness: sync before cloud build" else "Sufyan Harness: add cloud build workflow",
                        files = payload,
                        deletions = status.deleted,
                        expectedSha = p.lastSyncSha ?: remoteSha,
                        onProgress = { line -> cloudPhase(line) },
                    ).getOrElse { return@launch cloudFail(it.message ?: "The push to GitHub failed.") }

                    when (push) {
                        is PushOutcome.Rejected -> return@launch cloudFail(
                            push.reason + " Resolve it on the GitHub screen, then start the cloud build again.",
                        )
                        is PushOutcome.Success -> {
                            p.lastSyncSha = push.commitSha
                            workspace.update(p)
                            _active.value = p.copy()
                            refreshSync()
                        }
                    }
                } else {
                    cloudPhase("GitHub is already up to date with this project.")
                }

                // 2 — start the run.
                val dispatchedAt = System.currentTimeMillis()
                cloudPhase("Starting the $wanted build on GitHub...")
                github.dispatchWorkflow(
                    full, CloudBuild.WORKFLOW_FILE, branch, mapOf("variant" to wanted),
                ).getOrElse {
                    return@launch cloudFail(
                        (it.message ?: "GitHub refused to start the workflow.") +
                            " A cloud build needs a token with the `workflow` scope, and the workflow file must exist on \"$branch\".",
                    )
                }

                // 3 — find the run we just started (never adopt someone else's).
                cloudPhase("Waiting for GitHub to queue the run...")
                var run: CloudRun? = null
                val findDeadline = System.currentTimeMillis() + 90_000
                while (run == null && System.currentTimeMillis() < findDeadline) {
                    delay(CloudBuild.POLL_MS)
                    run = github.latestWorkflowRun(full, CloudBuild.WORKFLOW_FILE, branch, dispatchedAt).getOrNull()
                }
                if (run == null) {
                    return@launch cloudFail(
                        "GitHub accepted the request but no run appeared within 90 seconds. Check the Actions tab of $full.",
                    )
                }
                _cloudBuild.value = _cloudBuild.value.copy(runUrl = run.htmlUrl)

                // 4 — follow it step by step.
                val deadline = System.currentTimeMillis() + CloudBuild.TIMEOUT_MS
                var progress: CloudBuild.Progress = CloudBuild.Progress.Queued
                while (System.currentTimeMillis() < deadline) {
                    val current = github.workflowRun(full, run.id).getOrNull()
                    if (current != null) {
                        progress = CloudBuild.progressOf(current.status, current.conclusion)
                        val steps = github.workflowRunSteps(full, run.id).getOrDefault(emptyList())
                        val active = steps.lastOrNull { it.status == "in_progress" }?.name
                            ?: steps.lastOrNull { it.conclusion != null }?.name
                        _cloudBuild.value = _cloudBuild.value.copy(
                            steps = steps,
                            phase = when (progress) {
                                CloudBuild.Progress.Queued -> "Queued on GitHub..."
                                CloudBuild.Progress.Running -> active?.let { "Running: $it" } ?: "Building..."
                                CloudBuild.Progress.Succeeded -> "Build finished — fetching the APK..."
                                is CloudBuild.Progress.Ended -> "Run ended"
                            },
                        )
                        if (current.status == "completed") break
                    }
                    delay(CloudBuild.POLL_MS)
                }

                when (val result = progress) {
                    is CloudBuild.Progress.Ended -> return@launch cloudFail(result.explanation)
                    CloudBuild.Progress.Succeeded -> Unit
                    else -> return@launch cloudFail(
                        "The run was still going after ${CloudBuild.TIMEOUT_MS / 60_000} minutes, so the app stopped waiting. It is still running on GitHub.",
                    )
                }

                // 5 — download, verify, offer to install. Nothing is claimed before this succeeds.
                cloudPhase("Downloading the APK...")
                val artifacts = github.runArtifacts(full, run.id).getOrElse {
                    return@launch cloudFail(it.message ?: "Could not list the run's artifacts.")
                }
                val name = CloudBuild.pickArtifact(artifacts.map { it.name }, wanted)
                    ?: return@launch cloudFail(
                        "The run succeeded but uploaded no APK artifact. Check that the workflow's upload step ran.",
                    )
                val artifact = artifacts.first { it.name == name }
                val zip = File(application.cacheDir, "cloud-$name-${run.id}.zip")
                github.downloadArtifact(full, artifact.id, zip).getOrElse {
                    return@launch cloudFail(it.message ?: "Downloading the artifact failed.")
                }

                cloudPhase("Verifying the package...")
                val apkDir = File(exportsDir(), "apk").apply { mkdirs() }
                val apk = withContext(Dispatchers.IO) { CloudBuild.extractApk(zip, apkDir) }.getOrElse {
                    return@launch cloudFail(it.message ?: "The artifact did not contain an APK.")
                }
                zip.delete()
                val report = withContext(Dispatchers.IO) { ApkVerifier.verify(apk) }
                if (!report.valid) {
                    return@launch cloudFail("The downloaded file is not a valid APK: ${report.problem ?: "unknown reason"}.")
                }

                val built = BuildArtifact(apk, wanted, report, System.currentTimeMillis())
                _buildState.value = _buildState.value.copy(
                    artifacts = listOf(built) + _buildState.value.artifacts.filterNot { it.file == apk },
                )
                _cloudBuild.value = _cloudBuild.value.copy(
                    running = false,
                    phase = "Done",
                    error = null,
                    lastResult = "${apk.name} built on GitHub and verified — ${report.sizeBytes / 1024 / 1024} MB. Tap Install below.",
                )
                notify("Cloud build finished: ${apk.name}")
                if (settings.notifyOnTaskComplete) {
                    RuntimeService.notifyCompleted(application, "APK ready", "${apk.name} downloaded from GitHub Actions")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                cloudFail(t.message ?: "The cloud build failed.")
            } finally {
                tasks.finish(TASK_CLOUD)
                if (_cloudBuild.value.running) {
                    _cloudBuild.value = _cloudBuild.value.copy(running = false)
                }
            }
        }
    }

    /** Stops following the run. Honest wording: GitHub keeps building; only the app stops watching. */
    fun stopFollowingCloudBuild() {
        cloudJob?.cancel()
        cloudJob = null
        tasks.finish(TASK_CLOUD)
        _cloudBuild.value = _cloudBuild.value.copy(
            running = false,
            phase = "Stopped watching",
            lastResult = "The app stopped following the run. GitHub is still building it — open the run to check.",
        )
    }

    fun clearCloudBuildError() {
        _cloudBuild.value = _cloudBuild.value.copy(error = null)
    }

    private fun cloudPhase(text: String) {
        _cloudBuild.value = _cloudBuild.value.copy(phase = text, error = null)
    }

    private fun cloudFail(reason: String) {
        _cloudBuild.value = _cloudBuild.value.copy(running = false, error = reason)
    }

    /** §37 — hands the verified APK to the system installer. */
    fun installArtifact(artifact: BuildArtifact): Intent? =
        builder.installIntent(artifact).fold(
            {
                // The installer runs in another process; the marker is cleared on the next launch
                // after the user is told, which is the only honest way to notice a killed install.
                recovery.begin(Recovery.Operation.Install, artifact.name)
                it
            },
            { notify(it.message ?: "This APK cannot be installed."); null },
        )

    fun shareArtifact(artifact: BuildArtifact): Intent? =
        builder.shareIntent(artifact).fold({ it }, { notify(it.message ?: "Share failed."); null })

    fun deleteArtifact(artifact: BuildArtifact) {
        if (builder.delete(artifact)) {
            notify("Deleted ${artifact.name}")
            detectBuildEnvironment()
        } else {
            notify("Could not delete ${artifact.name}")
        }
    }

    fun canRequestInstall(): Boolean = builder.canRequestInstall()

    // ---- git & checkpoints --------------------------------------------------
    private val _git = MutableStateFlow<GitStatus?>(null)
    val git = _git.asStateFlow()
    private val _diff = MutableStateFlow<String?>(null)
    val diff = _diff.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val gitLog = _log.asStateFlow()

    private val gitService: GitService? get() = projectDir?.let { GitService(it, linux) }
    private val checkpointStore: CheckpointStore? get() = projectDir?.let { CheckpointStore(it, application.filesDir) }

    private val _checkpoints = MutableStateFlow<List<Checkpoint>>(emptyList())
    val checkpoints = _checkpoints.asStateFlow()

    fun refreshGit() {
        val g = gitService ?: return
        viewModelScope.launch {
            _git.value = g.status()
            _log.value = g.log().getOrElse { emptyList() }
        }
    }

    fun gitInit() {
        val g = gitService ?: return
        viewModelScope.launch {
            g.init().fold({ notify("Repository initialised."); refreshGit() }, { notify(it.message ?: "git init failed") })
        }
    }

    fun loadDiff() {
        val g = gitService ?: return
        viewModelScope.launch {
            _diff.value = g.diff().getOrElse { "Could not produce a diff: ${it.message}" }
        }
    }

    fun commit(message: String) {
        val g = gitService ?: return
        viewModelScope.launch {
            g.commit(message).fold({ notify("Committed."); refreshGit() }, { notify(it.message ?: "Commit failed") })
        }
    }

    fun refreshCheckpoints() { _checkpoints.value = checkpointStore?.list() ?: emptyList() }

    fun createCheckpoint(label: String) {
        checkpointStore?.create(label)?.fold({ notify("Checkpoint saved."); refreshCheckpoints() }, { notify(it.message ?: "Failed") })
    }

    fun restoreCheckpoint(cp: Checkpoint) {
        checkpointStore?.restore(cp)?.fold({
            notify("Restored “${cp.label}”.")
            reloadOpenTabs()
            refreshGit()
            refreshChanges()
        }, { notify(it.message ?: "Restore failed") })
    }

    fun deleteCheckpoint(cp: Checkpoint) {
        checkpointStore?.delete(cp)?.onSuccess { refreshCheckpoints() }
    }

    override fun onCleared() {
        super.onCleared()
        stopShell()
        devServer?.dispose()
        tasks.clear()
    }

    private companion object {
        const val TASK_AGENT = "agent"
        const val TASK_SERVER = "server"
        const val TASK_BUILD = "build"
        const val TASK_SHELL = "shell"
        const val TASK_SYNC = "sync"
        const val TASK_CLOUD = "cloud-build"
    }
}
