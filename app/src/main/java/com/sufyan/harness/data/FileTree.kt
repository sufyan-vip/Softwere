package com.sufyan.harness.data

import java.io.File
import java.io.IOException

data class Node(val file: File, val depth: Int, val isDir: Boolean)

/**
 * Safe, sandboxed file operations. Every path is validated to stay inside the
 * project root so neither the user nor the AI agent can escape the workspace.
 */
class ProjectFiles(val root: File) {

    companion object {
        const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024L // 2 MB guard for the editor
        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "jar", "apk",
            "so", "class", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "bin",
        )
    }

    /** Resolves [relative] inside the root, or throws if it escapes. */
    fun resolve(relative: String): File {
        val target = File(root, relative).canonicalFile
        val base = root.canonicalFile
        if (!target.path.startsWith(base.path)) {
            throw SecurityException("Path escapes the project sandbox: $relative")
        }
        return target
    }

    fun relativePath(file: File): String =
        file.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')

    fun isBinary(file: File): Boolean = file.extension.lowercase() in BINARY_EXT

    fun list(dir: File = root): List<File> =
        (dir.listFiles() ?: emptyArray()).sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
        )

    /** Flattened tree honouring an expanded-directory set. */
    fun tree(expanded: Set<String>): List<Node> {
        val out = mutableListOf<Node>()
        fun walk(dir: File, depth: Int) {
            for (f in list(dir)) {
                if (f.name == ".git" && depth == 0) continue
                out += Node(f, depth, f.isDirectory)
                if (f.isDirectory && relativePath(f) in expanded) walk(f, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun read(relative: String): Result<String> = runCatching {
        val f = resolve(relative)
        if (!f.isFile) throw IOException("Not a file: $relative")
        if (f.length() > MAX_EDITABLE_BYTES) {
            throw IOException("File is ${f.length() / 1024} KB — too large to open in the editor (limit 2 MB).")
        }
        if (isBinary(f)) throw IOException("Binary file cannot be edited as text.")
        f.readText()
    }

    fun write(relative: String, content: String): Result<Unit> = runCatching {
        val f = resolve(relative)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun createFile(relative: String): Result<File> = runCatching {
        val f = resolve(relative)
        if (f.exists()) throw IOException("Already exists: $relative")
        f.parentFile?.mkdirs()
        if (!f.createNewFile()) throw IOException("Could not create $relative")
        f
    }

    fun createDir(relative: String): Result<File> = runCatching {
        val f = resolve(relative)
        if (f.exists()) throw IOException("Already exists: $relative")
        if (!f.mkdirs()) throw IOException("Could not create directory $relative")
        f
    }

    fun delete(relative: String): Result<Unit> = runCatching {
        val f = resolve(relative)
        if (!f.exists()) throw IOException("Does not exist: $relative")
        if (!f.deleteRecursively()) throw IOException("Could not delete $relative")
    }

    fun rename(relative: String, newName: String): Result<Unit> = runCatching {
        val f = resolve(relative)
        val target = File(f.parentFile, newName)
        if (target.exists()) throw IOException("Already exists: $newName")
        if (!f.renameTo(target)) throw IOException("Could not rename $relative")
    }

    /** Recursive text search; results are "path:line: text". */
    fun search(query: String, limit: Int = 200): List<String> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && !isBinary(it) && it.length() < MAX_EDITABLE_BYTES && !it.path.contains("/.git/") }
            .forEach { f ->
                if (out.size >= limit) return@forEach
                runCatching {
                    f.useLines { lines ->
                        lines.forEachIndexed { i, line ->
                            if (out.size < limit && line.contains(query, ignoreCase = true)) {
                                out += "${relativePath(f)}:${i + 1}: ${line.trim().take(160)}"
                            }
                        }
                    }
                }
            }
        return out
    }
}
