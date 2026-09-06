package com.sufyan.harness.ai

import com.sufyan.harness.data.AgentPermission
import com.sufyan.harness.data.ProjectFiles
import com.sufyan.harness.runtime.CommandDiagnostics
import com.sufyan.harness.runtime.CommandResult
import com.sufyan.harness.runtime.Probe
import com.sufyan.harness.runtime.ShellSession
import kotlinx.serialization.json.*
import java.io.File

data class ToolResult(val ok: Boolean, val summary: String, val payload: String)

/** §48 — a mutating action waiting for the user's decision. */
data class ApprovalRequest(
    val tool: String,
    val target: String,
    val description: String,
    val destructive: Boolean,
    /** A short preview of what will happen (content head, or the command line). */
    val preview: String,
)

/**
 * Real tool implementations backing the agent loop. Every tool touches the actual filesystem or
 * spawns an actual process — none of them are stubbed.
 *
 * §48 — mutating tools pass through [approver] according to [permission]; the gate lives here, not in
 * the UI, so no code path can skip it. §46 — `project_info` reports the *detected* build commands so
 * the model does not invent one. §23 — a failed command comes back with a real diagnosis attached.
 */
class AgentTools(
    private val files: ProjectFiles,
    private val projectDir: File,
    private val commandsEnabled: Boolean,
    private val permission: AgentPermission = AgentPermission.AskDestructive,
    private val approver: (suspend (ApprovalRequest) -> Boolean)? = null,
    /** Optional environment probe used to explain command failures (§23). */
    private val probeFor: (suspend (String) -> Probe)? = null,
    /** Project type metadata line shown to the model by `project_info`. */
    private val projectSummary: String = "",
) {

    /** Files this instance created or modified — used for the session summary and change review. */
    val changedFiles = linkedSetOf<String>()

    /** Commands actually executed through the agent. */
    val executedCommands = mutableListOf<String>()

    private fun obj(build: JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String>) = obj {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun str(desc: String) = obj { put("type", "string"); put("description", desc) }
    private fun int(desc: String) = obj { put("type", "integer"); put("description", desc) }

    fun schemas(): List<ToolSchema> = buildList {
        add(
            ToolSchema(
                "project_info",
                "Report the project type and the build/dev/test commands that were actually detected in " +
                    "this project. Call this before running any command so you never guess one.",
                schema(required = emptyList()),
            ),
        )
        add(
            ToolSchema(
                "list_files",
                "List files and directories in the project. Use this first to understand the project layout.",
                schema("path" to str("Directory relative to project root. Use \"\" for the root."), required = emptyList()),
            ),
        )
        add(
            ToolSchema(
                "read_file",
                "Read the full text content of a file in the project.",
                schema("path" to str("File path relative to project root."), required = listOf("path")),
            ),
        )
        add(
            ToolSchema(
                "write_file",
                "Create a file or overwrite it completely with new content.",
                schema(
                    "path" to str("File path relative to project root."),
                    "content" to str("Complete new file content."),
                    required = listOf("path", "content"),
                ),
            ),
        )
        add(
            ToolSchema(
                "edit_file",
                "Replace an exact snippet inside an existing file. Fails if the snippet is absent or ambiguous.",
                schema(
                    "path" to str("File path relative to project root."),
                    "old_text" to str("Exact text to find."),
                    "new_text" to str("Replacement text."),
                    required = listOf("path", "old_text", "new_text"),
                ),
            ),
        )
        add(
            ToolSchema(
                "delete_file",
                "Delete a file or directory from the project.",
                schema("path" to str("Path relative to project root."), required = listOf("path")),
            ),
        )
        add(
            ToolSchema(
                "search",
                "Search the project for a text string and return matching lines.",
                schema(
                    "query" to str("Text to search for."),
                    "limit" to int("Maximum matches to return (default 60)."),
                    required = listOf("query"),
                ),
            ),
        )
        if (commandsEnabled) {
            add(
                ToolSchema(
                    "run_command",
                    "Run a shell command in the project directory and return stdout, stderr and the exit code.",
                    schema(
                        "command" to str("Shell command to execute."),
                        "timeout_seconds" to int("Timeout in seconds (default 90, max 300)."),
                        required = listOf("command"),
                    ),
                ),
            )
        }
    }

    /** Read-only tools are always safe (§48). */
    private fun isSafe(tool: String) = tool in setOf("project_info", "list_files", "read_file", "search")

    private fun isDestructive(tool: String, path: String?) = when (tool) {
        "delete_file" -> true
        "write_file" -> path != null && runCatching { files.resolve(path).exists() }.getOrDefault(false)
        "run_command" -> true
        else -> false
    }

    /** Applies the configured permission mode. Returns null when allowed, or a refusal result. */
    private suspend fun gate(tool: String, target: String, description: String, preview: String): ToolResult? {
        if (isSafe(tool)) return null
        val destructive = isDestructive(tool, target.takeIf { it.isNotBlank() })
        val needsApproval = when (permission) {
            AgentPermission.AskEvery -> true
            AgentPermission.AskDestructive -> destructive
            AgentPermission.AutoSafe -> false
        }
        if (!needsApproval) return null
        val ask = approver ?: return ToolResult(
            false,
            "Approval required",
            "This action needs your approval but no approval channel is available. " +
                "Change the agent permission mode in Settings if you want it to run unattended.",
        )
        val approved = ask(ApprovalRequest(tool, target, description, destructive, preview.take(600)))
        return if (approved) null else ToolResult(
            false,
            "Declined by you",
            "You declined this action. Nothing was changed. Suggest an alternative or ask the user what to do instead.",
        )
    }

    suspend fun execute(call: ToolCall): ToolResult {
        val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrElse { return ToolResult(false, "Invalid arguments", "Tool arguments were not valid JSON.") }

        fun s(k: String): String? = args[k]?.jsonPrimitive?.contentOrNull
        fun i(k: String): Int? = args[k]?.jsonPrimitive?.intOrNull

        return try {
            when (call.name) {
                "project_info" -> ToolResult(
                    true,
                    "Read project metadata",
                    projectSummary.ifBlank { "No project metadata is available." },
                )

                "list_files" -> {
                    val rel = s("path").orEmpty()
                    val dir = files.resolve(rel)
                    if (!dir.isDirectory) return ToolResult(false, "Not a directory", "$rel is not a directory.")
                    val listing = files.list(dir).joinToString("\n") {
                        (if (it.isDirectory) "dir  " else "file ") + files.relativePath(it) +
                            (if (it.isFile) "  (${it.length()} bytes)" else "")
                    }
                    ToolResult(true, "Listed ${if (rel.isEmpty()) "project root" else rel}", listing.ifEmpty { "(empty directory)" })
                }

                "read_file" -> {
                    val path = s("path") ?: return ToolResult(false, "Missing path", "path is required.")
                    files.read(path).fold(
                        { ToolResult(true, "Read $path", it) },
                        { ToolResult(false, "Could not read $path", it.message ?: "Read failed.") },
                    )
                }

                "write_file" -> {
                    val path = s("path") ?: return ToolResult(false, "Missing path", "path is required.")
                    val content = s("content") ?: return ToolResult(false, "Missing content", "content is required.")
                    val exists = runCatching { files.resolve(path).exists() }.getOrDefault(false)
                    gate(
                        "write_file", path,
                        if (exists) "Overwrite $path" else "Create $path",
                        content.take(400),
                    )?.let { return it }
                    files.write(path, content).fold(
                        {
                            changedFiles += path
                            ToolResult(true, "Wrote $path", "Wrote ${content.length} characters to $path.")
                        },
                        { ToolResult(false, "Could not write $path", it.message ?: "Write failed.") },
                    )
                }

                "edit_file" -> {
                    val path = s("path") ?: return ToolResult(false, "Missing path", "path is required.")
                    val old = s("old_text") ?: return ToolResult(false, "Missing old_text", "old_text is required.")
                    val new = s("new_text") ?: return ToolResult(false, "Missing new_text", "new_text is required.")
                    val current = files.read(path).getOrElse {
                        return ToolResult(false, "Could not read $path", it.message ?: "Read failed.")
                    }
                    val count = current.split(old).size - 1
                    when {
                        count == 0 -> ToolResult(false, "Snippet not found in $path", "old_text does not appear in $path.")
                        count > 1 -> ToolResult(false, "Snippet is ambiguous in $path", "old_text appears $count times; include more context.")
                        else -> {
                            gate("edit_file", path, "Edit $path", "- ${old.take(180)}\n+ ${new.take(180)}")?.let { return it }
                            files.write(path, current.replaceFirst(old, new)).fold(
                                {
                                    changedFiles += path
                                    ToolResult(true, "Edited $path", "Replaced 1 occurrence in $path.")
                                },
                                { ToolResult(false, "Could not write $path", it.message ?: "Write failed.") },
                            )
                        }
                    }
                }

                "delete_file" -> {
                    val path = s("path") ?: return ToolResult(false, "Missing path", "path is required.")
                    gate("delete_file", path, "Delete $path", "This permanently removes $path from the project.")
                        ?.let { return it }
                    files.delete(path).fold(
                        {
                            changedFiles += path
                            ToolResult(true, "Deleted $path", "$path was deleted.")
                        },
                        { ToolResult(false, "Could not delete $path", it.message ?: "Delete failed.") },
                    )
                }

                "search" -> {
                    val q = s("query") ?: return ToolResult(false, "Missing query", "query is required.")
                    val hits = files.search(q, (i("limit") ?: 60).coerceIn(1, 200))
                    ToolResult(true, "Searched for \"$q\" (${hits.size} matches)", hits.joinToString("\n").ifEmpty { "No matches." })
                }

                "run_command" -> {
                    if (!commandsEnabled) {
                        return ToolResult(false, "Command execution disabled", "Enable agent command execution in Project Settings.")
                    }
                    val cmd = s("command") ?: return ToolResult(false, "Missing command", "command is required.")
                    gate("run_command", cmd, "Run: $cmd", cmd)?.let { return it }
                    val timeout = ((i("timeout_seconds") ?: 90).coerceIn(1, 300)) * 1000L
                    executedCommands += cmd
                    val res = ShellSession.exec(cmd, projectDir, timeout)
                    ToolResult(
                        res.ok,
                        if (res.ok) "Ran: $cmd" else "Command failed (exit ${res.exitCode}): $cmd",
                        buildString {
                            append("exit code: ${res.exitCode}\n")
                            append(res.combined())
                            if (!res.ok) {
                                append("\n\n")
                                append(explain(cmd, res))
                            }
                        },
                    )
                }

                else -> ToolResult(false, "Unknown tool ${call.name}", "This tool is not implemented.")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Blocked", e.message ?: "Path escapes the project sandbox.")
        } catch (e: Exception) {
            ToolResult(false, "Tool error", e.message ?: e::class.java.simpleName)
        }
    }

    /** §23 — turns a failure into an explanation the model can act on, based on a real probe. */
    private suspend fun explain(command: String, result: CommandResult): String {
        val probe = probeFor?.invoke(command) ?: Probe(
            executable = CommandDiagnostics.executableOf(command),
            onPath = false,
            path = "",
            runtimeLabel = "Android shell",
        )
        val diagnosis = CommandDiagnostics.diagnose(command, result.exitCode, result.stderr, result.stdout, probe)
        return "Diagnosis: ${diagnosis.what}\n${diagnosis.why}\nSuggested fix: ${diagnosis.how}"
    }
}
