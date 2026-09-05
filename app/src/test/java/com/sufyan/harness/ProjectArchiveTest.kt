package com.sufyan.harness

import com.sufyan.harness.data.ProjectArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun makeProject(): File {
        val dir = temp.newFolder("proj")
        File(dir, "index.html").writeText("<h1>hi</h1>")
        File(dir, "src").mkdirs()
        File(dir, "src/app.js").writeText("console.log('hi')")
        return dir
    }

    @Test
    fun `export then import round trips all real files`() {
        val src = makeProject()
        val zipFile = File(temp.newFolder("out"), "project.zip")
        assertTrue(ProjectArchive.exportZip(src, zipFile).isSuccess)
        assertTrue(zipFile.isFile && zipFile.length() > 0)

        val dest = temp.newFolder("restore")
        assertTrue(ProjectArchive.importZip(dest, zipFile).isSuccess)
        assertEquals("<h1>hi</h1>", File(dest, "index.html").readText())
        assertEquals("console.log('hi')", File(dest, "src/app.js").readText())
    }

    @Test
    fun `zip-slip entry is refused`() {
        val zipFile = File(temp.newFolder("evil"), "evil.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt"))
            zip.write("nope".toByteArray())
            zip.closeEntry()
        }
        val dest = temp.newFolder("safe")
        val result = ProjectArchive.importZip(dest, zipFile)
        assertTrue(result.isFailure)
        assertFalse(File(temp.root, "escape.txt").exists())
    }

    @Test
    fun `import strips a single top-level folder`() {
        // Simulate an archive that wraps everything in "project-foo/".
        val outer = temp.newFolder("wrapped")
        File(outer, "project-foo").mkdirs()
        File(outer, "project-foo/readme.md").writeText("hello")
        val zipFile = File(temp.newFolder("zw"), "w.zip")
        assertTrue(ProjectArchive.exportZip(outer, zipFile).isSuccess)

        val dest = temp.newFolder("flat")
        assertTrue(ProjectArchive.importZip(dest, zipFile).isSuccess)
        assertEquals("hello", File(dest, "readme.md").readText())
    }

    @Test
    fun `importFolder copies files recursively`() {
        val src = temp.newFolder("folder")
        File(src, "a.txt").writeText("a")
        File(src, "sub").mkdirs()
        File(src, "sub/b.txt").writeText("b")

        val dest = temp.newFolder("copy")
        assertTrue(ProjectArchive.importFolder(dest, src).isSuccess)
        assertEquals("a", File(dest, "a.txt").readText())
        assertEquals("b", File(dest, "sub/b.txt").readText())
    }
}
