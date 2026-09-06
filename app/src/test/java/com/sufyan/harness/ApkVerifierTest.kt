package com.sufyan.harness

import com.sufyan.harness.runtime.ApkVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * §38 — "the APK exists" is never taken on trust. These tests pin the rule that a file is only
 * called a valid package when it really contains a manifest and compiled code.
 */
class ApkVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, String>): File {
        val f = temp.newFile(name)
        ZipOutputStream(f.outputStream()).use { out ->
            entries.forEach { (path, body) ->
                out.putNextEntry(ZipEntry(path))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `a real looking apk is accepted`() {
        val f = zip(
            "app.apk",
            mapOf(
                "AndroidManifest.xml" to "binary",
                "classes.dex" to "dex",
                "META-INF/CERT.RSA" to "sig",
                "lib/arm64-v8a/libx.so" to "so",
                "res/layout/main.xml" to "x",
            ),
        )
        val r = ApkVerifier.verify(f)
        assertTrue(r.valid)
        assertTrue(r.hasManifest)
        assertTrue(r.hasDex)
        assertTrue(r.signed)
        assertEquals(listOf("arm64-v8a"), r.nativeAbis)
        assertTrue(r.summary.contains("Valid Android package"))
    }

    @Test
    fun `multi dex is recognised`() {
        val f = zip("multi.apk", mapOf("AndroidManifest.xml" to "m", "classes2.dex" to "d"))
        assertTrue(ApkVerifier.verify(f).hasDex)
    }

    @Test
    fun `a zip without a manifest is rejected`() {
        val f = zip("plain.zip", mapOf("readme.txt" to "hello"))
        val r = ApkVerifier.verify(f)
        assertFalse(r.valid)
        assertTrue(r.problem!!.contains("AndroidManifest"))
    }

    @Test
    fun `an apk without code is rejected`() {
        val f = zip("nocode.apk", mapOf("AndroidManifest.xml" to "m"))
        val r = ApkVerifier.verify(f)
        assertFalse(r.valid)
        assertTrue(r.problem!!.contains("classes.dex"))
    }

    @Test
    fun `an unsigned apk is valid but reported as unsigned`() {
        val f = zip("unsigned.apk", mapOf("AndroidManifest.xml" to "m", "classes.dex" to "d"))
        val r = ApkVerifier.verify(f)
        assertTrue(r.valid)
        assertFalse(r.signed)
        assertTrue(r.summary.contains("unsigned"))
    }

    @Test
    fun `an empty file is rejected`() {
        val f = temp.newFile("empty.apk")
        val r = ApkVerifier.verify(f)
        assertFalse(r.valid)
        assertTrue(r.problem!!.contains("empty"))
    }

    @Test
    fun `a missing file is rejected`() {
        val r = ApkVerifier.verify(File(temp.root, "nope.apk"))
        assertFalse(r.valid)
        assertTrue(r.problem!!.contains("does not exist"))
    }

    @Test
    fun `a truncated archive is reported instead of crashing`() {
        val f = temp.newFile("broken.apk")
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01))
        val r = ApkVerifier.verify(f)
        assertFalse(r.valid)
        assertTrue(r.problem!!.contains("zip"))
    }
}
