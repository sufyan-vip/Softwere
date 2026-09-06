package com.sufyan.harness

import com.sufyan.harness.runtime.CommandDiagnostics
import com.sufyan.harness.runtime.FixAction
import com.sufyan.harness.runtime.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4 / §23 — a failure must always be explained with WHAT / WHY / HOW, and the explanation must be
 * based on the probed environment rather than on a guess from the exit code.
 */
class CommandDiagnosticsTest {

    private fun probe(exe: String, onPath: Boolean, linux: Boolean = false) =
        Probe(exe, onPath, "/system/bin:/system/xbin", if (linux) "Linux runtime" else "Android shell", linux)

    @Test
    fun `executableOf ignores leading environment assignments`() {
        assertEquals("npm", CommandDiagnostics.executableOf("FOO=1 BAR=2 npm run build"))
        assertEquals("./gradlew", CommandDiagnostics.executableOf("./gradlew assembleDebug"))
        assertEquals("ls", CommandDiagnostics.executableOf("  ls -la  "))
    }

    @Test
    fun `missing tool is only claimed when the probe agrees`() {
        val d = CommandDiagnostics.diagnose("npm install", 127, "sh: npm: not found", probe = probe("npm", false))
        assertEquals("Command unavailable", d.what)
        assertTrue(d.why.contains("command -v npm"))
        assertTrue(d.actions.any { it is FixAction.InstallTool && it.toolId == "npm" })
    }

    @Test
    fun `exit 127 with the binary on PATH is diagnosed as a loader problem`() {
        val d = CommandDiagnostics.diagnose("node app.js", 127, "not found", probe = probe("node", true))
        assertEquals("Program could not start", d.what)
        assertFalse(d.actions.any { it is FixAction.InstallTool })
    }

    @Test
    fun `exit 126 is explained as a permission problem`() {
        val d = CommandDiagnostics.diagnose("./run.sh", 126, "permission denied", probe = probe("./run.sh", true))
        assertEquals("Permission denied", d.what)
        assertTrue(d.how.contains("chmod") || d.how.contains("interpreter"))
    }

    @Test
    fun `interrupted command offers a retry`() {
        val d = CommandDiagnostics.diagnose("sleep 100", 130, "", probe = probe("sleep", true))
        assertEquals("Command interrupted", d.what)
        assertTrue(d.actions.contains(FixAction.Retry))
    }

    @Test
    fun `killed command is attributed to the system`() {
        val d = CommandDiagnostics.diagnose("gradle build", 137, "Killed", probe = probe("gradle", true))
        assertEquals("Command was killed", d.what)
    }

    @Test
    fun `every diagnosis fills what why and how`() {
        val cases = listOf(
            Triple("npm i", 127, "not found"),
            Triple("./x", 126, "denied"),
            Triple("y", 130, ""),
            Triple("z", 137, "Killed"),
            Triple("make", 2, "make: *** No targets specified"),
            Triple("tsc", 1, "error TS2304: Cannot find name 'foo'"),
        )
        for ((cmd, code, err) in cases) {
            val d = CommandDiagnostics.diagnose(cmd, code, err, probe = probe(cmd.substringBefore(' '), true))
            assertTrue("what is blank for $cmd", d.what.isNotBlank())
            assertTrue("why is blank for $cmd", d.why.isNotBlank())
            assertTrue("how is blank for $cmd", d.how.isNotBlank())
        }
    }

    @Test
    fun `the linux runtime is offered only when it is not ready`() {
        val withoutRuntime = CommandDiagnostics.diagnose("git status", 127, "not found", probe = probe("git", false))
        assertTrue(withoutRuntime.actions.contains(FixAction.OpenRuntime))

        val withRuntime = CommandDiagnostics.diagnose("git status", 127, "not found", probe = probe("git", false, linux = true))
        assertFalse(withRuntime.actions.contains(FixAction.OpenRuntime))
    }
}
