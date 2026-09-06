package com.sufyan.harness

import com.sufyan.harness.runtime.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §56 — a crash must survive the process that caused it, and be readable on the next launch. */
class CrashLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun log() = CrashLog(File(temp.root, "crash"))

    @Test
    fun `no crash means no report`() {
        assertNull(log().last())
    }

    @Test
    fun `a recorded crash is readable after a fresh instance is created`() {
        val error = IllegalStateException("boom while opening project")
        log().record(error, now = 1788640315747L)

        // A new instance stands in for the next launch of the process.
        val report = log().last()
        assertNotNull(report)
        assertEquals(1788640315747L, report!!.time)
        assertTrue(report.message.contains("IllegalStateException"))
        assertTrue(report.message.contains("boom while opening project"))
        assertTrue(report.stackTrace.contains("CrashLogTest"))
    }

    @Test
    fun `the real device crash shape is parsed`() {
        val text = """
            time: 1788640315747

            msg: java.lang.NullPointerException: Attempt to invoke interface method 'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)' on a null object reference

            stacktrace: java.lang.RuntimeException: Cannot create an instance of class com.sufyan.harness.HarnessViewModel
            	at androidx.lifecycle.ViewModelProvider${'$'}AndroidViewModelFactory.create(ViewModelProvider.android.kt:315)
        """.trimIndent()

        val report = CrashLog.parse(text)
        assertNotNull(report)
        assertEquals(1788640315747L, report!!.time)
        assertTrue(report.message.startsWith("java.lang.NullPointerException"))
        assertTrue(report.stackTrace.contains("Cannot create an instance"))
    }

    @Test
    fun `a message with no exception message still records the type`() {
        log().record(NullPointerException())
        assertTrue(log().last()!!.message.contains("NullPointerException"))
    }

    @Test
    fun `clearing removes the report so it is shown once`() {
        val l = log()
        l.record(RuntimeException("once"))
        assertNotNull(l.last())
        l.clear()
        assertNull(l.last())
        assertFalse(File(File(temp.root, "crash"), CrashLog.FILE_NAME).exists())
    }

    @Test
    fun `a later crash replaces the earlier one`() {
        val l = log()
        l.record(RuntimeException("first"))
        l.record(RuntimeException("second"))
        assertTrue(l.last()!!.message.contains("second"))
    }

    @Test
    fun `garbage on disk does not produce a bogus report`() {
        val dir = File(temp.root, "crash").apply { mkdirs() }
        File(dir, CrashLog.FILE_NAME).writeText("   ")
        assertNull(log().last())
    }

    @Test
    fun `the rendered form round-trips`() {
        val original = CrashLog.Report(42L, "java.lang.Error: x", "java.lang.Error: x\n\tat Foo.bar(Foo.kt:1)")
        val parsed = CrashLog.parse(original.render())
        assertEquals(original, parsed)
    }
}
