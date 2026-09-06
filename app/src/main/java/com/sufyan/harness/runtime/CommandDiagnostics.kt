package com.sufyan.harness.runtime

/**
 * §4 / §23 — every command failure is explained as WHAT / WHY / HOW, with a concrete action.
 *
 * The classification below never assumes "exit 127 means missing binary" blindly: the caller probes
 * the real environment first ([Probe], filled by `command -v`, `$PATH` and the runtime state) and the
 * diagnosis is derived from that evidence plus the actual stderr text.
 *
 * Pure Kotlin — unit-tested off-device.
 */
data class Probe(
    /** The executable the command tried to run, e.g. "npm" for `npm run build`. */
    val executable: String,
    /** True only when `command -v <exe>` exited 0. */
    val onPath: Boolean,
    /** The real PATH the command saw. */
    val path: String,
    /** "Android shell" or "Linux runtime". */
    val runtimeLabel: String,
    /** True when a Linux userspace is installed and usable. */
    val linuxRuntimeReady: Boolean = false,
)

/** What the user can do about a failure. Each maps to a real action in the UI. */
sealed interface FixAction {
    /** Install a toolchain id from [Toolchains.CORE]. */
    data class InstallTool(val toolId: String, val label: String) : FixAction
    /** Run a concrete, safe command. */
    data class RunCommand(val command: String, val label: String) : FixAction
    /** Open the Linux runtime screen (install / repair). */
    data object OpenRuntime : FixAction
    /** Just retry the same command. */
    data object Retry : FixAction
}

data class Diagnosis(
    val what: String,
    val why: String,
    val how: String,
    val actions: List<FixAction> = emptyList(),
) {
    fun render(): String = "$what\n\n$why\n\n$how"
}

object CommandDiagnostics {

    /** Commands that stock Android genuinely does not ship, mapped to the toolchain id that provides them. */
    private val PROVIDED_BY = mapOf(
        "node" to "node", "npm" to "npm", "npx" to "npm", "yarn" to "npm", "pnpm" to "npm",
        "git" to "git", "curl" to "curl", "wget" to "curl", "openssl" to "openssl",
        "python" to "python", "python3" to "python", "pip" to "python", "pip3" to "python",
        "java" to "java", "javac" to "java", "gradle" to "gradle", "./gradlew" to "gradle",
    )

    /** First word of a command line, ignoring leading env assignments like `FOO=1 cmd`. */
    fun executableOf(commandLine: String): String {
        val parts = commandLine.trim().split(Regex("\\s+"))
        for (p in parts) {
            if (p.contains('=') && !p.startsWith("/") && !p.startsWith(".")) continue
            return p
        }
        return parts.firstOrNull().orEmpty()
    }

    fun diagnose(command: String, exitCode: Int, stderr: String, stdout: String = "", probe: Probe): Diagnosis {
        val err = stderr.trim()
        val exe = probe.executable.ifBlank { executableOf(command) }
        val lower = err.lowercase()
        val notFound = exitCode == 127 ||
            lower.contains("not found") ||
            lower.contains("no such file or directory") && lower.contains(exe.lowercase())

        // 1. Missing executable, confirmed by the probe rather than assumed from the exit code.
        if (notFound && !probe.onPath) {
            val toolId = PROVIDED_BY[exe]
            val how = if (toolId != null) {
                if (probe.linuxRuntimeReady) {
                    "Install it inside the Linux runtime, then run the command again."
                } else {
                    "Android does not ship $exe. Install the Linux runtime (Settings \u2192 Toolchains) which " +
                        "provides it, or run this command on a machine that has $exe."
                }
            } else {
                "Check the spelling, or give the full path to the program. The current PATH is:\n${probe.path}"
            }
            val actions = buildList {
                if (toolId != null) add(FixAction.InstallTool(toolId, "Install ${Toolchains.labelFor(toolId)}"))
                if (!probe.linuxRuntimeReady) add(FixAction.OpenRuntime)
                add(FixAction.RunCommand("echo \$PATH", "Show PATH"))
            }
            return Diagnosis(
                what = "Command unavailable",
                why = "\u201c$exe\u201d was not found in the ${probe.runtimeLabel}. " +
                    "`command -v $exe` produced no result, so the program genuinely is not installed on this PATH.",
                how = how,
                actions = actions,
            )
        }

        // 2. Found on PATH but exit 127 anyway — usually a missing shared library or interpreter.
        if (notFound && probe.onPath) {
            return Diagnosis(
                what = "Program could not start",
                why = "\u201c$exe\u201d exists on PATH but the loader still reported \u201cnot found\u201d. " +
                    "That normally means a missing interpreter (a wrong #! line) or a missing shared library.",
                how = "Run `head -1 $exe` to check the interpreter, or `ldd $(command -v $exe)` inside the Linux " +
                    "runtime to list the libraries it needs.",
                actions = listOf(
                    FixAction.RunCommand("command -v $exe", "Locate $exe"),
                    FixAction.RunCommand("head -1 $(command -v $exe)", "Show interpreter"),
                ),
            )
        }

        if (exitCode == 126) {
            return Diagnosis(
                what = "Permission denied",
                why = "The file was found but could not be executed (exit 126). On Android, files stored in app " +
                    "data are often mounted without the execute bit, and a directory is not runnable.",
                how = "Run it through its interpreter instead (for example `sh script.sh`), or `chmod +x` the file " +
                    "if it lives somewhere execution is permitted.",
                actions = listOf(FixAction.RunCommand("ls -l $exe", "Inspect permissions")),
            )
        }

        if (exitCode == 130 || lower.contains("interrupt")) {
            return Diagnosis(
                what = "Command interrupted",
                why = "The command was stopped before it finished (exit 130 = SIGINT).",
                how = "Run it again if that was not intentional.",
                actions = listOf(FixAction.Retry),
            )
        }

        if (exitCode == 137 || lower.contains("killed")) {
            return Diagnosis(
                what = "Command was killed",
                why = "The process was terminated by the system (exit 137 = SIGKILL) \u2014 on a phone this is almost " +
                    "always the low-memory killer, or Android stopping background work.",
                how = "Close other apps, keep the Harness screen open while the command runs, or split the work " +
                    "into smaller steps.",
                actions = listOf(FixAction.Retry),
            )
        }

        if (exitCode == -1 && lower.contains("timed out")) {
            return Diagnosis(
                what = "Command timed out",
                why = err,
                how = "Increase the timeout, or run the command in the terminal where it can keep going while you watch it.",
                actions = listOf(FixAction.Retry),
            )
        }

        // Tool-specific, evidence based.
        if (lower.contains("enoent") && lower.contains("package.json")) {
            return Diagnosis(
                what = "No package.json here",
                why = "npm looked for package.json in the working directory and did not find one.",
                how = "Change into the directory that contains package.json, or run `npm init -y` to create one.",
                actions = listOf(FixAction.RunCommand("ls", "List this directory")),
            )
        }
        if (lower.contains("cannot find module")) {
            val mod = Regex("cannot find module '([^']+)'", RegexOption.IGNORE_CASE).find(err)?.groupValues?.get(1)
            return Diagnosis(
                what = "Missing dependency",
                why = "Node could not resolve ${mod?.let { "\u201c$it\u201d" } ?: "a required module"}. " +
                    "It is imported by the code but not installed.",
                how = "Install dependencies with `npm install`" + (mod?.let { ", or add it with `npm install $it`" } ?: "") + ".",
                actions = listOf(FixAction.RunCommand("npm install", "Run npm install")),
            )
        }
        if (lower.contains("eacces") || lower.contains("read-only file system")) {
            return Diagnosis(
                what = "Filesystem is not writable",
                why = "The command tried to write somewhere this app cannot write. Android only grants write access " +
                    "inside the app's own storage.",
                how = "Work inside the project directory. Paths outside it are read-only for this process.",
            )
        }
        if (lower.contains("address already in use") || lower.contains("eaddrinuse")) {
            val port = Regex(":(\\d{2,5})").find(err)?.groupValues?.get(1)
            return Diagnosis(
                what = "Port already in use",
                why = "Another process is already listening on ${port?.let { "port $it" } ?: "that port"}.",
                how = "Stop the running server from the Preview screen, or start this one on a different port.",
            )
        }
        if (lower.contains("could not resolve host") || lower.contains("temporary failure in name resolution")) {
            return Diagnosis(
                what = "Network unreachable",
                why = "DNS resolution failed, so the command could not reach the internet.",
                how = "Check the device's connection. Note that some carriers block plain-HTTP registries.",
                actions = listOf(FixAction.Retry),
            )
        }

        if (exitCode == 0) {
            return Diagnosis(
                what = "Command succeeded",
                why = "Exit code 0.",
                how = "No action needed.",
            )
        }

        return Diagnosis(
            what = "Command failed (exit $exitCode)",
            why = err.ifBlank { stdout.trim().takeLast(400).ifBlank { "The command produced no output." } },
            how = "Read the output above for the specific error. Run the command again with more verbose flags " +
                "if the cause is not clear.",
            actions = listOf(FixAction.Retry),
        )
    }
}
