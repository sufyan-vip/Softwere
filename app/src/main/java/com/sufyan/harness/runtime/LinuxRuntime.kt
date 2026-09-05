package com.sufyan.harness.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

enum class RuntimeState { NotInstalled, Downloading, Extracting, Installed, Failed }

data class RuntimeStatus(
    val state: RuntimeState = RuntimeState.NotInstalled,
    val progress: Float = 0f,
    val message: String = "Linux runtime is not installed.",
)

/**
 * Private Linux userspace executed through PRoot.
 *
 * Honesty contract: this class NEVER reports Installed unless the proot binary
 * and rootfs exist on disk AND a probe command actually executes inside them.
 * Because Android forbids exec from app-private storage on API 29+ for
 * downloaded binaries in some configurations, the probe is the source of truth.
 */
class LinuxRuntime(private val context: Context) {

    val baseDir: File by lazy { File(context.filesDir, "linux").apply { mkdirs() } }
    val rootfsDir: File get() = File(baseDir, "rootfs")
    private val prootBin: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val markerFile: File get() = File(baseDir, ".installed")

    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status

    /** True only if the native proot loader shipped with this APK exists. */
    fun prootAvailable(): Boolean = prootBin.exists() && prootBin.canExecute()

    fun rootfsPresent(): Boolean = File(rootfsDir, "bin").isDirectory || File(rootfsDir, "usr/bin").isDirectory

    suspend fun refresh() {
        _status.value = when {
            !prootAvailable() -> RuntimeStatus(
                RuntimeState.NotInstalled, 0f,
                "This build does not ship a PRoot loader, so the Linux runtime cannot start. " +
                    "The Android shell is still fully available in the Terminal.",
            )
            !rootfsPresent() -> RuntimeStatus(RuntimeState.NotInstalled, 0f, "Linux rootfs is not installed.")
            else -> {
                val probe = exec("uname -a", baseDir, 15_000)
                if (probe.ok) RuntimeStatus(RuntimeState.Installed, 1f, probe.stdout.trim())
                else RuntimeStatus(RuntimeState.Failed, 0f, "Runtime present but not executable: ${probe.stderr.take(200)}")
            }
        }
    }

    /**
     * Downloads and extracts a rootfs tarball. Resumable: a partial download is
     * kept and continued rather than silently restarted.
     */
    suspend fun install(rootfsUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!prootAvailable()) {
                throw IOException(
                    "No PRoot loader in this build. Installing a rootfs would not be usable, so installation is refused.",
                )
            }
            val archive = File(baseDir, "rootfs.tar.gz")
            _status.value = RuntimeStatus(RuntimeState.Downloading, 0f, "Contacting server...")

            val existing = if (archive.exists()) archive.length() else 0L
            val conn = (URL(rootfsUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()
            val resumed = conn.responseCode == 206
            if (conn.responseCode !in listOf(200, 206)) {
                throw IOException("Download failed with HTTP ${conn.responseCode}.")
            }
            val total = conn.contentLengthLong + if (resumed) existing else 0L

            conn.inputStream.use { input ->
                java.io.FileOutputStream(archive, resumed).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = if (resumed) existing else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            _status.value = RuntimeStatus(
                                RuntimeState.Downloading, written.toFloat() / total,
                                "Downloading rootfs ${written / 1_048_576} MB / ${total / 1_048_576} MB",
                            )
                        }
                    }
                }
            }

            _status.value = RuntimeStatus(RuntimeState.Extracting, 1f, "Extracting rootfs...")
            rootfsDir.mkdirs()
            extractTarGz(archive, rootfsDir)
            archive.delete()
            markerFile.writeText(System.currentTimeMillis().toString())
            refresh()
            if (_status.value.state != RuntimeState.Installed) {
                throw IOException(_status.value.message)
            }
        }.onFailure { e ->
            _status.value = RuntimeStatus(
                RuntimeState.Failed, 0f,
                e.message ?: "Runtime installation was interrupted. Downloaded files are kept — you can retry.",
            )
        }
    }

    private fun extractTarGz(archive: File, target: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (gz.readNBytes(header, 0, 512) < 512) break
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100).trimEnd('\u0000', ' ')
                if (name.isEmpty()) break
                val sizeField = String(header, 124, 12).trim().trimEnd('\u0000', ' ')
                val size = sizeField.toLongOrNull(8) ?: 0L
                val type = header[156].toInt().toChar()
                val out = File(target, name)
                if (!out.canonicalPath.startsWith(target.canonicalPath)) {
                    throw IOException("Refusing to extract entry outside rootfs: $name")
                }
                when (type) {
                    '5' -> out.mkdirs()
                    '0', '\u0000' -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os ->
                            var remaining = size
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n <= 0) break
                                os.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        out.setExecutable(true, false)
                    }
                    else -> gz.skip(size)
                }
                val pad = (512 - (size % 512)) % 512
                if (pad > 0) gz.skip(pad)
            }
        }
    }

    fun uninstall(): Result<Unit> = runCatching {
        rootfsDir.deleteRecursively()
        markerFile.delete()
        _status.value = RuntimeStatus(RuntimeState.NotInstalled, 0f, "Linux rootfs removed.")
    }

    /** Wraps a command so it runs inside the Linux rootfs when available. */
    fun wrap(command: String, cwdInside: String = "/root"): List<String>? {
        if (!prootAvailable() || !rootfsPresent()) return null
        return listOf(
            prootBin.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-0",
            "-w", cwdInside,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "/bin/sh", "-c", command,
        )
    }

    suspend fun exec(command: String, workingDir: File, timeoutMs: Long = 120_000): CommandResult =
        withContext(Dispatchers.IO) {
            val argv = wrap(command) ?: return@withContext CommandResult(
                -1, "", "Linux runtime is not installed.",
            )
            try {
                val p = ProcessBuilder(argv).directory(workingDir).start()
                val out = StringBuilder(); val err = StringBuilder()
                val t1 = Thread { p.inputStream.bufferedReader().useLines { s -> s.forEach { out.appendLine(it) } } }
                val t2 = Thread { p.errorStream.bufferedReader().useLines { s -> s.forEach { err.appendLine(it) } } }
                t1.start(); t2.start()
                if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    return@withContext CommandResult(-1, out.toString(), "Timed out.")
                }
                t1.join(2000); t2.join(2000)
                CommandResult(p.exitValue(), out.toString().trimEnd(), err.toString().trimEnd())
            } catch (e: Exception) {
                CommandResult(-1, "", e.message ?: "Execution failed.")
            }
        }
}
