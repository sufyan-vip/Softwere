package com.sufyan.harness

import com.sufyan.harness.runtime.CloudBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** §34-§39 — the parts of the cloud build that can be proven without a network. */
class CloudBuildTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zipWith(vararg entries: Pair<String, ByteArray>): File {
        val zip = File(temp.root, "artifact-${entries.hashCode()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    // ---- the workflow the app writes into the user's repository ------------

    @Test
    fun `the workflow can be started from the phone and takes a variant`() {
        val yaml = CloudBuild.workflowYaml()
        assertTrue(yaml.contains("workflow_dispatch"))
        assertTrue(yaml.contains("variant"))
        assertTrue(yaml.contains("options: [debug, release]"))
    }

    @Test
    fun `the workflow really builds and uploads an APK`() {
        val yaml = CloudBuild.workflowYaml()
        assertTrue(yaml.contains("setup-java"))
        assertTrue(yaml.contains("setup-android"))
        assertTrue(yaml.contains("./gradlew assemble"))
        assertTrue(yaml.contains("upload-artifact"))
        assertTrue(yaml.contains("if-no-files-found: error"))
        // It must verify what it uploads, not just trust Gradle.
        assertTrue(yaml.contains("AndroidManifest.xml"))
        assertTrue(yaml.contains("classes.dex"))
    }

    @Test
    fun `the workflow path is a real workflow location`() {
        assertEquals(".github/workflows/sufyan-harness-build.yml", CloudBuild.WORKFLOW_PATH)
        assertTrue(CloudBuild.WORKFLOW_PATH.endsWith(CloudBuild.WORKFLOW_FILE))
    }

    // ---- picking the artifact ----------------------------------------------

    @Test
    fun `the exact artifact name wins`() {
        val names = listOf("reports", "sufyan-harness-debug-apk", "sufyan-harness-release-apk")
        assertEquals("sufyan-harness-release-apk", CloudBuild.pickArtifact(names, "release"))
        assertEquals("sufyan-harness-debug-apk", CloudBuild.pickArtifact(names, "debug"))
    }

    @Test
    fun `a hand-written workflow with different names still works`() {
        assertEquals("my-app-apk", CloudBuild.pickArtifact(listOf("logs", "my-app-apk"), "release"))
        assertEquals("nightly-release-apk", CloudBuild.pickArtifact(listOf("nightly-release-apk", "debug-symbols"), "release"))
    }

    @Test
    fun `no apk artifact means no guess`() {
        assertNull(CloudBuild.pickArtifact(listOf("reports", "mapping"), "debug"))
        assertNull(CloudBuild.pickArtifact(emptyList(), "debug"))
    }

    @Test
    fun `an unknown variant is treated as debug`() {
        assertEquals("debug", CloudBuild.normalise("banana"))
        assertEquals("release", CloudBuild.normalise("RELEASE"))
    }

    // ---- run progress -------------------------------------------------------

    @Test
    fun `run states map onto something a user can act on`() {
        assertEquals(CloudBuild.Progress.Queued, CloudBuild.progressOf("queued", null))
        assertEquals(CloudBuild.Progress.Running, CloudBuild.progressOf("in_progress", null))
        assertEquals(CloudBuild.Progress.Succeeded, CloudBuild.progressOf("completed", "success"))

        val failed = CloudBuild.progressOf("completed", "failure")
        assertTrue(failed is CloudBuild.Progress.Ended)
        assertTrue((failed as CloudBuild.Progress.Ended).explanation.contains("failed"))

        val cancelled = CloudBuild.progressOf("completed", "cancelled")
        assertTrue((cancelled as CloudBuild.Progress.Ended).explanation.contains("cancelled"))
    }

    @Test
    fun `a completed run is never reported as success without the conclusion saying so`() {
        val unknown = CloudBuild.progressOf("completed", "neutral")
        assertTrue(unknown is CloudBuild.Progress.Ended)
    }

    // ---- unpacking the artifact --------------------------------------------

    @Test
    fun `the apk is extracted out of the artifact zip`() {
        val zip = zipWith("app-release.apk" to ByteArray(2048) { 1 })
        val apk = CloudBuild.extractApk(zip, File(temp.root, "out")).getOrThrow()
        assertEquals("app-release.apk", apk.name)
        assertEquals(2048L, apk.length())
    }

    @Test
    fun `a nested path in the artifact still yields the apk`() {
        val zip = zipWith("app/build/outputs/apk/debug/app-debug.apk" to ByteArray(10) { 7 })
        val apk = CloudBuild.extractApk(zip, File(temp.root, "out2")).getOrThrow()
        assertEquals("app-debug.apk", apk.name)
    }

    @Test
    fun `an artifact with no apk fails loudly`() {
        val zip = zipWith("report.html" to "<html/>".toByteArray())
        val result = CloudBuild.extractApk(zip, File(temp.root, "out3"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no .apk"))
    }

    @Test
    fun `a path traversal entry is refused`() {
        val zip = zipWith("../../evil.apk" to ByteArray(4))
        val dest = File(temp.root, "out4")
        val result = CloudBuild.extractApk(zip, dest)
        // Either the name is flattened into dest, or it is rejected — never written outside dest.
        if (result.isSuccess) {
            assertTrue(result.getOrThrow().canonicalPath.startsWith(dest.canonicalPath))
        }
        assertTrue(!File(temp.root, "evil.apk").exists())
    }

    @Test
    fun `an oversized artifact is refused instead of filling the device`() {
        val zip = zipWith("big.apk" to ByteArray(4096))
        val result = CloudBuild.extractApk(zip, File(temp.root, "out5"), maxBytes = 1024)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("larger than"))
    }
}
