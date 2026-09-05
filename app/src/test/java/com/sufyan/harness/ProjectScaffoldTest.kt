package com.sufyan.harness

import com.sufyan.harness.data.ProjectScaffold
import com.sufyan.harness.data.ProjectType
import com.sufyan.harness.data.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §11/§3 — a template must never claim files it does not create. This asserts the single source of
 * truth (ProjectScaffold) writes exactly the files each template declares, and that every creatable
 * project type maps to a template whose files really appear on disk.
 */
class ProjectScaffoldTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `empty template writes no files`() {
        val dir = temp.newFolder("empty")
        ProjectScaffold.write(dir, "demo", Template.Empty)
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertTrue(Template.Empty.declaredFiles.isEmpty())
    }

    @Test
    fun `every template writes every declared file`() {
        for (template in Template.entries) {
            if (template == Template.Empty) continue
            val dir = temp.newFolder("t-${template.id}")
            ProjectScaffold.write(dir, "Scaffold", template)
            // the generated map keys must match the declared contract exactly
            assertEquals(
                "${template.id} declared files do not match its content",
                template.declaredFiles.toSet(),
                ProjectScaffold.files("Scaffold", template).keys,
            )
            for (rel in template.declaredFiles) {
                val f = File(dir, rel)
                assertTrue("${template.id} did not write $rel", f.isFile)
                assertTrue("${template.id} wrote an empty $rel", f.length() > 0)
            }
        }
    }

    @Test
    fun `every creatable project type scaffolds a real template`() {
        for (type in ProjectType.entries.filter { it.canCreate }) {
            val dir = temp.newFolder("p-${type.id}")
            ProjectScaffold.write(dir, type.label, type.template)
            for (rel in type.template.declaredFiles) {
                assertTrue("$type (${type.template.id}) did not scaffold $rel", File(dir, rel).isFile)
            }
        }
    }

    @Test
    fun `scaffold contents are non-empty for file-producing templates`() {
        for (template in Template.entries.filter { it.declaredFiles.isNotEmpty() }) {
            val content = ProjectScaffold.files("Demo", template)
            content.values.forEach { assertTrue("${template.id} has blank content", it.isNotBlank()) }
        }
    }
}
