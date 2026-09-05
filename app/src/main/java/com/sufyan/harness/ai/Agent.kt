package com.sufyan.harness.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** UI-facing agent events. Chain-of-thought is never surfaced — only summaries. */
sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class TextDelta(val delta: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val summary: String, val target: String) : AgentEvent
    data class ToolFinished(val id: String, val ok: Boolean, val summary: String, val detail: String) : AgentEvent
    data class Failed(val error: AiError) : AgentEvent
    data object TurnFinished : AgentEvent
}

const val DEFAULT_SYSTEM_PROMPT = """You are the coding agent inside Sufyan Harness, an AI development workspace running on an Android phone.

Rules:
- Inspect before you change: use list_files and read_file to understand the project.
- Make small, correct, complete edits. Prefer edit_file for targeted changes and write_file for new files.
- After changing code, verify it when a command tool is available (run tests, build, or a syntax check).
- Never claim something succeeded unless a tool result confirms it.
- Keep replies short and practical. Do not narrate your internal reasoning; state what you did and what the user should do next.
- Paths are always relative to the project root. You cannot access anything outside it."""

class Agent(
    private val provider: AiProvider,
    private val tools: AgentTools,
    private val maxIterations: Int = 8,
) {

    /**
     * Runs one user turn: model -> tool calls -> model, until the model answers
     * without requesting tools or the iteration budget is exhausted.
     * [history] is mutated so the caller can persist the full conversation.
     */
    fun run(
        model: String,
        history: MutableList<ChatMessage>,
        temperature: Float,
    ): Flow<AgentEvent> = flow {
        val schemas = tools.schemas()
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++
            val text = StringBuilder()
            var pendingTools: List<ToolCall> = emptyList()
            var failed = false

            provider.stream(model, history, schemas, temperature).collect { ev ->
                when (ev) {
                    is StreamEvent.Text -> {
                        text.append(ev.delta)
                        emit(AgentEvent.TextDelta(ev.delta))
                    }
                    is StreamEvent.Tools -> pendingTools = ev.calls
                    is StreamEvent.Failed -> {
                        failed = true
                        emit(AgentEvent.Failed(ev.error))
                    }
                    StreamEvent.Done -> Unit
                }
            }

            if (failed) {
                emit(AgentEvent.TurnFinished)
                return@flow
            }

            history += ChatMessage(role = "assistant", content = text.toString(), toolCalls = pendingTools)

            if (pendingTools.isEmpty()) {
                emit(AgentEvent.TurnFinished)
                return@flow
            }

            emit(AgentEvent.Status(statusFor(pendingTools)))

            for (call in pendingTools) {
                emit(AgentEvent.ToolStarted(call.id, call.name, describe(call), targetOf(call)))
                val result = tools.execute(call)
                emit(AgentEvent.ToolFinished(call.id, result.ok, result.summary, result.payload.take(4000)))
                history += ChatMessage(
                    role = "tool",
                    content = result.payload.take(12000),
                    toolCallId = call.id,
                    name = call.name,
                )
            }
        }

        emit(
            AgentEvent.Failed(
                AiError(
                    "Agent stopped",
                    "The agent reached its $maxIterations step limit for one turn. Review the changes so far and send a follow-up instruction.",
                    false,
                ),
            ),
        )
        emit(AgentEvent.TurnFinished)
    }

    private val pathArg = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
    private val commandArg = Regex("\"command\"\\s*:\\s*\"([^\"]+)\"")

    /** The concrete thing a tool call is about — a path or a command. Used for the activity timeline. */
    private fun targetOf(call: ToolCall): String = when (call.name) {
        "run_command" -> commandArg.find(call.argumentsJson)?.groupValues?.get(1).orEmpty()
        else -> pathArg.find(call.argumentsJson)?.groupValues?.get(1).orEmpty()
    }

    private fun describe(call: ToolCall): String {
        val path = pathArg.find(call.argumentsJson)?.groupValues?.get(1)
        val cmd = commandArg.find(call.argumentsJson)?.groupValues?.get(1)
        return when (call.name) {
            "list_files" -> "Inspecting project..."
            "read_file" -> "Reading ${path ?: "file"}..."
            "write_file" -> "Writing ${path ?: "file"}..."
            "edit_file" -> "Editing ${path ?: "file"}..."
            "delete_file" -> "Deleting ${path ?: "file"}..."
            "search" -> "Searching project..."
            "run_command" -> "Running ${cmd?.take(60) ?: "command"}..."
            else -> "Running ${call.name}..."
        }
    }

    private fun statusFor(calls: List<ToolCall>): String {
        val edits = calls.count { it.name in setOf("edit_file", "write_file") }
        return when {
            edits > 1 -> "Editing $edits files..."
            calls.any { it.name == "run_command" } -> "Running commands..."
            calls.all { it.name in setOf("list_files", "read_file", "search") } -> "Inspecting project..."
            else -> "Working..."
        }
    }
}
