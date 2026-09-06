package com.sufyan.harness

import com.sufyan.harness.data.DiffEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §12 — the diff shown in review must describe the real change, not an approximation. */
class DiffEngineTest {

    @Test
    fun `identical content produces no hunks`() {
        val d = DiffEngine.diff("a.txt", "one\ntwo\n", "one\ntwo\n")
        assertFalse(d.changed)
        assertTrue(d.hunks.isEmpty())
        assertEquals(0, d.added)
        assertEquals(0, d.removed)
    }

    @Test
    fun `single line change counts one added and one removed`() {
        val d = DiffEngine.diff("a.txt", "one\ntwo\nthree\n", "one\nTWO\nthree\n")
        assertTrue(d.changed)
        assertEquals(1, d.added)
        assertEquals(1, d.removed)
    }

    @Test
    fun `new file marks every line as added`() {
        val d = DiffEngine.diff("new.txt", null, "a\nb\n")
        assertTrue(d.isNew)
        assertEquals(2, d.added)
        assertEquals(0, d.removed)
    }

    @Test
    fun `deleted file marks every line as removed`() {
        val d = DiffEngine.diff("gone.txt", "a\nb\nc\n", null)
        assertTrue(d.isDeleted)
        assertEquals(3, d.removed)
        assertEquals(0, d.added)
    }

    @Test
    fun `binary content is flagged rather than rendered`() {
        val binary = String(CharArray(10) { '\u0000' })
        val d = DiffEngine.diff("x.bin", "text", binary)
        assertTrue(d.binary)
    }

    @Test
    fun `unified output carries the standard headers`() {
        val d = DiffEngine.diff("a.txt", "one\n", "two\n")
        val text = d.unified()
        assertTrue(text.contains("--- a/a.txt"))
        assertTrue(text.contains("+++ b/a.txt"))
        assertTrue(text.contains("-one"))
        assertTrue(text.contains("+two"))
    }

    @Test
    fun `context lines around a change are limited`() {
        val old = (1..40).joinToString("\n") { "line $it" }
        val new = old.replace("line 20", "LINE 20")
        val d = DiffEngine.diff("big.txt", old, new, context = 2)
        assertEquals(1, d.hunks.size)
        // 2 context above + 1 removed + 1 added + 2 context below
        assertEquals(6, d.hunks.first().lines.size)
    }

    @Test
    fun `summary counts every changed file`() {
        val diffs = listOf(
            DiffEngine.diff("a", "1\n", "2\n"),
            DiffEngine.diff("b", null, "x\n"),
        )
        assertTrue(DiffEngine.summary(diffs).startsWith("2 file(s) changed"))
        assertEquals("No changes.", DiffEngine.summary(emptyList()))
    }

    @Test
    fun `diffAll skips files that did not change`() {
        val diffs = DiffEngine.diffAll(
            listOf(
                Triple("same.txt", "x\n", "x\n"),
                Triple("changed.txt", "x\n", "y\n"),
            ),
        )
        assertEquals(1, diffs.size)
        assertEquals("changed.txt", diffs.first().path)
    }
}
