package com.sufyan.harness.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

enum class LineKind { Input, Stdout, Stderr, System }

data class TermLine(val kind: LineKind, val text: String)

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    fun combined(limit: Int = 8000): String =
        (stdout + if (stderr.isNotBlank()) "\n$stderr" else "").take(limit)
}

/** §23 — the last command the session ran, with its real exit code. */
data class LastCommand(val command: String, val exitCode: Int, val stderrTail: String, val finishedAt: Long)

/**
 * A real interactive shell process (Android's /system/bin/sh, or the PRoot runtime shell when
 * installed). Output is streamed line by line; nothing is simulated.
 *
 * §21 — after every command the session writes a sentinel line containing `$?` and `pwd`. Parsing it
 * is what gives the UI a genuine exit code, a genuine current directory and a genuine "is a command
 * still running" state, instead of the guesses the previous implementation made.
 */
class ShellSession(
    val workingDir: File,
    private val env: Map<String, String> = emptyMap(),
    val shell: String = "/system/bin/sh",
    private val maxLines: Int = 3000,
    /** Human name shown in the session manager (§26). */
    val name: String = "shell",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lines = MutableStateFlow<List<TermLine>>(emptyList())
    val lines: StateFlow<List<TermLine>> = _lines

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** True only between sending a command and reading its sentinel. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _cwd = MutableStateFlow(workingDir.absolutePath)
    val cwd: StateFlow<String> = _cwd

    private val _lastCommand = MutableStateFlow<LastCommand?>(null)
    val lastCommand: StateFlow<LastCommand?> = _lastCommand

    val startedAt: Long = System.currentTimeMillis()

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var pumpJobs = mutableListOf<Job>()
    private var currentCommand: String = ""
    private val stderrTail = ArrayDeque<String>()

    val history = mutableListOf<String>()

    /**
     * The real OS pid of the shell, read from the platform's private `Process.pid` field. Android
     * only exposes `Process.pid()` on newer API levels, so this is reflective and returns null
     * rather than inventing a number when the field is not accessible.
     */
    val pid: Int?
        get() = runCatching {
            val p = process ?: return@runCatching null
            val field = p.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            (field.get(p) as? Int)
        }.getOrNull()

    private fun append(kind: LineKind, text: String) {
        _lines.value = (_lines.value + TermLine(kind, text)).let {
            if (it.size > maxLines) it.takeLast(maxLines) else it
        }
    }

    fun start(): Result<Unit> = runCatching {
        if (process != null) return@runCatching
        workingDir.mkdirs()
        val shellFile = File(shell)
        if (!shellFile.exists()) {
            throw IOException("Shell not found: $shell. Set a different shell in Settings \u2192 Terminal.")
        }
        if (!shellFile.canExecute()) {
            throw IOException("Shell is not executable: $shell.")
        }
        val pb = ProcessBuilder(shell, "-i")
            .directory(workingDir)
            .redirectErrorStream(false)
        pb.environment().apply {
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", File(workingDir, ".harness-tmp").apply { mkdirs() }.absolutePath)
            put("TERM", "dumb")
            put("PS1", "")
            putAll(env)
        }
        val p = pb.start()
        process = p
        writer = OutputStreamWriter(p.outputStream)
        _running.value = true
        append(LineKind.System, "Session \u201c$name\u201d started: $shell")
        append(LineKind.System, "Working directory: ${workingDir.absolutePath}")
        pumpJobs += scope.launch { pump(p.inputStream.bufferedReader(), LineKind.Stdout) }
        pumpJobs += scope.launch { pump(p.errorStream.bufferedReader(), LineKind.Stderr) }
        pumpJobs += scope.launch {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            append(LineKind.System, "Shell exited with code $code")
            _running.value = false
            _busy.value = false
            process = null
        }
    }.onFailure {
        append(LineKind.System, it.message ?: "Could not start shell.")
        _running.value = false
    }

    private suspend fun pump(reader: BufferedReader, kind: LineKind) {
        try {
            reader.useLines { seq ->
                seq.forEach { line ->
                    val marker = line.indexOf(SENTINEL)
                    if (marker >= 0) {
                        // "<prefix>__HARNESS__:<exit>:<cwd>"
                        val payload = line.substring(marker + SENTINEL.length)
                        val exit = payload.substringBefore(':').trim().toIntOrNull() ?: -1
                        val dir = payload.substringAfter(':', "").trim()
                        if (dir.isNotEmpty()) _cwd.value = dir
                        val prefix = line.substring(0, marker)
                        if (prefix.isNotBlank()) append(kind, prefix)
                        _lastCommand.value = LastCommand(
                            command = currentCommand,
                            exitCode = exit,
                            stderrTail = stderrTail.joinToString("\n").takeLast(2000),
                            finishedAt = System.currentTimeMillis(),
                        )
                        if (exit != 0 && currentCommand.isNotBlank()) {
                            append(LineKind.System, "exit $exit")
                        }
                        _busy.value = false
                    } else {
                        if (kind == LineKind.Stderr) {
                            stderrTail += line
                            while (stderrTail.size > 40) stderrTail.removeFirst()
                        }
                        append(kind, line)
                    }
                }
            }
        } catch (_: IOException) {
            // stream closed on shutdown — expected
        }
    }

    fun send(command: String): Result<Unit> = runCatching {
        val p = process ?: throw IOException("Shell is not running. Start the session first.")
        if (command.isNotBlank()) history += command
        append(LineKind.Input, command)
        currentCommand = command
        stderrTail.clear()
        _busy.value = true
        val out = writer ?: throw IOException("Shell input stream is closed.")
        out.write(command)
        out.write("\n")
        // Sentinel: real exit status and real working directory of the command just run.
        out.write("printf '%s%d:%s\\n' '$SENTINEL' \"$?\" \"$(pwd)\"\n")
        out.flush()
    }.onFailure {
        _busy.value = false
        append(LineKind.System, it.message ?: "Command could not be sent.")
    }

    /** Interrupts the foreground command by sending ETX, then falls back to killing children. */
    fun interrupt() {
        runCatching {
            writer?.write("\u0003\n")
            writer?.flush()
        }
        append(LineKind.System, "Interrupt sent.")
        _busy.value = false
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        process = null
        _running.value = false
        _busy.value = false
        pumpJobs.forEach { it.cancel() }
        pumpJobs.clear()
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
        /** Marker the shell echoes after each command. Chosen so it cannot collide with normal output. */
        const val SENTINEL = "__HARNESS_RC__:"

        /**
         * One-shot command execution used by the AI agent's run_command tool and by
         * toolchain detection. Fully real: captures stdout, stderr and exit code.
         */
        suspend fun exec(
            command: String,
            workingDir: File,
            timeoutMs: Long = 120_000,
            shell: String = "/system/bin/sh",
            env: Map<String, String> = emptyMap(),
        ): CommandResult = withContext(Dispatchers.IO) {
            try {
                workingDir.mkdirs()
                val pb = ProcessBuilder(shell, "-c", command).directory(workingDir)
                pb.environment().apply {
                    put("HOME", workingDir.absolutePath)
                    put("TERM", "dumb")
                    putAll(env)
                }
                val p = pb.start()
                val out = StringBuilder()
                val err = StringBuilder()
                val to = Thread { InputStreamReader(p.inputStream).useLines { s -> s.forEach { out.appendLine(it) } } }
                val te = Thread { InputStreamReader(p.errorStream).useLines { s -> s.forEach { err.appendLine(it) } } }
                to.start(); te.start()
                val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    return@withContext CommandResult(-1, out.toString(), "Command timed out after ${timeoutMs / 1000}s.")
                }
                to.join(2000); te.join(2000)
                CommandResult(p.exitValue(), out.toString().trimEnd(), err.toString().trimEnd())
            } catch (e: Exception) {
                CommandResult(-1, "", e.message ?: "Command could not be executed.")
            }
        }
    }
}
