package com.sufyan.harness.runtime

import com.sufyan.harness.data.DiffEngine
import java.io.File

/** A file the AI (or the editor) changed during a session, with its before/after content. */
data class ReviewedChange(
    val path: String,
    val before: String?,
    val after: String?,
    val diff: DiffEngine.FileDiff,
) {
    val isNew: Boolean get() = before == null
    val isDeleted: Boolean get() = after == null
    val stat: String get() = "+${diff.added} / -${diff.removed}"
}

/**
 * §12 / §17 — "review what the AI changed", implemented without git.
 *
 * A snapshot of the project's text files is taken before the agent runs; afterwards the same files
 * are read again and compared with [DiffEngine]. Because the before-image is real file content, a
 * per-file **revert** can restore exactly what was there — even on a device with no git binary and
 * no checkpoint.
 */
class ChangeTracker(private val projectDir: File) {

    companion object {
        private const val MAX_SNAPSHOT_BYTES = 1024 * 1024L
        private val SKIP_DIRS = setOf(".git", "node_modules", "build", "dist", ".gradle", ".harness-tmp")
        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "jar", "apk",
            "so", "class", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "bin",
        )
    }

    private var snapshot: Map<String, String>? = null

    private fun textFiles(): Map<String, String> {
        val root = projectDir.canonicalFile
        if (!root.isDirectory) return emptyMap()
        return root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && it.length() <= MAX_SNAPSHOT_BYTES && it.extension.lowercase() !in BINARY_EXT }
            .mapNotNull { file ->
                val rel = file.canonicalPath.removePrefix(root.path).trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                runCatching { rel to file.readText() }.getOrNull()
            }
            .toMap()
    }

    /** Called immediately before an agent turn. */
    fun capture() {
        snapshot = textFiles()
    }

    fun hasSnapshot(): Boolean = snapshot != null

    /** Compares the current tree with the snapshot. Empty when nothing changed. */
    fun review(): List<ReviewedChange> {
        val before = snapshot ?: return emptyList()
        val after = textFiles()
        val paths = (before.keys + after.keys).sorted()
        return paths.mapNotNull { path ->
            val old = before[path]
            val new = after[path]
            if (old == new) return@mapNotNull null
            ReviewedChange(path, old, new, DiffEngine.diff(path, old, new))
        }
    }

    /** Restores one file to its snapshot content — a real write, or a real delete for new files. */
    fun revert(change: ReviewedChange): Result<Unit> = runCatching {
        val target = File(projectDir, change.path).canonicalFile
        require(target.path.startsWith(projectDir.canonicalPath)) { "Refusing to write outside the project." }
        if (change.before == null) {
            if (target.exists() && !target.delete()) throw IllegalStateException("Could not delete ${change.path}.")
        } else {
            target.parentFile?.mkdirs()
            target.writeText(change.before)
        }
    }

    fun revertAll(changes: List<ReviewedChange>): Result<Int> = runCatching {
        var reverted = 0
        changes.forEach { if (revert(it).isSuccess) reverted++ }
        reverted
    }

    /** Accepting simply drops the before-image so the next turn compares against the new state. */
    fun accept() {
        snapshot = textFiles()
    }

    fun clear() {
        snapshot = null
    }
}
