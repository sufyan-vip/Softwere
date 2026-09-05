package com.sufyan.harness

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sufyan.harness.ai.*
import com.sufyan.harness.data.*
import com.sufyan.harness.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    Complete("Complete", false),
    Failed("Failed", false),
    ;
}

@Serializable
data class UiMessage(
    val role: String,          // "user" | "assistant" | "error" | "status"
    var text: String,
    var tools: List<ToolActivity> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

data class OpenTab(val path: String, var content: String, var dirty: Boolean = false)

/** §52 — one snapshot of storage, computed from real directories, never estimated. */
data class StorageSnapshot(
    val projectsTotal: Long,
    val runtimeSize: Long,
    val exportsSize: Long,
    val projects: List<Pair<String, Long>>,
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

    init {
        refreshProjects()
        settings.lastProjectId?.let { id -> _projects.value.find { it.id == id }?.let { open(it) } }
    }

    fun refreshProjects() { _projects.value = workspace.list() }

    fun createProject(name: String, template: Template, type: ProjectType = ProjectType.from(null, template.id)): Result<Project> =
        workspace.create(name, template, type).onSuccess {
            refreshProjects()
            open(it)
        }

    fun open(project: Project) {
        _active.value = project
        settings.lastProjectId = project.id
        _tabs.value = emptyList()
        _activeTab.value = null
        _messages.value = loadConversation(project)
        _git.value = null
        _checkpoints.value = emptyList()
        devServer = DevServer(workspace.projectDir(project))
        refreshGit()
        refreshCheckpoints()
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

    // ---- Phase 3: import / export / storage --------------------------------
    /** §41 — zip the active project into <filesDir>/exports and return the file (real bytes). */
    fun exportProject(): Result<File> {
        val p = _active.value ?: return Result.failure(IllegalStateException("Open a project first."))
        return runCatching {
            val exports = File(application.filesDir, "exports").apply { mkdirs() }
            val out = File(exports, "${p.id}.zip")
            workspace.exportZip(p, out).getOrThrow()
            out
        }
    }

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

    /** §52 — snapshot of storage, aggregated from real directories. */
    fun storageSnapshot(): StorageSnapshot {
        val projects = workspace.list().map { it to workspace.sizeOf(it) }
        val runtime = application.linux.rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val exports = exportsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return StorageSnapshot(
            projectsTotal = projects.sumOf { it.second },
            runtimeSize = runtime,
            exportsSize = exports,
            projects = projects.map { (p, s) -> (p.name to s) },
        )
    }

    /** §52 — safe cleanup: does not touch project files. */
    fun clearExports(): Result<Unit> = runCatching {
        exportsDir().walkTopDown().filter { it.isFile }.forEach { it.delete() }
        notify("Cleared exported archives.")
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

    fun send(prompt: String) {
        val project = _active.value ?: return notify("Open a project first.")
        val f = files ?: return
        if (prompt.isBlank()) return
        if (!secure.hasApiKey()) {
            _messages.value = _messages.value + UiMessage("error", "No OpenRouter API key configured. Add one in Settings → AI, then retry.")
            return
        }

        _messages.value = _messages.value + UiMessage("user", prompt)
        val assistant = UiMessage("assistant", "")
        _messages.value = _messages.value + assistant
        persistConversation()

        if (apiHistory.none { it.role == "system" }) {
            val extra = settings.systemPrompt.trim()
            apiHistory.add(
                0,
                ChatMessage("system", DEFAULT_SYSTEM_PROMPT + "\n\n" + typeContext(project) + if (extra.isNotEmpty()) "\n\n$extra" else ""),
            )
        }
        apiHistory += ChatMessage("user", prompt)

        val tools = AgentTools(f, workspace.projectDir(project), commandsEnabled = true)
        val agent = Agent(provider, tools)
        val model = project.modelId ?: settings.modelId

        _generating.value = true
        _agentPhase.value = AgentPhase.Thinking
        _agentStatus.value = "Waiting for $model"
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
                if (_agentPhase.value.busy) _agentPhase.value = AgentPhase.Complete
                workspace.touch(project)
                refreshProjects()
                refreshGit()
                reloadOpenTabs()
                persistConversation()
            }
        }
    }

    /**
     * §45 — the agent gets the project's actual type plus the real commands it can run, so it never
     * invents a build/preview command. Every line is derived from [Project.kind] metadata; a null
     * command is reported as absent rather than guessed.
     */
    private fun typeContext(p: Project): String {
        val k = p.kind
        return buildString {
            append("Project: ${p.name} (${k.label})")
            append("\nType: ${k.id}")
            if (k.languages.isNotBlank()) append("\nLanguage: ${k.languages}")
            k.runCommand?.let { append("\nRun: $it") }
            k.devCommand?.let { append("\nDev server: $it") }
            k.buildCommand?.let { append("\nBuild: $it") }
            if (k.requiredTools.isNotEmpty()) append("\nRequires: ${k.requiredTools.joinToString(", ")}")
            append("\nPreview port: ${p.previewPort}")
            append(
                "\nRules for this project: only use run_command for a command that actually appears in " +
                    "the project files (package.json scripts, build.gradle, etc.). Never guess a command. " +
                    "After any edit, run the real build or dev command and report the actual output.",
            )
        }
    }

    /** Maps a tool call onto the state shown in the agent status bar. No guessing, no timers. */
    private fun phaseFor(tool: String, target: String): AgentPhase = when (tool) {
        "list_files", "read_file", "search" -> AgentPhase.Inspecting
        "write_file", "edit_file", "delete_file" -> AgentPhase.Editing
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
        agentJob?.cancel()
        _generating.value = false
        _agentPhase.value = AgentPhase.Idle
        _agentStatus.value = "Stopped by you"
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

    fun loadModels() {
        if (_modelsLoading.value) return
        _modelsLoading.value = true
        _modelsError.value = null
        viewModelScope.launch {
            provider.listModels().fold(
                { _models.value = it },
                { _modelsError.value = it.message ?: "Could not load the model list." },
            )
            _modelsLoading.value = false
        }
    }

    fun selectModel(id: String) {
        settings.modelId = id
        settings.pushRecentModel(id)
        _active.value?.let { p -> p.modelId = id; workspace.update(p); _active.value = p.copy() }
        notify("Model set to $id")
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

    // ---- terminal -----------------------------------------------------------
    private var shell: ShellSession? = null
    private val _terminalLines = MutableStateFlow<List<TermLine>>(emptyList())
    val terminalLines = _terminalLines.asStateFlow()
    private val _shellRunning = MutableStateFlow(false)
    val shellRunning = _shellRunning.asStateFlow()
    private val _shellCwd = MutableStateFlow<String?>(null)
    /** Where the session was started; `cd` inside the shell is tracked in V3 phase 7. */
    val shellCwd = _shellCwd.asStateFlow()
    private val _commandRunning = MutableStateFlow(false)
    /** True only while the shell process itself says it is executing a command. */
    val commandRunning = _commandRunning.asStateFlow()
    val commandHistory = mutableListOf<String>()

    fun startShell() {
        val dir = projectDir ?: return notify("Open a project first.")
        if (shell != null) return
        val s = ShellSession(dir)
        shell = s
        s.start().fold(
            {
                _shellRunning.value = true
                _shellCwd.value = dir.absolutePath
            },
            {
                shell?.dispose()
                shell = null
                _shellRunning.value = false
                notify(it.message ?: "Shell failed to start.")
                return
            },
        )
        viewModelScope.launch { s.lines.collect { _terminalLines.value = it } }
        viewModelScope.launch { s.running.collect { _commandRunning.value = it } }
    }

    fun restartShell() {
        stopShell()
        startShell()
    }

    fun runCommand(cmd: String) {
        if (shell == null) startShell()
        commandHistory += cmd
        shell?.send(cmd)
    }

    fun interruptShell() = shell?.interrupt() ?: Unit
    fun clearTerminal() { shell?.clear(); _terminalLines.value = emptyList() }

    fun stopShell() {
        shell?.dispose()
        shell = null
        _shellRunning.value = false
        _commandRunning.value = false
        _shellCwd.value = null
    }

    // ---- toolchains ---------------------------------------------------------
    private val _tools = MutableStateFlow<List<ToolStatus>>(emptyList())
    val toolStatuses = _tools.asStateFlow()
    private val _toolsScanning = MutableStateFlow(false)
    val toolsScanning = _toolsScanning.asStateFlow()

    fun scanToolchains() {
        if (_toolsScanning.value) return
        _toolsScanning.value = true
        viewModelScope.launch {
            _tools.value = toolchains.detectAll(projectDir ?: workspace.root)
            _toolsScanning.value = false
        }
    }

    fun refreshRuntime() { viewModelScope.launch { linux.refresh() } }

    // ---- preview ------------------------------------------------------------
    var devServer: DevServer? = null
        private set

    fun startPreviewStatic() {
        val p = _active.value ?: return notify("Open a project first.")
        devServer?.startStatic(p.previewPort)?.onSuccess {
            RuntimeService.start(application, "Preview server running for ${p.name}")
        }?.onFailure { notify(it.message ?: "Server failed to start.") }
    }

    fun startPreviewProcess(command: String) {
        val p = _active.value ?: return notify("Open a project first.")
        viewModelScope.launch {
            devServer?.startProcess(command, p.previewPort)?.onSuccess {
                RuntimeService.start(application, "Dev server running for ${p.name}")
            }?.onFailure { notify(it.message ?: "Dev server failed.") }
        }
    }

    fun stopPreview() {
        devServer?.stop()
        RuntimeService.stop(application)
    }

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
        }, { notify(it.message ?: "Restore failed") })
    }

    fun deleteCheckpoint(cp: Checkpoint) {
        checkpointStore?.delete(cp)?.onSuccess { refreshCheckpoints() }
    }

    override fun onCleared() {
        super.onCleared()
        stopShell()
        devServer?.stop()
    }
}
