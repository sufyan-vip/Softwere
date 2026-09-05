package com.sufyan.harness

import com.sufyan.harness.data.ProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var files: ProjectFiles

    @Before
    fun setUp() {
        root = temp.newFolder("project")
        files = ProjectFiles(root)
    }

    @Test
    fun `write then read round trips`() {
        assertTrue(files.write("src/App.kt", "fun main() {}").isSuccess)
        assertEquals("fun main() {}", files.read("src/App.kt").getOrNull())
    }

    @Test
    fun `path traversal is blocked`() {
        val result = files.write("../escape.txt", "nope")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `search finds matching lines`() {
        files.write("a.txt", "hello world\nsecond line")
        files.write("b.txt", "nothing here")
        val hits = files.search("world")
        assertEquals(1, hits.size)
        assertTrue(hits.first().startsWith("a.txt:1:"))
    }

    @Test
    fun `tree respects expanded directories`() {
        files.createDir("src")
        files.write("src/main.kt", "x")
        assertEquals(1, files.tree(emptySet()).size)
        assertEquals(2, files.tree(setOf("src")).size)
    }

    @Test
    fun `delete removes files`() {
        files.write("gone.txt", "x")
        assertTrue(files.delete("gone.txt").isSuccess)
        assertFalse(File(root, "gone.txt").exists())
    }

    @Test
    fun `binary files are rejected by the editor`() {
        File(root, "image.png").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(files.read("image.png").isFailure)
    }
}
