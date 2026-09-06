package com.sufyan.harness

import com.sufyan.harness.data.StartupGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the crash reported from a real device: the app opened once, then crashed on
 * every launch afterwards because it tried to restore the last project during construction.
 */
class StartupGuardTest {

    private val known = listOf("p1", "p2")

    @Test
    fun `no previous project means nothing to restore`() {
        assertEquals(
            StartupGuard.Decision.None,
            StartupGuard.decide(lastProjectId = null, pendingRestoreId = null, knownProjectIds = known),
        )
    }

    @Test
    fun `a known last project is re-opened`() {
        assertEquals(
            StartupGuard.Decision.Open("p2"),
            StartupGuard.decide(lastProjectId = "p2", pendingRestoreId = null, knownProjectIds = known),
        )
    }

    @Test
    fun `a project that no longer exists is skipped with a reason`() {
        val d = StartupGuard.decide(lastProjectId = "gone", pendingRestoreId = null, knownProjectIds = known)
        assertTrue(d is StartupGuard.Decision.Skip)
        assertTrue((d as StartupGuard.Decision.Skip).reason.contains("no longer exists"))
        assertEquals("gone", d.projectId)
    }

    @Test
    fun `an unfinished restore is never retried automatically`() {
        val d = StartupGuard.decide(lastProjectId = "p1", pendingRestoreId = "p1", knownProjectIds = known)
        assertTrue(d is StartupGuard.Decision.Skip)
        assertTrue((d as StartupGuard.Decision.Skip).reason.contains("stopped unexpectedly"))
    }

    @Test
    fun `the crash guard wins even when the project is perfectly valid`() {
        // This is the loop-breaker: the marker, not the project's validity, decides.
        val d = StartupGuard.decide(lastProjectId = "p2", pendingRestoreId = "p2", knownProjectIds = known)
        assertTrue(d is StartupGuard.Decision.Skip)
    }

    @Test
    fun `an empty workspace never restores anything`() {
        val d = StartupGuard.decide(lastProjectId = "p1", pendingRestoreId = null, knownProjectIds = emptyList())
        assertTrue(d is StartupGuard.Decision.Skip)
    }
}
