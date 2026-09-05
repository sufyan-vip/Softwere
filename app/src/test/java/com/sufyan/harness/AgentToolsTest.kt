package com.sufyan.harness

import com.sufyan.harness.ai.AgentTools
import com.sufyan.harness.ai.ToolCall
import com.sufyan.harness.data.ProjectFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var tools: AgentTools
    private lateinit var files: ProjectFiles

    @Before
    fun setUp() {
        root = temp.newFolder("proj")
        files = ProjectFiles(root)
        tools = AgentTools(files, root, commandsEnabled = false)
    }

    private fun call(name: String, args: String) = ToolCall("id", name, args)

    @Test
    fun `schemas exclude run_command when disabled`() {
        assertFalse(tools.schemas().any { it.name == "run_command" })
        assertTrue(AgentTools(files, root, true).schemas().any { it.name == "run_command" })
    }

    @Test
    fun `write_file creates real file`() = runTest {
        val r = tools.execute(call("write_file", """{"path":"a.txt","content":"hi"}"""))
        assertTrue(r.ok)
        assertEquals("hi", File(root, "a.txt").readText())
    }

    @Test
    fun `edit_file replaces unique snippet`() = runTest {
        files.write("a.txt", "alpha beta gamma")
        val r = tools.execute(call("edit_file", """{"path":"a.txt","old_text":"beta","new_text":"BETA"}"""))
        assertTrue(r.ok)
        assertEquals("alpha BETA gamma", File(root, "a.txt").readText())
    }

    @Test
    fun `edit_file rejects ambiguous snippet`() = runTest {
        files.write("a.txt", "x x")
        val r = tools.execute(call("edit_file", """{"path":"a.txt","old_text":"x","new_text":"y"}"""))
        assertFalse(r.ok)
        assertTrue(r.summary.contains("ambiguous"))
    }

    @Test
    fun `edit_file reports missing snippet instead of succeeding`() = runTest {
        files.write("a.txt", "content")
        val r = tools.execute(call("edit_file", """{"path":"a.txt","old_text":"absent","new_text":"y"}"""))
        assertFalse(r.ok)
    }

    @Test
    fun `sandbox escape is blocked`() = runTest {
        val r = tools.execute(call("write_file", """{"path":"../../evil.txt","content":"x"}"""))
        assertFalse(r.ok)
    }

    @Test
    fun `run_command is refused when disabled`() = runTest {
        val r = tools.execute(call("run_command", """{"command":"echo hi"}"""))
        assertFalse(r.ok)
    }

    @Test
    fun `invalid json arguments fail cleanly`() = runTest {
        val r = tools.execute(call("read_file", "not json"))
        assertFalse(r.ok)
    }

    @Test
    fun `list_files reports contents`() = runTest {
        files.write("one.txt", "1")
        val r = tools.execute(call("list_files", """{"path":""}"""))
        assertTrue(r.ok)
        assertTrue(r.payload.contains("one.txt"))
    }
}
