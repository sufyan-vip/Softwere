package com.sufyan.harness.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * §41 — real ZIP import/export for projects. Every function touches the actual filesystem: an export
 * writes the true bytes of every project file, and an import extracts them back. Nothing is stubbed
 * and no ZIP is faked (§3). Zip-slip is blocked on import so a malicious archive cannot escape the
 * project directory.
 */
object ProjectArchive {

    /** Writes every file under [sourceDir] into [dest] as a zip, preserving relative paths. */
    fun exportZip(sourceDir: File, dest: File): Result<Unit> = runCatching {
        require(sourceDir.isDirectory) { "Source is not a directory." }
        dest.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zip ->
            val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
            for (file in files) {
                val rel = file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Extracts [zipFile] into [destDir]. If every entry sits under one common top-level directory,
     * that root is stripped so the result lands at [destDir] itself.
     */
    fun importZip(destDir: File, zipFile: File): Result<Unit> = runCatching {
        require(zipFile.isFile) { "Archive not found." }
        destDir.mkdirs()
        val entries = readEntries(zipFile)

        val relPaths = entries.filter { it.isFile }.map { safeRel(it) }
        val commonRoot = commonPrefix(relPaths)

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
            while (true) {
                val entry = try { zin.nextEntry } catch (e: IOException) { null } ?: break
                val raw = entry.name
                val rel = safeRel(raw)

                // Strip a single top-level folder, if present, so "project-foo/*" imports as "*".
                val effective = if (commonRoot != null && rel.startsWith("$commonRoot/")) {
                    rel.removePrefix("$commonRoot/")
                } else rel

                if (effective.isEmpty() || effective == commonRoot) {
                    zin.closeEntry()
                    continue
                }
                val target = resolveInside(destDir, effective)
                when {
                    entry.isDirectory -> target.mkdirs()
                    else -> {
                        target.parentFile?.mkdirs()
                        zin.copyTo(target.outputStream())
                    }
                }
                zin.closeEntry()
            }
        }
    }

    /** Recursively copies every file from [srcDir] into [destDir]. */
    fun importFolder(destDir: File, srcDir: File): Result<Unit> = runCatching {
        require(srcDir.isDirectory) { "Source folder not found." }
        destDir.mkdirs()
        srcDir.walkTopDown().forEach { f ->
            if (f.isDirectory) return@forEach
            val rel = f.relativeTo(srcDir).path.replace(File.separatorChar, '/')
            val target = resolveInside(destDir, rel)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
    }

    private fun readEntries(zipFile: File): List<ZipEntry> {
        val out = mutableListOf<ZipEntry>()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
            while (true) {
                val entry = try { zin.nextEntry } catch (e: IOException) { null } ?: break
                out += entry
                zin.closeEntry()
            }
        }
        return out
    }

    /** Rejects absolute paths and traversal, returning a clean relative path. */
    private fun safeRel(entryName: String): String {
        val normalized = entryName.replace('\\', '/')
        require(!normalized.startsWith("/")) { "Archive entry has an absolute path." }
        require(!normalized.split("/").contains("..")) { "Archive entry escapes the destination." }
        return normalized.trim('/')
    }

    private fun resolveInside(base: File, rel: String): File {
        val target = File(base, rel).canonicalFile
        require(target.path.startsWith(base.canonicalPath + File.separator) || target == base.canonicalFile) {
            "Archive entry escapes the destination."
        }
        return target
    }

    /**
     * Longest leading *directory* shared by all paths. The final segment of each path is treated as a
     * filename and never part of the common root, so a lone "project-foo/readme.md" strips to
     * "project-foo" (meaning "readme.md" lands at the destination root) rather than to the whole path.
     */
    private fun commonPrefix(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        val dirs = paths.map { it.split('/').dropLast(1) }
        val first = dirs.first()
        val common = mutableListOf<String>()
        for (i in first.indices) {
            if (dirs.all { it.getOrNull(i) == first[i] }) common += first[i] else break
        }
        // Strip a single wrapping directory (GitHub "Download ZIP" style) so the project lands at
        // the destination root, not inside an extra folder.
        return if (common.isNotEmpty()) common.joinToString("/") else null
    }
}
