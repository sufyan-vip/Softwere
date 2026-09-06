package com.sufyan.harness.runtime

import java.io.File

/**
 * §56 — crash/kill recovery.
 *
 * Long operations (runtime install, Gradle build, APK install, server start, an agent turn, a git
 * operation) write a marker file before they begin and delete it when they end. If the process is
 * killed in between, the marker survives, so the next launch can *tell the user what was
 * interrupted* instead of leaving half-finished state around silently (RULE 4).
 *
 * Recovery also removes the scratch directories those operations create, because a half-extracted
 * rootfs or a half-written export is worse than none at all.
 */
class Recovery(private val stateDir: File) {

    enum class Operation(val id: String, val label: String, val advice: String) {
        RuntimeInstall(
            "runtime-install",
            "Linux runtime installation",
            "The download was interrupted. Open Settings › Linux runtime and install again — the partial download has been removed.",
        ),
        Build(
            "build",
            "Android build",
            "The build did not finish. Open the Build screen and run it again; no APK from that run was kept.",
        ),
        Install(
            "install",
            "APK install",
            "The installer did not report a result. Check your app list, then install the APK again from the Build screen.",
        ),
        Server(
            "server",
            "Preview server",
            "The preview server was stopped when the app closed. Start it again from the Preview screen.",
        ),
        AgentTurn(
            "agent",
            "AI task",
            "The AI task was cut short. Its file changes up to that point are in Review changes — keep or revert them there.",
        ),
        Git(
            "git",
            "Git operation",
            "A git operation was interrupted. Check Changes before committing again.",
        ),
        ;

        companion object {
            fun byId(id: String): Operation? = entries.find { it.id == id }
        }
    }

    /** One interrupted operation found at startup. */
    data class Interrupted(
        val operation: Operation,
        val detail: String,
        val startedAt: Long,
    ) {
        val title: String get() = "${operation.label} was interrupted"
        val message: String
            get() = buildString {
                append(operation.advice)
                if (detail.isNotBlank()) {
                    append("\n\nIt was working on: ")
                    append(detail)
                }
            }
    }

    private val dir: File get() = File(stateDir, "recovery").apply { mkdirs() }

    private fun markerOf(operation: Operation) = File(dir, "${operation.id}.marker")

    /** Records that [operation] started. Call this *before* the work, not after. */
    fun begin(operation: Operation, detail: String = "") {
        runCatching { markerOf(operation).writeText("${System.currentTimeMillis()}\n$detail") }
    }

    /** Records that [operation] finished — successfully or with a reported error, both are fine. */
    fun end(operation: Operation) {
        runCatching { markerOf(operation).delete() }
    }

    /** Runs [block] between [begin] and [end], even if it throws. */
    inline fun <T> track(operation: Operation, detail: String = "", block: () -> T): T {
        begin(operation, detail)
        return try {
            block()
        } finally {
            end(operation)
        }
    }

    fun isRunning(operation: Operation): Boolean = markerOf(operation).isFile

    /** Everything that was still marked as running when the process died. */
    fun pending(): List<Interrupted> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".marker") }
            .mapNotNull { file ->
                val op = Operation.byId(file.name.removeSuffix(".marker")) ?: return@mapNotNull null
                val text = runCatching { file.readText() }.getOrDefault("")
                val startedAt = text.lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: file.lastModified()
                val detail = text.substringAfter('\n', "").trim()
                Interrupted(op, detail, startedAt)
            }
            .sortedByDescending { it.startedAt }

    /** Clears the markers after the user has been told. */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Deletes scratch state left behind by an interrupted run. Only paths this app creates are
     * touched, and a project's own files are never removed.
     */
    fun sweep(vararg roots: File): Int {
        var removed = 0
        val scratchNames = setOf(".harness-tmp", ".harness-partial", "install-tmp")
        for (root in roots) {
            if (!root.isDirectory) continue
            root.listFiles().orEmpty().forEach { child ->
                val stale = child.name in scratchNames ||
                    (child.isFile && (child.name.startsWith("import-") || child.name.startsWith("partial-")))
                if (stale && child.deleteRecursively()) removed++
            }
        }
        return removed
    }
}
