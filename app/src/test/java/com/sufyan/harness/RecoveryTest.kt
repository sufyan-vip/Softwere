package com.sufyan.harness

import com.sufyan.harness.runtime.Recovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §56 — an interrupted operation must be visible on the next launch, with real advice. */
class RecoveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var state: File
    private lateinit var recovery: Recovery

    @Before
    fun setUp() {
        state = temp.newFolder("state")
        recovery = Recovery(state)
    }

    @Test
    fun `a clean start reports nothing`() {
        assertTrue(recovery.pending().isEmpty())
    }

    @Test
    fun `an operation that finished leaves no marker`() {
        recovery.begin(Recovery.Operation.Build, "demo (release)")
        assertTrue(recovery.isRunning(Recovery.Operation.Build))
        recovery.end(Recovery.Operation.Build)
        assertFalse(recovery.isRunning(Recovery.Operation.Build))
        assertTrue(recovery.pending().isEmpty())
    }

    @Test
    fun `a killed operation is reported with what it was doing`() {
        recovery.begin(Recovery.Operation.Build, "demo (release)")
        // simulate a fresh process reading the same directory
        val next = Recovery(state)
        val found = next.pending().single()
        assertEquals(Recovery.Operation.Build, found.operation)
        assertEquals("demo (release)", found.detail)
        assertTrue(found.title.contains("Android build"))
        assertTrue(found.message.contains("demo (release)"))
        assertTrue(found.message.isNotBlank())
    }

    @Test
    fun `every operation has advice that tells the user what to do`() {
        for (op in Recovery.Operation.entries) {
            assertTrue("${op.id} has no advice", op.advice.length > 20)
            assertTrue("${op.id} has no label", op.label.isNotBlank())
        }
    }

    @Test
    fun `several interrupted operations are all reported, newest first`() {
        recovery.begin(Recovery.Operation.RuntimeInstall, "alpine")
        Thread.sleep(5)
        recovery.begin(Recovery.Operation.AgentTurn, "fix the build")
        val pending = recovery.pending()
        assertEquals(2, pending.size)
        assertEquals(Recovery.Operation.AgentTurn, pending.first().operation)
    }

    @Test
    fun `track clears the marker even when the work throws`() {
        runCatching {
            recovery.track(Recovery.Operation.Git, "commit") { throw IllegalStateException("boom") }
        }
        assertFalse(recovery.isRunning(Recovery.Operation.Git))
    }

    @Test
    fun `clear removes every marker`() {
        recovery.begin(Recovery.Operation.Build)
        recovery.begin(Recovery.Operation.Install)
        recovery.clear()
        assertTrue(recovery.pending().isEmpty())
    }

    @Test
    fun `sweep deletes scratch state but never a project`() {
        val workspace = temp.newFolder("workspace")
        File(workspace, "my-app").apply { mkdirs(); File(this, "index.html").writeText("<html>") }
        File(workspace, ".harness-tmp").apply { mkdirs(); File(this, "half").writeText("x") }
        val cache = temp.newFolder("cache")
        File(cache, "import-123.zip").writeText("partial")
        File(cache, "keep.txt").writeText("keep")

        val removed = recovery.sweep(workspace, cache)

        assertEquals(2, removed)
        assertTrue(File(workspace, "my-app/index.html").isFile)
        assertFalse(File(workspace, ".harness-tmp").exists())
        assertFalse(File(cache, "import-123.zip").exists())
        assertTrue(File(cache, "keep.txt").isFile)
    }

    @Test
    fun `an unknown marker file is ignored rather than crashing`() {
        recovery.begin(Recovery.Operation.Build)
        File(state, "recovery/mystery.marker").writeText("not an operation")
        assertEquals(1, recovery.pending().size)
    }
}
