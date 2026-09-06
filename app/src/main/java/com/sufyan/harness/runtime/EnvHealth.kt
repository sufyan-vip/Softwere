package com.sufyan.harness.runtime

import java.io.File

/** One probed fact about the environment. [ok] is only true when a real command/check succeeded. */
data class HealthItem(
    val label: String,
    val ok: Boolean,
    val value: String,
    val hint: String? = null,
)

data class EnvReport(
    val items: List<HealthItem>,
    val tools: List<ToolStatus>,
    val runtimeLabel: String,
    val path: String,
) {
    val healthy: Boolean get() = items.all { it.ok }
    val missingTools: List<ToolStatus> get() = tools.filterNot { it.available }
}

/**
 * §22 / §28 — real environment health.
 *
 * Every line of the report comes from executing something: `echo $PATH`, a write+exec attempt in the
 * project directory, `uname -a` inside the PRoot runtime, and each tool's own probe. Nothing is
 * assumed from the Android version or from a file merely existing (§3).
 */
class EnvHealth(private val linux: LinuxRuntime, private val toolchains: Toolchains) {

    suspend fun inspect(workingDir: File, shell: String): EnvReport {
        val usingLinux = linux.rootfsPresent() && linux.prootAvailable()
        val runtimeLabel = if (usingLinux) "Linux runtime" else "Android shell"

        val items = mutableListOf<HealthItem>()

        // 1. Shell — does the configured binary exist and actually run?
        val shellFile = File(shell)
        val shellProbe = ShellSession.exec("echo harness-ok", workingDir, 10_000, shell)
        items += HealthItem(
            label = "Shell",
            ok = shellProbe.ok && shellProbe.stdout.contains("harness-ok"),
            value = if (shellProbe.ok) shell else "$shell failed",
            hint = if (!shellFile.exists()) "The configured shell does not exist on this device." else null,
        )

        // 2. Runtime — PRoot userspace, only claimed when `uname` really answers from inside it.
        val unameInside = if (usingLinux) linux.exec("uname -a", workingDir, 15_000) else null
        items += HealthItem(
            label = "Runtime",
            ok = if (usingLinux) unameInside?.ok == true else shellProbe.ok,
            value = when {
                usingLinux && unameInside?.ok == true -> unameInside.stdout.lineSequence().firstOrNull()?.take(60).orEmpty()
                usingLinux -> "PRoot present but not responding"
                else -> "Android shell (no Linux userspace installed)"
            },
            hint = if (!usingLinux) "Install the Linux runtime to get Git, Node, curl and package management." else null,
        )

        // 3. PATH — the real one the shell exports.
        val pathResult = ShellSession.exec("echo \$PATH", workingDir, 10_000, shell)
        val path = pathResult.stdout.trim()
        items += HealthItem(
            label = "PATH",
            ok = pathResult.ok && path.isNotEmpty(),
            value = path.ifEmpty { "empty" },
            hint = if (path.isEmpty()) "An empty PATH means only absolute paths can be executed." else null,
        )

        // 4. HOME / working directory — must exist and be writable, or nothing works.
        val writable = runCatching {
            val probe = File(workingDir, ".harness-write-check")
            probe.writeText("ok")
            val read = probe.readText() == "ok"
            probe.delete()
            read
        }.getOrDefault(false)
        items += HealthItem(
            label = "Home",
            ok = writable,
            value = workingDir.absolutePath,
            hint = if (!writable) "The project directory is not writable, so no command can create files." else null,
        )

        // 5. Exec permission — Android blocks execution from app data on most devices; state it plainly.
        val execProbe = runCatching {
            val script = File(workingDir, ".harness-exec-check.sh")
            script.writeText("#!/system/bin/sh\necho exec-ok\n")
            val allowed = script.setExecutable(true)
            val res = if (allowed) ShellSession.exec(script.absolutePath, workingDir, 10_000, shell) else null
            script.delete()
            res?.ok == true && res.stdout.contains("exec-ok")
        }.getOrDefault(false)
        items += HealthItem(
            label = "Exec in workspace",
            ok = execProbe,
            value = if (execProbe) "allowed" else "blocked by the OS",
            hint = if (!execProbe) {
                "Android refuses to execute files stored in app data (W^X). Run scripts through an " +
                    "interpreter, for example `sh build.sh`, instead of executing them directly."
            } else null,
        )

        // 6. Temp directory.
        val tmp = File(workingDir, ".harness-tmp")
        items += HealthItem(
            label = "Temp",
            ok = tmp.exists() || tmp.mkdirs(),
            value = tmp.absolutePath,
        )

        // 7. Free space — a build needs room; report the real figure.
        val free = runCatching { workingDir.usableSpace }.getOrDefault(0L)
        items += HealthItem(
            label = "Free space",
            ok = free > 64L * 1024 * 1024,
            value = "${free / (1024 * 1024)} MB",
            hint = if (free <= 64L * 1024 * 1024) "Less than 64 MB free — installs and builds will fail." else null,
        )

        val tools = toolchains.detectAll(workingDir)
        return EnvReport(items, tools, runtimeLabel, path)
    }

    /**
     * §23 — evidence for a failed command, gathered by really asking the shell where the program is.
     * Only safe, read-only probes are executed.
     */
    suspend fun probeFor(command: String, workingDir: File, shell: String): Probe {
        val exe = CommandDiagnostics.executableOf(command)
        val which = ShellSession.exec("command -v ${shellQuote(exe)}", workingDir, 10_000, shell)
        val path = ShellSession.exec("echo \$PATH", workingDir, 10_000, shell).stdout.trim()
        val ready = linux.rootfsPresent() && linux.prootAvailable()
        return Probe(
            executable = exe,
            onPath = which.ok && which.stdout.isNotBlank(),
            path = path,
            runtimeLabel = if (ready) "Linux runtime" else "Android shell",
            linuxRuntimeReady = ready,
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
