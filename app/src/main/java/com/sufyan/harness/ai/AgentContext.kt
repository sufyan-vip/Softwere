package com.sufyan.harness.ai

/**
 * §19 — agent context management.
 *
 * The whole conversation cannot be replayed forever: every turn would grow the request until the
 * model's context window rejects it (or the bill explodes). This prunes the history that is sent to
 * the provider while keeping it *valid*:
 *
 *  * the system prompt is always kept,
 *  * a turn is kept or dropped as a whole, so an `assistant` message carrying `tool_calls` is never
 *    separated from the `tool` results that answer it (providers reject that),
 *  * dropped turns are replaced by one honest note stating how many were omitted — never by an
 *    invented summary of what they said,
 *  * oversized tool payloads are clipped with a visible marker rather than silently truncated.
 *
 * Everything here is pure Kotlin so it is unit-testable off-device.
 */
object AgentContext {

    /** Rough characters-per-token ratio. Only used for budgeting, never reported as real usage. */
    const val CHARS_PER_TOKEN = 4

    /** Default budget for replayed history, in tokens. */
    const val DEFAULT_TOKEN_BUDGET = 24_000

    /** Longest tool payload replayed back to the model. */
    const val MAX_TOOL_CHARS = 6_000

    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun estimateTokens(message: ChatMessage): Int {
        val argChars = message.toolCalls.sumOf { it.argumentsJson.length + it.name.length }
        // +8 tokens of per-message envelope (role, delimiters) as OpenAI-style APIs charge for it.
        return estimateTokens(message.content) + estimateTokens("x".repeat(argChars)) + 8
    }

    fun estimateTokens(messages: List<ChatMessage>): Int = messages.sumOf { estimateTokens(it) }

    data class Pruned(
        val messages: List<ChatMessage>,
        val droppedMessages: Int,
        val droppedTurns: Int,
        val estimatedTokens: Int,
    ) {
        val pruned: Boolean get() = droppedMessages > 0
    }

    /**
     * Splits [history] into turns. A turn starts at a `user` message and contains every assistant /
     * tool message that follows it. Leading `system` messages form their own prefix.
     */
    private fun split(history: List<ChatMessage>): Pair<List<ChatMessage>, List<List<ChatMessage>>> {
        val system = history.takeWhile { it.role == "system" }
        val rest = history.drop(system.size)
        val turns = mutableListOf<MutableList<ChatMessage>>()
        for (m in rest) {
            if (m.role == "user" || turns.isEmpty()) turns += mutableListOf(m) else turns.last() += m
        }
        return system to turns
    }

    /**
     * Returns the message list to send. [budgetTokens] counts the whole request; the most recent turn
     * is always kept even when it alone exceeds the budget (the provider, not this code, decides
     * whether it fits — we never silently drop what the user just asked).
     */
    fun prune(
        history: List<ChatMessage>,
        budgetTokens: Int = DEFAULT_TOKEN_BUDGET,
        maxToolChars: Int = MAX_TOOL_CHARS,
    ): Pruned {
        val clipped = history.map { clip(it, maxToolChars) }
        val (system, turns) = split(clipped)
        if (turns.isEmpty()) {
            return Pruned(system, 0, 0, estimateTokens(system))
        }

        val systemTokens = estimateTokens(system)
        val kept = ArrayDeque<List<ChatMessage>>()
        var total = systemTokens

        for (i in turns.indices.reversed()) {
            val turn = turns[i]
            val cost = estimateTokens(turn)
            val isLast = i == turns.lastIndex
            if (!isLast && total + cost > budgetTokens) break
            kept.addFirst(turn)
            total += cost
        }

        val droppedTurns = turns.size - kept.size
        val droppedMessages = turns.take(droppedTurns).sumOf { it.size }

        val out = mutableListOf<ChatMessage>()
        out += system
        if (droppedTurns > 0) {
            out += ChatMessage(
                role = "system",
                content = "Context note: $droppedMessages earlier message(s) from $droppedTurns earlier " +
                    "exchange(s) in this conversation were omitted to stay inside the context window. " +
                    "They are still shown to the user in the app. If you need something from earlier, " +
                    "re-read the files instead of guessing.",
            )
        }
        kept.forEach { out += it }
        return Pruned(out, droppedMessages, droppedTurns, estimateTokens(out))
    }

    /** Clips a tool payload, saying so in the text so the model knows the output is incomplete. */
    private fun clip(message: ChatMessage, maxToolChars: Int): ChatMessage {
        if (message.role != "tool" || message.content.length <= maxToolChars) return message
        val head = message.content.take(maxToolChars * 2 / 3)
        val tail = message.content.takeLast(maxToolChars / 3)
        val omitted = message.content.length - head.length - tail.length
        return message.copy(
            content = head + "\n\n... [$omitted characters omitted from this tool output] ...\n\n" + tail,
        )
    }
}
