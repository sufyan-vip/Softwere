package com.sufyan.harness

import com.sufyan.harness.runtime.CheckpointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckpointTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `create and restore round trips without corrupting the project`() {
        val project = temp.newFolder("app")
        File(project, "main.js").writeText("original")
        File(project, "src").mkdirs()
        File(project, "src/util.js").writeText("util v1")

        val store = CheckpointStore(project, temp.newFolder("store"))
        val cp = store.create("before AI").getOrThrow()
        assertEquals(1, store.list().size)

        File(project, "main.js").writeText("ruined by the agent")
        File(project, "src/util.js").delete()

        assertTrue(store.restore(cp).isSuccess)
        assertEquals("original", File(project, "main.js").readText())
        assertEquals("util v1", File(project, "src/util.js").readText())
        // the internal marker must not leak into the restored project
        assertTrue(!File(project, ".checkpoint").exists())
    }

    @Test
    fun `delete removes checkpoint data`() {
        val project = temp.newFolder("app2")
        File(project, "a.txt").writeText("a")
        val store = CheckpointStore(project, temp.newFolder("store2"))
        val cp = store.create("snap").getOrThrow()
        assertTrue(store.delete(cp).isSuccess)
        assertEquals(0, store.list().size)
    }
}
