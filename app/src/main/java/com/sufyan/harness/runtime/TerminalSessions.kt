package com.sufyan.harness.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** §26 — one entry in the session list. Everything shown comes from the live [ShellSession]. */
data class SessionInfo(
    val id: String,
    val name: String,
    val cwd: String,
    val running: Boolean,
    val busy: Boolean,
    val pid: Int?,
    val startedAt: Long,
    val lineCount: Int,
)

/**
 * §26 — multiple concurrent shell sessions.
 *
 * Each session is a genuinely separate OS process with its own buffer, working directory and
 * lifecycle; closing one does not touch the others. The manager owns them so the ViewModel can hand
 * out a single active session while background sessions keep running (a dev server, for example).
 */
class TerminalSessions {

    private val sessions = LinkedHashMap<String, ShellSession>()

    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    fun get(id: String?): ShellSession? = id?.let { sessions[it] }

    val active: ShellSession? get() = get(_activeId.value)

    fun info(): List<SessionInfo> = sessions.map { (id, s) ->
        SessionInfo(
            id = id,
            name = s.name,
            cwd = s.cwd.value,
            running = s.running.value,
            busy = s.busy.value,
            pid = s.pid,
            startedAt = s.startedAt,
            lineCount = s.lines.value.size,
        )
    }

    /**
     * Creates and starts a session. Failure is returned, never swallowed: if the shell binary is
     * missing the caller shows why instead of displaying a dead terminal.
     */
    fun open(
        name: String,
        workingDir: File,
        shell: String,
        env: Map<String, String>,
        scrollback: Int,
    ): Result<String> {
        val session = ShellSession(workingDir, env, shell, scrollback, name)
        return session.start().map {
            val id = "s${System.currentTimeMillis().toString(36)}${sessions.size}"
            sessions[id] = session
            _ids.value = sessions.keys.toList()
            _activeId.value = id
            id
        }.onFailure { session.dispose() }
    }

    fun select(id: String) {
        if (sessions.containsKey(id)) _activeId.value = id
    }

    fun close(id: String) {
        sessions.remove(id)?.dispose()
        _ids.value = sessions.keys.toList()
        if (_activeId.value == id) _activeId.value = sessions.keys.lastOrNull()
    }

    fun closeAll() {
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        _ids.value = emptyList()
        _activeId.value = null
    }

    val runningCount: Int get() = sessions.values.count { it.running.value }
}
