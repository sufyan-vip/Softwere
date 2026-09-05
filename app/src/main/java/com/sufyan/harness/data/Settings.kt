package com.sufyan.harness.data

import android.content.Context
import com.sufyan.harness.ui.theme.ThemeMode

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

    var terminalFontSize: Int
        get() = prefs.getInt(TERM_FONT, 12)
        set(v) = prefs.edit().putInt(TERM_FONT, v.coerceIn(8, 20)).apply()

    var agentAutoApprove: Boolean
        get() = prefs.getBoolean(AUTO_APPROVE, false)
        set(v) = prefs.edit().putBoolean(AUTO_APPROVE, v).apply()

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
        const val SYS_PROMPT = "system_prompt"
        const val TEMP = "temperature"
        const val EDITOR_FONT = "editor_font"
        const val LINE_NUMBERS = "line_numbers"
        const val WORD_WRAP = "word_wrap"
        const val TAB_SIZE = "tab_size"
        const val TERM_FONT = "terminal_font"
        const val AUTO_APPROVE = "agent_auto_approve"
        const val LAST_PROJECT = "last_project"
        const val RECENT_MODELS = "recent_models"
    }
}
