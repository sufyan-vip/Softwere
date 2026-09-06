package com.sufyan.harness.runtime

import java.io.File

/** One diagnostic line in the runtime health report. */
data class RuntimeCheck(val label: String, val ok: Boolean, val detail: String)

data class RuntimeDiagnosis(
    val checks: List<RuntimeCheck>,
    /** Repairs that can actually be performed on this device, in the order they should be tried. */
    val repairs: List<RuntimeRepairAction>,
) {
    val healthy: Boolean get() = checks.all { it.ok }
    val blocker: RuntimeCheck? get() = checks.firstOrNull { !it.ok }
}

enum class RuntimeRepairAction(val label: String, val blurb: String) {
    FixPermissions("Repair file permissions", "Restores the execute bit on the rootfs binaries."),
    RebuildPath("Rebuild PATH and profile", "Rewrites /etc/profile and the launcher environment inside the rootfs."),
    ClearCache("Clear runtime cache", "Deletes partial downloads and temporary files. Project files are untouched."),
    Reinstall("Reinstall runtime", "Removes the rootfs and downloads it again."),
}

/**
 * §28 — the runtime repair system.
 *
 * Diagnostics execute real checks (file layout, execute bit, a `uname` and a shell round-trip inside
 * the userspace) and the repair actions perform real filesystem work. When PRoot cannot run on this
 * build at all, that is reported as the blocking check rather than being papered over — and the
 * repairs that would be pointless are not offered (§3, §4).
 */
class RuntimeRepair(private val linux: LinuxRuntime) {

    suspend fun diagnose(): RuntimeDiagnosis {
        val checks = mutableListOf<RuntimeCheck>()

        val loader = linux.prootAvailable()
        checks += RuntimeCheck(
            "PRoot loader",
            loader,
            if (loader) {
                "Native loader present and executable."
            } else {
                "This build ships no libproot.so, so no Linux userspace can be started. " +
                    "The Android shell in the Terminal is unaffected."
            },
        )

        val rootfs = linux.rootfsPresent()
        checks += RuntimeCheck(
            "Filesystem",
            rootfs,
            if (rootfs) linux.rootfsDir.absolutePath else "No rootfs installed at ${linux.rootfsDir.absolutePath}.",
        )

        if (loader && rootfs) {
            val shell = File(linux.rootfsDir, "bin/sh")
            val execBit = shell.exists() && shell.canExecute()
            checks += RuntimeCheck(
                "Shell binary",
                execBit,
                if (execBit) "${shell.absolutePath} is executable." else "bin/sh is missing or lost its execute bit.",
            )

            val uname = linux.exec("uname -a", linux.baseDir, 15_000)
            checks += RuntimeCheck(
                "Userspace",
                uname.ok,
                if (uname.ok) uname.stdout.lineSequence().firstOrNull().orEmpty() else uname.stderr.take(200),
            )

            val path = linux.exec("echo \$PATH", linux.baseDir, 15_000)
            val pathOk = path.ok && path.stdout.contains("/usr/bin")
            checks += RuntimeCheck(
                "PATH",
                pathOk,
                if (path.ok) path.stdout.trim() else "PATH could not be read from inside the runtime.",
            )
        }

        val free = runCatching { linux.baseDir.usableSpace }.getOrDefault(0L)
        checks += RuntimeCheck(
            "Free space",
            free > 512L * 1024 * 1024,
            "${free / (1024 * 1024)} MB available (a base rootfs needs roughly 500 MB).",
        )

        val repairs = buildList {
            if (loader && rootfs) {
                add(RuntimeRepairAction.FixPermissions)
                add(RuntimeRepairAction.RebuildPath)
            }
            add(RuntimeRepairAction.ClearCache)
            if (loader) add(RuntimeRepairAction.Reinstall)
        }
        return RuntimeDiagnosis(checks, repairs)
    }

    /** Performs a repair. The returned message states exactly what was done — including "nothing". */
    suspend fun repair(action: RuntimeRepairAction): Result<String> = runCatching {
        when (action) {
            RuntimeRepairAction.FixPermissions -> {
                if (!linux.rootfsPresent()) throw IllegalStateException("There is no rootfs to repair.")
                var fixed = 0
                listOf("bin", "usr/bin", "sbin", "usr/sbin").forEach { rel ->
                    val dir = File(linux.rootfsDir, rel)
                    if (dir.isDirectory) {
                        dir.listFiles()?.forEach { f ->
                            if (f.isFile && !f.canExecute() && f.setExecutable(true, false)) fixed++
                        }
                    }
                }
                "Restored the execute bit on $fixed file(s)."
            }

            RuntimeRepairAction.RebuildPath -> {
                if (!linux.rootfsPresent()) throw IllegalStateException("There is no rootfs to repair.")
                val etc = File(linux.rootfsDir, "etc").apply { mkdirs() }
                File(etc, "profile").writeText(
                    """
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    export HOME=/root
                    export TERM=xterm-256color
                    export LANG=C.UTF-8
                    """.trimIndent() + "\n",
                )
                "Rewrote /etc/profile with a standard PATH."
            }

            RuntimeRepairAction.ClearCache -> {
                var removed = 0L
                linux.baseDir.listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".tar.gz") || f.name.endsWith(".part"))) {
                        removed += f.length()
                        f.delete()
                    }
                }
                val tmp = File(linux.rootfsDir, "tmp")
                if (tmp.isDirectory) {
                    tmp.listFiles()?.forEach { removed += it.length(); it.deleteRecursively() }
                }
                "Cleared ${removed / (1024 * 1024)} MB of cached downloads and temporary files. " +
                    "No project file was touched."
            }

            RuntimeRepairAction.Reinstall -> {
                linux.uninstall().getOrThrow()
                "Runtime removed. Start the installation again to download a fresh rootfs."
            }
        }
    }
}
