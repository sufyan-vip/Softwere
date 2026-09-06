package com.sufyan.harness

import com.sufyan.harness.ai.Agent
import com.sufyan.harness.ai.AgentEvent
import com.sufyan.harness.ai.AgentTools
import com.sufyan.harness.ai.AiError
import com.sufyan.harness.ai.AiProvider
import com.sufyan.harness.ai.ChatMessage
import com.sufyan.harness.ai.ModelInfo
import com.sufyan.harness.ai.StreamEvent
import com.sufyan.harness.ai.ToolCall
import com.sufyan.harness.ai.ToolSchema
import com.sufyan.harness.ai.Verification
import com.sufyan.harness.ai.VerificationResult
import com.sufyan.harness.data.ProjectFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §20 / §47 — the agent must prove its work with a real command and must not claim success when
 * that command failed. The fake provider below replays scripted turns so the loop itself is tested.
 */
class AgentLoopTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var files: ProjectFiles

    @Before
    fun setUp() {
        root = temp.newFolder("proj")
        files = ProjectFiles(root)
    }

    /** Replays one scripted response per call, recording the model each turn used. */
    private class FakeProvider(private val turns: List<List<StreamEvent>>) : AiProvider {
        override val displayName = "Fake"
        val modelsUsed = mutableListOf<String>()
        var calls = 0
        override suspend fun listModels(force: Boolean): Result<List<ModelInfo>> = Result.success(emptyList())
        override fun stream(
            model: String,
            messages: List<ChatMessage>,
            tools: List<ToolSchema>,
            temperature: Float,
        ): Flow<StreamEvent> = flow {
            modelsUsed += model
            val script = turns.getOrElse(calls) { listOf(StreamEvent.Text("done"), StreamEvent.Done) }
            calls++
            script.forEach { emit(it) }
        }
    }

    private fun writeCall(path: String, body: String) =
        StreamEvent.Tools(listOf(ToolCall("t${path.hashCode()}", "write_file", """{"path":"$path","content":"$body"}""")))

    @Test
    fun `a plain answer finishes the turn without verification`() = runTest {
        val provider = FakeProvider(listOf(listOf(StreamEvent.Text("Hello"), StreamEvent.Done)))
        val agent = Agent(provider, AgentTools(files, root, commandsEnabled = false))
        val events = agent.run("m", mutableListOf(ChatMessage("user", "hi")), 0.2f).toList()
        assertTrue(events.any { it is AgentEvent.TextDelta })
        assertTrue(events.last() is AgentEvent.TurnFinished)
        assertFalse(events.any { it is AgentEvent.Verified })
    }

    @Test
    fun `tool calls are executed and reported`() = runTest {
        val provider = FakeProvider(
            listOf(
                listOf(writeCall("a.txt", "hi"), StreamEvent.Done),
                listOf(StreamEvent.Text("Created a.txt"), StreamEvent.Done),
            ),
        )
        val tools = AgentTools(files, root, commandsEnabled = false)
        val events = Agent(provider, tools).run("m", mutableListOf(ChatMessage("user", "go")), 0.2f).toList()
        assertTrue(events.any { it is AgentEvent.ToolStarted && it.name == "write_file" })
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.ok })
        assertEquals("hi", File(root, "a.txt").readText())
    }

    @Test
    fun `verification runs only after files really changed`() = runTest {
        val provider = FakeProvider(listOf(listOf(StreamEvent.Text("Nothing to do"), StreamEvent.Done)))
        var ran = false
        val verification = Verification("npm test", "package.json", 2) {
            ran = true
            VerificationResult(true, 0, "")
        }
        Agent(provider, AgentTools(files, root, commandsEnabled = false), verification = verification)
            .run("m", mutableListOf(ChatMessage("user", "hi")), 0.2f).toList()
        assertFalse("verification must not run when nothing changed", ran)
    }

    @Test
    fun `a failing verification feeds the real output back and retries`() = runTest {
        val provider = FakeProvider(
            listOf(
                listOf(writeCall("a.txt", "bad"), StreamEvent.Done),
                listOf(StreamEvent.Text("wrote it"), StreamEvent.Done),
                listOf(writeCall("a.txt", "good"), StreamEvent.Done),
                listOf(StreamEvent.Text("fixed it"), StreamEvent.Done),
            ),
        )
        var attempt = 0
        val verification = Verification("npm test", "package.json", 3) {
            attempt++
            if (attempt == 1) VerificationResult(false, 1, "FAIL: expected 2 got 1") else VerificationResult(true, 0, "PASS")
        }
        val history = mutableListOf(ChatMessage("user", "make it pass"))
        val events = Agent(provider, AgentTools(files, root, commandsEnabled = false), verification = verification)
            .run("m", history, 0.2f).toList()

        val verified = events.filterIsInstance<AgentEvent.Verified>()
        assertEquals(2, verified.size)
        assertFalse(verified.first().ok)
        assertTrue(verified.last().ok)
        assertTrue(history.any { it.role == "user" && it.content.contains("FAIL: expected 2 got 1") })
    }

    @Test
    fun `verification is bounded by maxAttempts`() = runTest {
        val provider = FakeProvider(
            (1..10).map { listOf(writeCall("a$it.txt", "x"), StreamEvent.Done) },
        )
        var attempts = 0
        val verification = Verification("npm test", "package.json", 2) {
            attempts++
            VerificationResult(false, 1, "still failing")
        }
        Agent(provider, AgentTools(files, root, commandsEnabled = false), maxIterations = 8, verification = verification)
            .run("m", mutableListOf(ChatMessage("user", "go")), 0.2f).toList()
        assertTrue("verification ran $attempts times", attempts <= 2)
    }

    @Test
    fun `an unavailable model falls back once and says so`() = runTest {
        val provider = FakeProvider(
            listOf(
                listOf(StreamEvent.Failed(AiError("Model unavailable", "gone", true)), StreamEvent.Done),
                listOf(StreamEvent.Text("hello from the backup"), StreamEvent.Done),
            ),
        )
        val events = Agent(provider, AgentTools(files, root, commandsEnabled = false), fallbackModel = "backup/model")
            .run("primary/model", mutableListOf(ChatMessage("user", "hi")), 0.2f).toList()
        assertTrue(events.any { it is AgentEvent.Status && it.text.contains("backup/model") })
        assertEquals(listOf("primary/model", "backup/model"), provider.modelsUsed)
        assertFalse(events.any { it is AgentEvent.Failed })
    }

    @Test
    fun `a hard failure is surfaced instead of being swallowed`() = runTest {
        val provider = FakeProvider(
            listOf(listOf(StreamEvent.Failed(AiError("Invalid API key", "401", false)), StreamEvent.Done)),
        )
        val events = Agent(provider, AgentTools(files, root, commandsEnabled = false))
            .run("m", mutableListOf(ChatMessage("user", "hi")), 0.2f).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals("Invalid API key", failed.error.title)
    }

    @Test
    fun `the step limit ends the turn with a step-limit event, not an error`() = runTest {
        val provider = FakeProvider((1..20).map { listOf(writeCall("f$it.txt", "x"), StreamEvent.Done) })
        val events = Agent(provider, AgentTools(files, root, commandsEnabled = false), maxIterations = 3)
            .run("m", mutableListOf(ChatMessage("user", "loop")), 0.2f).toList()
        // The turn stops, but it is reported as a pause the caller can resume, not a failure.
        assertEquals(3, events.filterIsInstance<AgentEvent.StepLimit>().single().steps)
        assertFalse(events.any { it is AgentEvent.Failed })
        assertTrue(events.last() is AgentEvent.TurnFinished)
    }

    @Test
    fun `running out of steps is a pause, not a failure`() = runTest {
        // A model that keeps asking for tools forever: the loop must stop, but honestly.
        val provider = object : AiProvider {
            override val displayName = "Loop"
            override suspend fun listModels(force: Boolean): Result<List<ModelInfo>> = Result.success(emptyList())
            override fun stream(
                model: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchema>,
                temperature: Float,
            ): Flow<StreamEvent> = flow {
                emit(writeCall("loop${messages.size}.txt", "x"))
                emit(StreamEvent.Done)
            }
        }
        val agent = Agent(provider, AgentTools(files, root, commandsEnabled = false), maxIterations = 3)
        val events = agent.run("m", mutableListOf(ChatMessage("user", "go")), 0.2f).toList()

        val limit = events.filterIsInstance<AgentEvent.StepLimit>().singleOrNull()
        assertTrue("the turn must report the step limit", limit != null)
        assertEquals(3, limit!!.steps)
        assertFalse("a step limit is not an error", events.any { it is AgentEvent.Failed })
        assertTrue(events.last() is AgentEvent.TurnFinished)
        // The work it managed to do is real and still on disk.
        assertTrue(root.listFiles()!!.any { it.name.startsWith("loop") })
    }

    @Test
    fun `a bigger step budget really allows more steps`() = runTest {
        var calls = 0
        val provider = object : AiProvider {
            override val displayName = "Loop"
            override suspend fun listModels(force: Boolean): Result<List<ModelInfo>> = Result.success(emptyList())
            override fun stream(
                model: String,
                messages: List<ChatMessage>,
                tools: List<ToolSchema>,
                temperature: Float,
            ): Flow<StreamEvent> = flow {
                calls++
                emit(writeCall("step$calls.txt", "x"))
                emit(StreamEvent.Done)
            }
        }
        Agent(provider, AgentTools(files, root, commandsEnabled = false), maxIterations = 7)
            .run("m", mutableListOf(ChatMessage("user", "go")), 0.2f).toList()
        assertEquals(7, calls)
    }
}
