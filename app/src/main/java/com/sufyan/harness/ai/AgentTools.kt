package com.sufyan.harness.ai

import com.sufyan.harness.data.ProjectFiles
import com.sufyan.harness.runtime.ShellSession
import kotlinx.serialization.json.*
import java.io.File

data class ToolResult(val ok: Boolean, val summary: String, val payload: String)

/**
 * Real tool implementations backing the agent loop. Every tool touches the
 * actual filesystem or spawns an actual process — none of them are stubbed.
 */
class AgentTools(
    private val files: ProjectFiles,
    private val projectDir: File,
    private val commandsEnabled: Boolean,
) {

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

    suspend fun execute(call: ToolCall): ToolResult {
        val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrElse { return ToolResult(false, "Invalid arguments", "Tool arguments were not valid JSON.") }

        fun s(k: String): String? = args[k]?.jsonPrimitive?.contentOrNull
        fun i(k: String): Int? = args[k]?.jsonPrimitive?.intOrNull

        return try {
            when (call.name) {
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
                    files.write(path, content).fold(
                        { ToolResult(true, "Wrote $path", "Wrote ${content.length} characters to $path.") },
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
                            files.write(path, current.replaceFirst(old, new)).fold(
                                { ToolResult(true, "Edited $path", "Replaced 1 occurrence in $path.") },
                                { ToolResult(false, "Could not write $path", it.message ?: "Write failed.") },
                            )
                        }
                    }
                }

                "delete_file" -> {
                    val path = s("path") ?: return ToolResult(false, "Missing path", "path is required.")
                    files.delete(path).fold(
                        { ToolResult(true, "Deleted $path", "$path was deleted.") },
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
                    val timeout = ((i("timeout_seconds") ?: 90).coerceIn(1, 300)) * 1000L
                    val res = ShellSession.exec(cmd, projectDir, timeout)
                    ToolResult(
                        res.ok,
                        if (res.ok) "Ran: $cmd" else "Command failed (exit ${res.exitCode}): $cmd",
                        "exit code: ${res.exitCode}\n${res.combined()}",
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
}
