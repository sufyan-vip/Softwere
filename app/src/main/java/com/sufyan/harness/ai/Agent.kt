package com.sufyan.harness.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** UI-facing agent events. Chain-of-thought is never surfaced — only summaries. */
sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class TextDelta(val delta: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val summary: String, val target: String) : AgentEvent
    data class ToolFinished(val id: String, val ok: Boolean, val summary: String, val detail: String) : AgentEvent
    data class Usage(val usage: com.sufyan.harness.ai.Usage) : AgentEvent
    data class Failed(val error: AiError) : AgentEvent
    /** §20/§47 — the result of a real verification command run after the model finished editing. */
    data class Verified(val command: String, val ok: Boolean, val attempt: Int, val output: String) : AgentEvent
    data object TurnFinished : AgentEvent
}

/** §47 — how the agent proves its work. [run] executes a real command and reports the real result. */
data class Verification(
    val command: String,
    val evidence: String,
    val maxAttempts: Int,
    val run: suspend () -> VerificationResult,
)

data class VerificationResult(val ok: Boolean, val exitCode: Int, val output: String)

const val DEFAULT_SYSTEM_PROMPT = """You are the coding agent inside Sufyan Harness, an AI development workspace running on an Android phone.

Rules:
- Call project_info first when you do not already know the project type and its real commands.
- Inspect before you change: use list_files and read_file to understand the project.
- Make small, correct, complete edits. Prefer edit_file for targeted changes and write_file for new files.
- Only run a command that project_info reported, or that you can see in the project's own files. Never invent one.
- After changing code, verify it when a command tool is available (run tests, build, or a syntax check).
- Never claim something succeeded unless a tool result confirms it. If a tool fails, read the diagnosis and fix the cause.
- If the user declines an action, stop and propose an alternative instead of retrying it.
- You have no access to GitHub credentials or the user's API key, and no tool that can push code. Ask the user to use the GitHub screen for that.
- Keep replies short and practical. Do not narrate your internal reasoning; state what you did and what the user should do next.
- Paths are always relative to the project root. You cannot access anything outside it."""

/**
 * The agent loop.
 *
 * §19 — the history sent to the provider is pruned to a token budget by [AgentContext], which keeps
 * whole turns so tool calls are never orphaned.
 * §20/§47 — when the model stops editing, an optional [verification] command really runs; if it
 * fails, its actual output is fed back and the model gets another attempt, bounded by
 * [Verification.maxAttempts] so the loop cannot run away.
 */
class Agent(
    private val provider: AiProvider,
    private val tools: AgentTools,
    private val maxIterations: Int = 12,
    private val contextBudget: Int = AgentContext.DEFAULT_TOKEN_BUDGET,
    private val verification: Verification? = null,
    /** Second model tried once if the first is unavailable (§5 fallbacks). Blank disables it. */
    private val fallbackModel: String = "",
) {

    /**
     * Runs one user turn: model -> tool calls -> model, until the model answers without requesting
     * tools and any verification passes, or a budget is exhausted.
     * [history] is mutated so the caller can persist the full conversation.
     */
    fun run(
        model: String,
        history: MutableList<ChatMessage>,
        temperature: Float,
    ): Flow<AgentEvent> = flow {
        val schemas = tools.schemas()
        var iteration = 0
        var fixAttempts = 0
        var usedFallback = false
        var activeModel = model

        while (iteration < maxIterations) {
            iteration++
            val text = StringBuilder()
            var pendingTools: List<ToolCall> = emptyList()
            var failure: AiError? = null

            val pruned = AgentContext.prune(history, contextBudget)
            if (pruned.pruned && iteration == 1) {
                emit(AgentEvent.Status("Trimmed ${pruned.droppedMessages} old messages to fit the context window"))
            }

            provider.stream(activeModel, pruned.messages, schemas, temperature).collect { ev ->
                when (ev) {
                    is StreamEvent.Text -> {
                        text.append(ev.delta)
                        emit(AgentEvent.TextDelta(ev.delta))
                    }
                    is StreamEvent.Tools -> pendingTools = ev.calls
                    is StreamEvent.Usage -> emit(AgentEvent.Usage(ev.usage))
                    is StreamEvent.Failed -> failure = ev.error
                    StreamEvent.Done -> Unit
                }
            }

            val error = failure
            if (error != null) {
                // §5 — one automatic attempt with the configured fallback model when the primary
                // one is unavailable. Never silent: the switch is announced.
                val canFallback = !usedFallback && fallbackModel.isNotBlank() && fallbackModel != activeModel &&
                    (error.title.contains("model", true) || error.title.contains("not found", true) ||
                        error.title.contains("unavailable", true) || error.title.contains("rate", true))
                if (canFallback) {
                    usedFallback = true
                    activeModel = fallbackModel
                    emit(AgentEvent.Status("$model failed (${error.title}) — retrying with $fallbackModel"))
                    continue
                }
                emit(AgentEvent.Failed(error))
                emit(AgentEvent.TurnFinished)
                return@flow
            }

            history += ChatMessage(role = "assistant", content = text.toString(), toolCalls = pendingTools)

            if (pendingTools.isNotEmpty()) {
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
                continue
            }

            // The model answered without asking for tools: the turn is done unless verification is
            // configured and the agent actually changed something.
            val verify = verification
            if (verify != null && tools.changedFiles.isNotEmpty() && fixAttempts < verify.maxAttempts) {
                fixAttempts++
                emit(AgentEvent.Status("Verifying with ${verify.command} (attempt $fixAttempts/${verify.maxAttempts})"))
                val verifyId = "verify-$fixAttempts"
                emit(AgentEvent.ToolStarted(verifyId, "verify", "Running ${verify.command}", verify.command))
                val result = verify.run()
                emit(
                    AgentEvent.ToolFinished(
                        verifyId,
                        result.ok,
                        if (result.ok) "Verified: ${verify.command}" else "Verification failed (exit ${result.exitCode})",
                        result.output.take(4000),
                    ),
                )
                emit(AgentEvent.Verified(verify.command, result.ok, fixAttempts, result.output.take(4000)))
                if (result.ok) {
                    emit(AgentEvent.TurnFinished)
                    return@flow
                }
                history += ChatMessage(
                    role = "user",
                    content = "The verification command `${verify.command}` failed with exit code " +
                        "${result.exitCode}. This is its real output:\n\n${result.output.take(6000)}\n\n" +
                        "Fix the cause and then stop. Do not repeat a change that already failed.",
                )
                continue
            }

            emit(AgentEvent.TurnFinished)
            return@flow
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
            "project_info" -> "Checking project type..."
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
            calls.all { it.name in setOf("list_files", "read_file", "search", "project_info") } -> "Inspecting project..."
            else -> "Working..."
        }
    }
}
