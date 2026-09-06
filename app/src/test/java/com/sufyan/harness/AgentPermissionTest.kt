package com.sufyan.harness

import com.sufyan.harness.ai.AgentTools
import com.sufyan.harness.ai.ApprovalRequest
import com.sufyan.harness.ai.ToolCall
import com.sufyan.harness.data.AgentPermission
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

/**
 * §48 — the permission gate lives in the tool layer, so no UI path can bypass it. These tests are
 * the contract: a destructive action without approval must not touch the disk.
 */
class AgentPermissionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var files: ProjectFiles
    private val asked = mutableListOf<ApprovalRequest>()

    @Before
    fun setUp() {
        root = temp.newFolder("proj")
        files = ProjectFiles(root)
        asked.clear()
    }

    private fun tools(permission: AgentPermission, allow: Boolean?) = AgentTools(
        files,
        root,
        commandsEnabled = true,
        permission = permission,
        approver = if (allow == null) null else { req -> asked += req; allow },
    )

    private fun call(name: String, args: String) = ToolCall("id", name, args)

    @Test
    fun `creating a new file is not destructive`() = runTest {
        val r = tools(AgentPermission.AskDestructive, allow = false)
            .execute(call("write_file", """{"path":"new.txt","content":"x"}"""))
        assertTrue(r.ok)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `overwriting an existing file asks first`() = runTest {
        files.write("a.txt", "keep")
        val r = tools(AgentPermission.AskDestructive, allow = false)
            .execute(call("write_file", """{"path":"a.txt","content":"gone"}"""))
        assertFalse(r.ok)
        assertEquals("keep", File(root, "a.txt").readText())
        assertEquals(1, asked.size)
        assertTrue(asked.first().destructive)
    }

    @Test
    fun `an approved overwrite really happens`() = runTest {
        files.write("a.txt", "old")
        val r = tools(AgentPermission.AskDestructive, allow = true)
            .execute(call("write_file", """{"path":"a.txt","content":"new"}"""))
        assertTrue(r.ok)
        assertEquals("new", File(root, "a.txt").readText())
    }

    @Test
    fun `delete always asks`() = runTest {
        files.write("a.txt", "x")
        val r = tools(AgentPermission.AskDestructive, allow = false).execute(call("delete_file", """{"path":"a.txt"}"""))
        assertFalse(r.ok)
        assertTrue(File(root, "a.txt").exists())
    }

    @Test
    fun `ask every mode also gates a brand new file`() = runTest {
        val r = tools(AgentPermission.AskEvery, allow = false)
            .execute(call("write_file", """{"path":"new.txt","content":"x"}"""))
        assertFalse(r.ok)
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun `read only tools are never gated`() = runTest {
        files.write("a.txt", "body")
        val t = tools(AgentPermission.AskEvery, allow = false)
        assertTrue(t.execute(call("read_file", """{"path":"a.txt"}""")).ok)
        assertTrue(t.execute(call("list_files", """{"path":""}""")).ok)
        assertTrue(t.execute(call("project_info", "{}")).ok)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `without an approval channel a gated action is refused, not performed`() = runTest {
        files.write("a.txt", "keep")
        val r = tools(AgentPermission.AskDestructive, allow = null)
            .execute(call("write_file", """{"path":"a.txt","content":"gone"}"""))
        assertFalse(r.ok)
        assertTrue(r.summary.contains("Approval"))
        assertEquals("keep", File(root, "a.txt").readText())
    }

    @Test
    fun `auto safe mode does not ask for edits`() = runTest {
        files.write("a.txt", "old")
        val r = tools(AgentPermission.AutoSafe, allow = false)
            .execute(call("write_file", """{"path":"a.txt","content":"new"}"""))
        assertTrue(r.ok)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `changed files are tracked for the review screen`() = runTest {
        val t = tools(AgentPermission.AutoSafe, allow = true)
        t.execute(call("write_file", """{"path":"a.txt","content":"1"}"""))
        t.execute(call("write_file", """{"path":"b.txt","content":"2"}"""))
        t.execute(call("read_file", """{"path":"a.txt"}"""))
        assertEquals(setOf("a.txt", "b.txt"), t.changedFiles.toSet())
    }
}
