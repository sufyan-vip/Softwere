package com.sufyan.harness.data

import android.content.Context
import com.sufyan.harness.ui.theme.ThemeMode

/**
 * §48 — how much freedom the agent has to change the project.
 *
 * The mode is enforced in [com.sufyan.harness.ai.AgentTools], not in the UI, so a tool call cannot
 * bypass it. "Safe" means read-only: listing, reading, searching and planning.
 */
enum class AgentPermission(val id: String, val label: String, val blurb: String) {
    AskEvery("ask_every", "Ask before every change", "Every write, edit, delete or command waits for you."),
    AskDestructive("ask_destructive", "Ask before destructive actions", "Deletes, overwrites of existing files and commands need approval."),
    AutoSafe("auto_safe", "Auto-approve safe actions", "Reading, searching and planning run freely; edits still apply immediately.");

    companion object {
        fun from(id: String?): AgentPermission = entries.firstOrNull { it.id == id } ?: AskDestructive
    }
}

/** Plain (non-secret) preferences. Secrets live in [SecureStore]. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("harness_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(THEME, ThemeMode.Dark.name)!!) }
            .getOrDefault(ThemeMode.Dark)
        set(v) = prefs.edit().putString(THEME, v.name).apply()

    var modelId: String
        get() = prefs.getString(MODEL, "openai/gpt-4o-mini")!!
        set(v) = prefs.edit().putString(MODEL, v).apply()

    /** §5 — custom OpenRouter-compatible endpoint (blank = the default). */
    var endpoint: String
        get() = prefs.getString(ENDPOINT, "")!!
        set(v) = prefs.edit().putString(ENDPOINT, v.trim()).apply()

    /** §5 — model used when the selected one is unavailable; blank disables the fallback. */
    var fallbackModelId: String
        get() = prefs.getString(FALLBACK_MODEL, "")!!
        set(v) = prefs.edit().putString(FALLBACK_MODEL, v.trim()).apply()

    var systemPrompt: String
        get() = prefs.getString(SYS_PROMPT, "")!!
        set(v) = prefs.edit().putString(SYS_PROMPT, v).apply()

    var temperature: Float
        get() = prefs.getFloat(TEMP, 0.2f)
        set(v) = prefs.edit().putFloat(TEMP, v).apply()

    var editorFontSize: Int
        get() = prefs.getInt(EDITOR_FONT, 13)
        set(v) = prefs.edit().putInt(EDITOR_FONT, v.coerceIn(9, 24)).apply()

    var lineNumbers: Boolean
        get() = prefs.getBoolean(LINE_NUMBERS, true)
        set(v) = prefs.edit().putBoolean(LINE_NUMBERS, v).apply()

    var wordWrap: Boolean
        get() = prefs.getBoolean(WORD_WRAP, false)
        set(v) = prefs.edit().putBoolean(WORD_WRAP, v).apply()

    var tabSize: Int
        get() = prefs.getInt(TAB_SIZE, 2)
        set(v) = prefs.edit().putInt(TAB_SIZE, v.coerceIn(1, 8)).apply()

    // ---- terminal (§24: every one of these is read by the runtime) ----------
    var terminalFontSize: Int
        get() = prefs.getInt(TERM_FONT, 12)
        set(v) = prefs.edit().putInt(TERM_FONT, v.coerceIn(8, 20)).apply()

    /** §24 — honoured by the terminal renderer: off means each line scrolls horizontally. */
    var terminalWordWrap: Boolean
        get() = prefs.getBoolean(TERM_WRAP, false)
        set(v) = prefs.edit().putBoolean(TERM_WRAP, v).apply()

    /** §24 — real cap on retained output lines, passed to the shell session buffer. */
    var terminalScrollback: Int
        get() = prefs.getInt(TERM_SCROLLBACK, 3000)
        set(v) = prefs.edit().putInt(TERM_SCROLLBACK, v.coerceIn(200, 20000)).apply()

    /** §24 — clears the buffer when a new session starts. */
    var terminalClearOnNewSession: Boolean
        get() = prefs.getBoolean(TERM_CLEAR_NEW, true)
        set(v) = prefs.edit().putBoolean(TERM_CLEAR_NEW, v).apply()

    /** §24 — the shell binary actually executed. Validated before use. */
    var terminalShell: String
        get() = prefs.getString(TERM_SHELL, "/system/bin/sh")!!
        set(v) = prefs.edit().putString(TERM_SHELL, v.trim().ifEmpty { "/system/bin/sh" }).apply()

    /** §24 — extra `KEY=value` lines exported into every new session. */
    var terminalEnv: String
        get() = prefs.getString(TERM_ENV, "")!!
        set(v) = prefs.edit().putString(TERM_ENV, v).apply()

    /** §24 — persisted command history across app restarts. */
    fun commandHistory(): List<String> =
        prefs.getString(TERM_HISTORY, "")!!.split('\n').filter { it.isNotBlank() }

    fun pushCommand(cmd: String) {
        if (cmd.isBlank()) return
        val list = (listOf(cmd.trim()) + commandHistory()).distinct().take(200)
        prefs.edit().putString(TERM_HISTORY, list.joinToString("\n")).apply()
    }

    fun clearCommandHistory() = prefs.edit().remove(TERM_HISTORY).apply()

    /** Parsed form of [terminalEnv]; invalid lines are ignored rather than silently breaking the shell. */
    fun terminalEnvMap(): Map<String, String> = terminalEnv.lineSequence()
        .mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || !t.contains('=')) null
            else t.substringBefore('=').trim() to t.substringAfter('=').trim()
        }
        .filter { it.first.isNotEmpty() }
        .toMap()

    // ---- agent (§48, §47) ---------------------------------------------------
    var agentPermission: AgentPermission
        get() = AgentPermission.from(prefs.getString(AGENT_PERMISSION, null))
        set(v) = prefs.edit().putString(AGENT_PERMISSION, v.id).apply()

    /** Legacy flag kept in sync with [agentPermission] so old callers keep working. */
    var agentAutoApprove: Boolean
        get() = agentPermission == AgentPermission.AutoSafe
        set(v) { agentPermission = if (v) AgentPermission.AutoSafe else AgentPermission.AskDestructive }

    /**
     * §48 — the master switch for `run_command`. When it is off the tool is not even offered to the
     * model, so no prompt can talk the agent into running something.
     */
    var agentCommandsEnabled: Boolean
        get() = prefs.getBoolean(AGENT_COMMANDS, true)
        set(v) = prefs.edit().putBoolean(AGENT_COMMANDS, v).apply()

    /** §47 — how many build→fix→build cycles the agent may attempt on its own. */
    var buildFixAttempts: Int
        get() = prefs.getInt(BUILD_FIX_ATTEMPTS, 3)
        set(v) = prefs.edit().putInt(BUILD_FIX_ATTEMPTS, v.coerceIn(0, 6)).apply()

    /** §19 — token budget for the replayed conversation. */
    var contextBudget: Int
        get() = prefs.getInt(CONTEXT_BUDGET, 24000)
        set(v) = prefs.edit().putInt(CONTEXT_BUDGET, v.coerceIn(4000, 200000)).apply()

    /** §51 — post a notification when a long task finishes. */
    var notifyOnTaskComplete: Boolean
        get() = prefs.getBoolean(NOTIFY_TASKS, true)
        set(v) = prefs.edit().putBoolean(NOTIFY_TASKS, v).apply()

    var lastProjectId: String?
        get() = prefs.getString(LAST_PROJECT, null)
        set(v) = prefs.edit().putString(LAST_PROJECT, v).apply()

    fun recentModels(): List<String> =
        prefs.getString(RECENT_MODELS, "")!!.split('\n').filter { it.isNotBlank() }

    fun pushRecentModel(id: String) {
        val list = (listOf(id) + recentModels()).distinct().take(6)
        prefs.edit().putString(RECENT_MODELS, list.joinToString("\n")).apply()
    }

    private companion object {
        const val THEME = "theme_mode"
        const val MODEL = "model_id"
        const val ENDPOINT = "endpoint"
        const val FALLBACK_MODEL = "fallback_model"
        const val SYS_PROMPT = "system_prompt"
        const val TEMP = "temperature"
        const val EDITOR_FONT = "editor_font"
        const val LINE_NUMBERS = "line_numbers"
        const val WORD_WRAP = "word_wrap"
        const val TAB_SIZE = "tab_size"
        const val TERM_FONT = "terminal_font"
        const val TERM_WRAP = "terminal_wrap"
        const val TERM_SCROLLBACK = "terminal_scrollback"
        const val TERM_CLEAR_NEW = "terminal_clear_new"
        const val TERM_SHELL = "terminal_shell"
        const val TERM_ENV = "terminal_env"
        const val TERM_HISTORY = "terminal_history"
        const val AGENT_PERMISSION = "agent_permission"
        const val AGENT_COMMANDS = "agent_commands_enabled"
        const val BUILD_FIX_ATTEMPTS = "build_fix_attempts"
        const val CONTEXT_BUDGET = "context_budget"
        const val NOTIFY_TASKS = "notify_tasks"
        const val LAST_PROJECT = "last_project"
        const val RECENT_MODELS = "recent_models"
    }
}
