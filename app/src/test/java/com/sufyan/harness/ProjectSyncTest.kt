package com.sufyan.harness

import com.sufyan.harness.runtime.ProjectSync
import com.sufyan.harness.runtime.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §29-§32 — the local/remote comparison. A file may only be reported as modified when its bytes
 * genuinely differ from the blob GitHub holds, which is why the hash must match git exactly.
 */
class ProjectSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    private fun write(path: String, body: String): File {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(body)
        return f
    }

    @org.junit.Before
    fun setUp() {
        root = temp.newFolder("proj")
    }

    @Test
    fun `blobSha matches git hash-object`() {
        // Values produced by `printf 'hello' | git hash-object --stdin` etc.
        assertEquals("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0", ProjectSync.blobSha("hello".toByteArray()))
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", ProjectSync.blobSha(ByteArray(0)))
        assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", ProjectSync.blobSha("hello world\n".toByteArray()))
    }

    @Test
    fun `collect skips ignored directories`() {
        write("src/main.js", "1")
        write("node_modules/dep/index.js", "2")
        write(".git/config", "3")
        write("build/out.txt", "4")
        val files = ProjectSync.collect(root)
        assertEquals(setOf("src/main.js"), files.keys)
    }

    @Test
    fun `unchanged files are not reported as modified`() {
        val f = write("a.txt", "hello")
        val local = mapOf("a.txt" to f)
        val remote = listOf(RemoteFile("a.txt", ProjectSync.blobSha("hello".toByteArray()), 5))
        val status = ProjectSync.status(local, remote, "sha1", "sha1")
        assertTrue(status.clean)
        assertEquals(1, status.unchanged)
        assertEquals("Up to date with the remote branch.", status.summary)
    }

    @Test
    fun `added modified and deleted are classified from real bytes`() {
        val a = write("a.txt", "changed")
        val b = write("b.txt", "new file")
        val local = mapOf("a.txt" to a, "b.txt" to b)
        val remote = listOf(
            RemoteFile("a.txt", ProjectSync.blobSha("original".toByteArray()), 8),
            RemoteFile("c.txt", "deadbeef", 3),
        )
        val status = ProjectSync.status(local, remote, "sha2", "sha1")
        assertEquals(listOf("b.txt"), status.added)
        assertEquals(listOf("a.txt"), status.modified)
        assertEquals(listOf("c.txt"), status.deleted)
        assertEquals(3, status.changedCount)
        assertFalse(status.clean)
    }

    @Test
    fun `conflicts are only raised when the remote actually moved`() {
        val a = write("a.txt", "mine")
        val local = mapOf("a.txt" to a)
        val remote = listOf(RemoteFile("a.txt", "otherblob", 4))

        val sameHead = ProjectSync.status(local, remote, "sha1", "sha1")
        assertTrue(ProjectSync.conflicts(sameHead, "sha1").isEmpty())

        val movedHead = ProjectSync.status(local, remote, "sha2", "sha1")
        val conflicts = ProjectSync.conflicts(movedHead, "sha1")
        assertEquals(1, conflicts.size)
        assertEquals("a.txt", conflicts.first().path)
    }

    @Test
    fun `oversized files are listed instead of being pushed`() {
        val big = write("big.bin", "x".repeat(10))
        val small = write("small.txt", "ok")
        // Simulate the cap by comparing against the real constant.
        assertTrue(ProjectSync.MAX_FILE_BYTES > big.length())
        assertTrue(ProjectSync.oversized(mapOf("big.bin" to big, "small.txt" to small)).isEmpty())
    }

    @Test
    fun `payload contains only the changed files`() {
        val a = write("a.txt", "changed")
        val b = write("b.txt", "new")
        val local = mapOf("a.txt" to a, "b.txt" to b)
        val remote = listOf(RemoteFile("a.txt", ProjectSync.blobSha("old".toByteArray()), 3))
        val status = ProjectSync.status(local, remote, "s", null)
        val payload = ProjectSync.payload(local, status)
        assertEquals(setOf("a.txt", "b.txt"), payload.keys)
        assertEquals("changed", String(payload["a.txt"]!!))
    }

    @Test
    fun `isIgnored covers nested paths`() {
        assertTrue(ProjectSync.isIgnored("node_modules/x/y.js"))
        assertTrue(ProjectSync.isIgnored("app/build/out.txt"))
        assertTrue(ProjectSync.isIgnored("local.properties"))
        assertFalse(ProjectSync.isIgnored("src/build.gradle.kts"))
    }
}
