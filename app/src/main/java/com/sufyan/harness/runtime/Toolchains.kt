package com.sufyan.harness.runtime

import java.io.File

data class Tool(
    val id: String,
    val label: String,
    val probe: String,
    val description: String,
)

data class ToolStatus(
    val tool: Tool,
    val available: Boolean,
    val version: String?,
    val detail: String,
)

/**
 * Toolchain detection. A tool is reported available ONLY when its probe command
 * actually runs and exits 0 — never based on a file merely existing.
 */
class Toolchains(private val linux: LinuxRuntime) {

    companion object {
        val CORE = listOf(
            Tool("sh", "Shell", "echo ok", "POSIX shell used to run commands."),
            Tool("git", "Git", "git --version", "Version control: status, diff, commit."),
            Tool("node", "Node.js", "node --version", "JavaScript runtime for servers and build tools."),
            Tool("npm", "npm", "npm --version", "Node package manager."),
            Tool("curl", "curl", "curl --version", "HTTP client."),
            Tool("openssl", "OpenSSL", "openssl version", "TLS and crypto utilities."),
            Tool("python", "Python", "python3 --version", "Optional Python interpreter."),
        )
    }

    suspend fun detect(tool: Tool, workingDir: File): ToolStatus {
        // Prefer the Linux runtime, fall back to the Android shell.
        val viaLinux = if (linux.rootfsPresent() && linux.prootAvailable()) {
            linux.exec(tool.probe, workingDir, 20_000)
        } else null

        val result = viaLinux?.takeIf { it.ok }
            ?: ShellSession.exec(tool.probe, workingDir, 20_000)

        val where = if (viaLinux?.ok == true) "Linux runtime" else "Android shell"
        return if (result.ok) {
            ToolStatus(tool, true, result.stdout.lineSequence().firstOrNull()?.trim(), "Verified in $where.")
        } else {
            ToolStatus(
                tool, false, null,
                result.stderr.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Not found on PATH.",
            )
        }
    }

    suspend fun detectAll(workingDir: File): List<ToolStatus> = CORE.map { detect(it, workingDir) }
}
