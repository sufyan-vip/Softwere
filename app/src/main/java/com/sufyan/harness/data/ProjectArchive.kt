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

    /** Directories that are build output or dependency caches, never part of "source only". */
    val NON_SOURCE_DIRS = setOf(
        "node_modules", "build", "dist", ".gradle", ".git", ".next", ".output",
        "__pycache__", ".venv", ".harness-tmp", "out",
    )

    /** §42 — the production output directory, or null when the project has not been built. */
    fun productionDir(sourceDir: File): File? =
        listOf("dist", "build/web", "out", "public/build", "build")
            .map { File(sourceDir, it) }
            .firstOrNull { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }

    /** Writes every file under [sourceDir] into [dest] as a zip, preserving relative paths. */
    fun exportZip(sourceDir: File, dest: File): Result<Unit> = exportZipFiltered(sourceDir, dest) { true }

    /**
     * §41 — zips only the entries [include] accepts. Used for "source only" and "selected files";
     * the archive always contains the real bytes of the files it lists.
     */
    fun exportZipFiltered(sourceDir: File, dest: File, include: (String) -> Boolean): Result<Unit> = runCatching {
        require(sourceDir.isDirectory) { "Source is not a directory." }
        dest.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zip ->
            val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
            for (file in files) {
                val rel = file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                if (!include(rel)) continue
                zip.putNextEntry(ZipEntry(rel))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        if (count == 0) {
            dest.delete()
            throw IOException("Nothing matched, so no archive was written.")
        }
    }

    /** §41 — source only: skips dependency and build directories. */
    fun exportSource(sourceDir: File, dest: File): Result<Unit> =
        exportZipFiltered(sourceDir, dest) { rel -> rel.split('/').none { it in NON_SOURCE_DIRS } }

    /** §42 — the production build, and only when it genuinely exists on disk. */
    fun exportProduction(sourceDir: File, dest: File): Result<Unit> {
        val prod = productionDir(sourceDir)
            ?: return Result.failure(
                IOException(
                    "No production build was found. Run the project's build command first — " +
                        "the export only contains files that really exist.",
                ),
            )
        return exportZip(prod, dest)
    }

    /** §41 — exactly the paths the user ticked. */
    fun exportSelection(sourceDir: File, dest: File, paths: Collection<String>): Result<Unit> {
        if (paths.isEmpty()) return Result.failure(IOException("No files were selected."))
        val wanted = paths.toSet()
        return exportZipFiltered(sourceDir, dest) { rel -> rel in wanted || wanted.any { rel.startsWith("$it/") } }
    }


    /**
     * Extracts [zipFile] into [destDir]. If every entry sits under one common top-level directory,
     * that root is stripped so the result lands at [destDir] itself.
     */
    fun importZip(destDir: File, zipFile: File): Result<Unit> = runCatching {
        require(zipFile.isFile) { "Archive not found." }
        destDir.mkdirs()
        val entries = readEntries(zipFile)

        val relPaths = entries.filter { !it.isDirectory }.map { safeRel(it.name) }
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
