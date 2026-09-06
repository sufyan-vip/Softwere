package com.sufyan.harness.data

/**
 * Decides whether the app should silently re-open the project you had open last time.
 *
 * This exists because of a real crash: the view model used to restore the last project from its
 * constructor, and a failure there kills the app before any UI is drawn — so it crashes on *every*
 * subsequent launch, with no way in from the user's side. Two rules follow from that:
 *
 *  1. Restoring is a decision, not a side effect. It is pure, so it can be unit tested.
 *  2. A restore that never finished is never retried automatically. The app writes a "restoring X"
 *     marker before it opens a project and clears it once the project is open. Finding that marker
 *     still set at launch means the previous attempt crashed or was killed, so the app opens the
 *     project list instead and says why (RULE 4 — no hidden failures).
 */
object StartupGuard {

    sealed interface Decision {
        /** Nothing to restore: no previous project, or the user closed it deliberately. */
        data object None : Decision

        /** Safe to re-open [projectId]. */
        data class Open(val projectId: String) : Decision

        /** Deliberately not re-opening; [reason] is shown to the user verbatim. */
        data class Skip(val projectId: String?, val reason: String) : Decision
    }

    /**
     * @param lastProjectId   the project open when the app was last used (`null` = none).
     * @param pendingRestoreId the marker written just before the last restore attempt; non-null
     *                        means that attempt never completed.
     * @param knownProjectIds ids that actually exist on disk right now.
     */
    fun decide(
        lastProjectId: String?,
        pendingRestoreId: String?,
        knownProjectIds: Collection<String>,
    ): Decision {
        if (pendingRestoreId != null) {
            return Decision.Skip(
                pendingRestoreId,
                "The app stopped unexpectedly while opening your last project. " +
                    "It is not being re-opened automatically — pick it from the list to try again.",
            )
        }
        val id = lastProjectId ?: return Decision.None
        if (id !in knownProjectIds) {
            return Decision.Skip(id, "The project that was open last time no longer exists on this device.")
        }
        return Decision.Open(id)
    }
}
