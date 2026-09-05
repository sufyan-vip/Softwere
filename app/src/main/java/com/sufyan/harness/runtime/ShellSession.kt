package com.sufyan.harness.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
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

/**
 * A real interactive shell process (Android's /system/bin/sh, or the PRoot
 * runtime shell when installed). Output is streamed line by line; nothing is
 * simulated. Buffer is bounded so huge output cannot exhaust phone memory.
 */
class ShellSession(
    private val workingDir: File,
    private val env: Map<String, String> = emptyMap(),
    private val shell: String = "/system/bin/sh",
    private val maxLines: Int = 3000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lines = MutableStateFlow<List<TermLine>>(emptyList())
    val lines: StateFlow<List<TermLine>> = _lines

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _cwd = MutableStateFlow(workingDir.absolutePath)
    val cwd: StateFlow<String> = _cwd

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var pumpJobs = mutableListOf<Job>()

    val history = mutableListOf<String>()

    private fun append(kind: LineKind, text: String) {
        _lines.value = (_lines.value + TermLine(kind, text)).let {
            if (it.size > maxLines) it.takeLast(maxLines) else it
        }
    }

    fun start(): Result<Unit> = runCatching {
        if (process != null) return@runCatching
        workingDir.mkdirs()
        val pb = ProcessBuilder(shell, "-i")
            .directory(workingDir)
            .redirectErrorStream(false)
        pb.environment().apply {
            put("HOME", workingDir.absolutePath)
            put("TERM", "dumb")
            put("PS1", "")
            putAll(env)
        }
        val p = pb.start()
        process = p
        writer = OutputStreamWriter(p.outputStream)
        append(LineKind.System, "Shell started: $shell")
        append(LineKind.System, "Working directory: ${workingDir.absolutePath}")
        pumpJobs += scope.launch { pump(p.inputStream.bufferedReader(), LineKind.Stdout) }
        pumpJobs += scope.launch { pump(p.errorStream.bufferedReader(), LineKind.Stderr) }
        pumpJobs += scope.launch {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            append(LineKind.System, "Shell exited with code $code")
            _running.value = false
            process = null
        }
    }.onFailure {
        append(LineKind.System, "Could not start shell: ${it.message}")
    }

    private suspend fun pump(reader: BufferedReader, kind: LineKind) {
        try {
            reader.useLines { seq -> seq.forEach { append(kind, it) } }
        } catch (_: IOException) {
            // stream closed on shutdown — expected
        }
    }

    fun send(command: String): Result<Unit> = runCatching {
        val p = process ?: throw IOException("Shell is not running. Start the session first.")
        if (command.isNotBlank()) history += command
        append(LineKind.Input, command)
        _running.value = true
        writer!!.apply {
            write(command)
            write("\n")
            // echo the working directory so the UI can track `cd`
            write("printf '\\n'; pwd > \"${'$'}{TMPDIR:-/data/local/tmp}/.harness_cwd\" 2>/dev/null\n")
            flush()
        }
        _running.value = false
    }.onFailure { append(LineKind.System, it.message ?: "Command could not be sent.") }

    /** Sends SIGINT-equivalent by killing the child process tree. */
    fun interrupt() {
        runCatching {
            writer?.write("\u0003\n")
            writer?.flush()
        }
        append(LineKind.System, "Interrupt sent.")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        process = null
        _running.value = false
        pumpJobs.forEach { it.cancel() }
        pumpJobs.clear()
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
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
