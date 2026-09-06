package com.sufyan.harness.runtime

import java.io.File
import java.util.zip.ZipFile

/**
 * §37 / §39 — APK verification.
 *
 * "Build successful" is only ever shown after this returns [ApkReport.valid] = true, and validity is
 * decided by opening the real file: a zip that actually contains an Android manifest and Dalvik
 * bytecode. Nothing is inferred from the file name.
 *
 * Pure JVM — unit-tested off-device against a zip built by the test itself.
 */
data class ApkReport(
    val valid: Boolean,
    val sizeBytes: Long,
    val hasManifest: Boolean,
    val hasDex: Boolean,
    val signed: Boolean,
    val entryCount: Int,
    val nativeAbis: List<String>,
    val problem: String? = null,
) {
    val summary: String
        get() = if (valid) {
            buildString {
                append("Valid Android package")
                append(" \u00b7 ${sizeBytes / 1024} KB")
                append(" \u00b7 $entryCount entries")
                append(if (signed) " \u00b7 signed" else " \u00b7 unsigned")
                if (nativeAbis.isNotEmpty()) append(" \u00b7 ${nativeAbis.joinToString("/")}")
            }
        } else {
            problem ?: "Not a valid Android package"
        }
}

object ApkVerifier {

    fun verify(file: File): ApkReport {
        if (!file.exists()) {
            return ApkReport(false, 0, false, false, false, 0, emptyList(), "File does not exist: ${file.name}")
        }
        if (file.length() == 0L) {
            return ApkReport(false, 0, false, false, false, 0, emptyList(), "File is empty (0 bytes).")
        }
        return try {
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }
                val hasManifest = names.any { it == "AndroidManifest.xml" }
                val hasDex = names.any { it.matches(Regex("classes\\d*\\.dex")) }
                val signed = names.any {
                    it.startsWith("META-INF/") && (
                        it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") || it.endsWith(".SF")
                        )
                }
                val abis = names.filter { it.startsWith("lib/") }
                    .mapNotNull { it.removePrefix("lib/").substringBefore('/').takeIf { a -> a.isNotEmpty() } }
                    .distinct()
                    .sorted()
                val problem = when {
                    !hasManifest -> "The archive has no AndroidManifest.xml, so it is not an APK."
                    !hasDex -> "The archive has no classes.dex, so it contains no compiled code."
                    else -> null
                }
                ApkReport(
                    valid = hasManifest && hasDex,
                    sizeBytes = file.length(),
                    hasManifest = hasManifest,
                    hasDex = hasDex,
                    signed = signed,
                    entryCount = names.size,
                    nativeAbis = abis,
                    problem = problem,
                )
            }
        } catch (e: Exception) {
            ApkReport(
                false, file.length(), false, false, false, 0, emptyList(),
                "The file could not be opened as a zip archive: ${e.message ?: e::class.java.simpleName}",
            )
        }
    }
}
