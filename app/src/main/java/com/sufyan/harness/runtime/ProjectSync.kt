package com.sufyan.harness.runtime

import java.io.File
import java.security.MessageDigest

/** The difference between the working copy and a remote branch. */
data class SyncStatus(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val unchanged: Int,
    val remoteSha: String?,
    val inSyncWithLastPull: Boolean,
) {
    val changedCount: Int get() = added.size + modified.size + deleted.size
    val clean: Boolean get() = changedCount == 0
    val summary: String
        get() = if (clean) "Up to date with the remote branch." else
            "${added.size} added, ${modified.size} modified, ${deleted.size} deleted"
}

/**
 * §29-§32 — comparing the on-device project with a GitHub branch.
 *
 * Comparison uses the *same* content hash git itself uses (`sha1("blob <size>\0" + bytes)`), so a
 * file is reported as modified only when its bytes genuinely differ from the blob on GitHub. No
 * timestamps, no guesses.
 */
object ProjectSync {

    /** Directories and files that must never be uploaded. */
    val IGNORED_DIRS = setOf(
        ".git", "node_modules", "build", "dist", ".gradle", ".idea", ".harness-tmp",
        "__pycache__", ".next", ".output", ".venv", "vendor",
    )

    private val IGNORED_FILES = setOf(".DS_Store", "local.properties")

    /** Hard cap per file: the GitHub blob API is not a place for 10 MB binaries. */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    fun isIgnored(relativePath: String): Boolean {
        val parts = relativePath.split('/')
        if (parts.any { it in IGNORED_DIRS }) return true
        return parts.last() in IGNORED_FILES
    }

    /** Git's blob object id for [bytes]. Identical to what `git hash-object` produces. */
    fun blobSha(bytes: ByteArray): String {
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.ISO_8859_1)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Every uploadable file in the project, keyed by its repo-relative path. */
    fun collect(projectDir: File): Map<String, File> {
        val root = projectDir.canonicalFile
        return root.walkTopDown()
            .onEnter { dir -> dir.name !in IGNORED_DIRS }
            .filter { it.isFile }
            .mapNotNull { file ->
                val rel = file.canonicalPath.removePrefix(root.path).trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                if (rel.isEmpty() || isIgnored(rel)) null else rel to file
            }
            .toMap()
    }

    /** Files that are too large to push, reported to the user instead of being skipped silently. */
    fun oversized(local: Map<String, File>): List<String> =
        local.filter { it.value.length() > MAX_FILE_BYTES }.keys.sorted()

    fun status(
        local: Map<String, File>,
        remote: List<RemoteFile>,
        remoteSha: String?,
        lastSyncSha: String?,
    ): SyncStatus {
        val remoteByPath = remote.associateBy { it.path }
        val added = mutableListOf<String>()
        val modified = mutableListOf<String>()
        var unchanged = 0

        for ((path, file) in local) {
            if (file.length() > MAX_FILE_BYTES) continue
            val remoteFile = remoteByPath[path]
            if (remoteFile == null) {
                added += path
            } else {
                val sha = runCatching { blobSha(file.readBytes()) }.getOrNull()
                if (sha != null && sha == remoteFile.sha) unchanged++ else modified += path
            }
        }
        val deleted = remoteByPath.keys.filter { it !in local.keys && !isIgnored(it) }

        return SyncStatus(
            added = added.sorted(),
            modified = modified.sorted(),
            deleted = deleted.sorted(),
            unchanged = unchanged,
            remoteSha = remoteSha,
            inSyncWithLastPull = lastSyncSha != null && lastSyncSha == remoteSha,
        )
    }

    /**
     * §32 — files that changed on both sides. A conflict is only claimed when the remote branch has
     * genuinely moved since the last sync *and* the local copy of that same file differs.
     */
    fun conflicts(status: SyncStatus, lastSyncSha: String?): List<SyncConflict> {
        if (lastSyncSha == null || status.remoteSha == null || lastSyncSha == status.remoteSha) return emptyList()
        return (status.modified.map { SyncConflict(it, localExists = true, remoteExists = true) } +
            status.deleted.map { SyncConflict(it, localExists = false, remoteExists = true) })
            .sortedBy { it.path }
    }

    /** The payload for a push: every changed file's real bytes. */
    fun payload(local: Map<String, File>, status: SyncStatus): Map<String, ByteArray> =
        (status.added + status.modified)
            .mapNotNull { path -> local[path]?.let { f -> path to f.readBytes() } }
            .toMap()
}
