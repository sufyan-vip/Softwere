package com.sufyan.harness.runtime

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GitFileChange(val status: String, val path: String) {
    val label: String get() = when (status.trim().firstOrNull()) {
        'M' -> "modified"
        'A' -> "added"
        'D' -> "deleted"
        'R' -> "renamed"
        '?' -> "new"
        else -> "changed"
    }
}

data class GitStatus(
    val isRepo: Boolean,
    val branch: String?,
    val changes: List<GitFileChange>,
    val error: String? = null,
) {
    val modified get() = changes.count { it.status.contains('M') }
    val added get() = changes.count { it.status.contains('A') || it.status.startsWith("??") }
    val deleted get() = changes.count { it.status.contains('D') }
}

data class Checkpoint(val id: String, val label: String, val timestampMs: Long) {
    val when_: String
        get() = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

/**
 * Git integration backed by a real `git` executable. When git is not on PATH,
 * every call reports that clearly instead of pretending to work; the snapshot
 * checkpoint system below is the git-free fallback and is also fully real.
 */
class GitService(private val projectDir: File, private val linux: LinuxRuntime?) {

    private suspend fun git(args: String, timeoutMs: Long = 60_000): CommandResult {
        val cmd = "git -c safe.directory='*' $args"
        linux?.let { if (it.prootAvailable() && it.rootfsPresent()) return it.exec(cmd, projectDir, timeoutMs) }
        return ShellSession.exec(cmd, projectDir, timeoutMs)
    }

    suspend fun available(): Boolean = ShellSession.exec("git --version", projectDir, 15_000).ok ||
        (linux?.let { it.prootAvailable() && it.rootfsPresent() && it.exec("git --version", projectDir, 15_000).ok } ?: false)

    suspend fun status(): GitStatus {
        if (!available()) {
            return GitStatus(false, null, emptyList(), "Git is not installed. Install it from Settings → Toolchains.")
        }
        if (!File(projectDir, ".git").isDirectory) {
            return GitStatus(false, null, emptyList(), null)
        }
        val branch = git("rev-parse --abbrev-ref HEAD").takeIf { it.ok }?.stdout?.trim()
        val res = git("status --porcelain")
        if (!res.ok) return GitStatus(true, branch, emptyList(), res.stderr.take(300))
        val changes = res.stdout.lineSequence().filter { it.isNotBlank() }.map {
            GitFileChange(it.take(2), it.drop(3).trim())
        }.toList()
        return GitStatus(true, branch, changes)
    }

    suspend fun init(): Result<String> = run {
        val r = git("init")
        if (r.ok) Result.success(r.stdout.trim()) else Result.failure(IllegalStateException(r.stderr.ifBlank { "git init failed." }))
    }

    suspend fun diff(path: String? = null): Result<String> {
        val r = git("--no-pager diff --no-color ${path?.let { "-- \"$it\"" } ?: ""}")
        return if (r.ok) Result.success(r.stdout) else Result.failure(IllegalStateException(r.stderr.ifBlank { "git diff failed." }))
    }

    suspend fun log(limit: Int = 30): Result<List<String>> {
        val r = git("--no-pager log --no-color --pretty=format:%h%x09%ad%x09%s --date=short -n $limit")
        return if (r.ok) Result.success(r.stdout.lines().filter { it.isNotBlank() })
        else Result.failure(IllegalStateException(r.stderr.ifBlank { "No commits yet." }))
    }

    suspend fun branches(): Result<List<String>> {
        val r = git("branch --format=%(refname:short)")
        return if (r.ok) Result.success(r.stdout.lines().filter { it.isNotBlank() }) else Result.failure(IllegalStateException(r.stderr))
    }

    suspend fun commit(message: String): Result<String> {
        val add = git("add -A")
        if (!add.ok) return Result.failure(IllegalStateException(add.stderr.ifBlank { "git add failed." }))
        val safe = message.replace("\"", "\\\"")
        val r = git("-c user.email=harness@local -c user.name=\"Sufyan Harness\" commit -m \"$safe\"")
        return if (r.ok) Result.success(r.stdout.trim())
        else Result.failure(IllegalStateException(r.combined().ifBlank { "git commit failed." }))
    }

    suspend fun checkout(branch: String): Result<String> {
        val r = git("checkout \"$branch\"")
        return if (r.ok) Result.success(r.combined()) else Result.failure(IllegalStateException(r.stderr))
    }
}

/**
 * Git-free snapshot checkpoints: a full copy of the project tree so AI changes
 * can always be rolled back even on devices without git. Restores are atomic —
 * the live tree is only swapped after the restore copy fully succeeds.
 */
class CheckpointStore(private val projectDir: File, storeRoot: File) {

    private val dir = File(storeRoot, "checkpoints/${projectDir.name}").apply { mkdirs() }

    fun list(): List<Checkpoint> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { f ->
            val meta = File(f, ".checkpoint")
            val label = if (meta.exists()) meta.readText().trim() else f.name
            Checkpoint(f.name, label, f.name.toLongOrNull() ?: f.lastModified())
        }.sortedByDescending { it.timestampMs }

    fun create(label: String): Result<Checkpoint> = runCatching {
        val id = System.currentTimeMillis().toString()
        val target = File(dir, id)
        projectDir.copyRecursively(target, overwrite = true)
        File(target, ".checkpoint").writeText(label)
        Checkpoint(id, label, id.toLong())
    }

    fun restore(checkpoint: Checkpoint): Result<Unit> = runCatching {
        val source = File(dir, checkpoint.id)
        check(source.isDirectory) { "Checkpoint data is missing." }
        val staging = File(projectDir.parentFile, "${projectDir.name}.restore")
        staging.deleteRecursively()
        source.copyRecursively(staging, overwrite = true)
        File(staging, ".checkpoint").delete()
        val backup = File(projectDir.parentFile, "${projectDir.name}.prev")
        backup.deleteRecursively()
        check(projectDir.renameTo(backup)) { "Could not move the current project aside." }
        if (!staging.renameTo(projectDir)) {
            backup.renameTo(projectDir) // roll the rollback back
            error("Restore failed; the project was left unchanged.")
        }
        backup.deleteRecursively()
    }

    fun delete(checkpoint: Checkpoint): Result<Unit> = runCatching {
        File(dir, checkpoint.id).deleteRecursively()
    }

    fun sizeOf(checkpoint: Checkpoint): Long =
        File(dir, checkpoint.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
