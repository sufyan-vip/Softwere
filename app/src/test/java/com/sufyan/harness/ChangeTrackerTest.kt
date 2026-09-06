package com.sufyan.harness

import com.sufyan.harness.runtime.ChangeTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §12 — reverting an AI change must restore the exact bytes that were there before, with no git
 * binary involved.
 */
class ChangeTrackerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var tracker: ChangeTracker

    private fun write(path: String, body: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    @Before
    fun setUp() {
        root = temp.newFolder("proj")
        tracker = ChangeTracker(root)
    }

    @Test
    fun `no snapshot means nothing to review`() {
        write("a.txt", "one")
        assertFalse(tracker.hasSnapshot())
        assertTrue(tracker.review().isEmpty())
    }

    @Test
    fun `an untouched project reports no changes`() {
        write("a.txt", "one")
        tracker.capture()
        assertTrue(tracker.review().isEmpty())
    }

    @Test
    fun `a modified file is reported with a diff`() {
        write("a.txt", "one\ntwo\n")
        tracker.capture()
        write("a.txt", "one\nTWO\n")
        val changes = tracker.review()
        assertEquals(1, changes.size)
        assertEquals("a.txt", changes.first().path)
        assertEquals("+1 / -1", changes.first().stat)
    }

    @Test
    fun `a new file is marked as new and reverting deletes it`() {
        tracker.capture()
        write("new.txt", "hello")
        val change = tracker.review().single()
        assertTrue(change.isNew)
        assertTrue(tracker.revert(change).isSuccess)
        assertFalse(File(root, "new.txt").exists())
    }

    @Test
    fun `reverting restores the exact previous content`() {
        write("a.txt", "original content\n")
        tracker.capture()
        write("a.txt", "destroyed")
        val change = tracker.review().single()
        assertTrue(tracker.revert(change).isSuccess)
        assertEquals("original content\n", File(root, "a.txt").readText())
    }

    @Test
    fun `a deleted file is restored by revert`() {
        write("a.txt", "keep me")
        tracker.capture()
        File(root, "a.txt").delete()
        val change = tracker.review().single()
        assertTrue(change.isDeleted)
        assertTrue(tracker.revert(change).isSuccess)
        assertEquals("keep me", File(root, "a.txt").readText())
    }

    @Test
    fun `revertAll reports how many files it restored`() {
        write("a.txt", "a")
        write("b.txt", "b")
        tracker.capture()
        write("a.txt", "A")
        write("b.txt", "B")
        write("c.txt", "C")
        val changes = tracker.review()
        assertEquals(3, changes.size)
        assertEquals(3, tracker.revertAll(changes).getOrThrow())
        assertEquals("a", File(root, "a.txt").readText())
        assertFalse(File(root, "c.txt").exists())
    }

    @Test
    fun `accepting resets the baseline`() {
        write("a.txt", "one")
        tracker.capture()
        write("a.txt", "two")
        assertEquals(1, tracker.review().size)
        tracker.accept()
        assertTrue(tracker.review().isEmpty())
    }

    @Test
    fun `build output and dependencies are ignored`() {
        write("node_modules/dep.js", "x")
        write("build/out.txt", "y")
        tracker.capture()
        write("node_modules/dep.js", "changed")
        write("build/out.txt", "changed")
        assertTrue(tracker.review().isEmpty())
    }
}
