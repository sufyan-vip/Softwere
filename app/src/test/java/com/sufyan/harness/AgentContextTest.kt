package com.sufyan.harness

import com.sufyan.harness.ai.AgentContext
import com.sufyan.harness.ai.ChatMessage
import com.sufyan.harness.ai.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §19 — pruning must keep the conversation *valid*: a tool result can never be sent without the
 * assistant message that requested it, and the newest turn is never dropped.
 */
class AgentContextTest {

    private fun user(text: String) = ChatMessage("user", text)
    private fun assistant(text: String, calls: List<ToolCall> = emptyList()) = ChatMessage("assistant", text, calls)
    private fun tool(id: String, text: String) = ChatMessage("tool", text, toolCallId = id, name = "read_file")

    private fun turn(index: Int, size: Int): List<ChatMessage> {
        val call = ToolCall("c$index", "read_file", """{"path":"f$index"}""")
        return listOf(
            user("question $index ".repeat(size)),
            assistant("", listOf(call)),
            tool("c$index", "file body ".repeat(size)),
            assistant("answer $index"),
        )
    }

    @Test
    fun `short history is returned untouched`() {
        val history = listOf(ChatMessage("system", "sys")) + turn(1, 2)
        val pruned = AgentContext.prune(history, budgetTokens = 10_000)
        assertFalse(pruned.pruned)
        assertEquals(history.size, pruned.messages.size)
    }

    @Test
    fun `the system prompt always survives`() {
        val history = mutableListOf(ChatMessage("system", "sys"))
        repeat(20) { history += turn(it, 200) }
        val pruned = AgentContext.prune(history, budgetTokens = 2_000)
        assertEquals("system", pruned.messages.first().role)
        assertEquals("sys", pruned.messages.first().content)
    }

    @Test
    fun `the newest turn is kept even when it alone exceeds the budget`() {
        val history = mutableListOf(ChatMessage("system", "sys"))
        history += turn(1, 5)
        history += turn(2, 5_000)
        val pruned = AgentContext.prune(history, budgetTokens = 100)
        assertTrue(pruned.messages.any { it.content.startsWith("question 2") })
    }

    @Test
    fun `tool results are never orphaned`() {
        val history = mutableListOf(ChatMessage("system", "sys"))
        repeat(12) { history += turn(it, 300) }
        val pruned = AgentContext.prune(history, budgetTokens = 3_000)
        val ids = pruned.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id }.toSet()
        val answered = pruned.messages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }
        assertTrue("every tool message must have its request", answered.all { it in ids })
    }

    @Test
    fun `dropped turns are announced honestly and not summarised`() {
        val history = mutableListOf(ChatMessage("system", "sys"))
        repeat(10) { history += turn(it, 400) }
        val pruned = AgentContext.prune(history, budgetTokens = 2_000)
        assertTrue(pruned.pruned)
        val note = pruned.messages.first { it.content.startsWith("Context note") }
        assertTrue(note.content.contains("${pruned.droppedMessages} earlier message"))
        assertTrue(note.content.contains("omitted"))
    }

    @Test
    fun `huge tool output is clipped with a visible marker`() {
        val history = listOf(
            ChatMessage("system", "sys"),
            user("go"),
            assistant("", listOf(ToolCall("c", "run_command", "{}"))),
            ChatMessage("tool", "x".repeat(50_000), toolCallId = "c", name = "run_command"),
        )
        val pruned = AgentContext.prune(history, budgetTokens = 100_000, maxToolChars = 1_000)
        val toolMsg = pruned.messages.first { it.role == "tool" }
        assertTrue(toolMsg.content.length < 2_000)
        assertTrue(toolMsg.content.contains("characters omitted"))
    }

    @Test
    fun `token estimate grows with content`() {
        val small = AgentContext.estimateTokens(listOf(user("hi")))
        val big = AgentContext.estimateTokens(listOf(user("hi".repeat(1_000))))
        assertTrue(big > small)
    }

    @Test
    fun `empty history yields an empty plan`() {
        val pruned = AgentContext.prune(emptyList())
        assertTrue(pruned.messages.isEmpty())
        assertFalse(pruned.pruned)
    }
}
