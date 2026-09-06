package com.sufyan.harness.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One requirement of an Android build, with the probe result that decided it. */
data class BuildRequirement(
    val id: String,
    val label: String,
    val available: Boolean,
    val detail: String,
    val remedy: String? = null,
)

data class BuildEnvironment(
    val requirements: List<BuildRequirement>,
    val ready: Boolean,
    val gradleCommand: String?,
) {
    val missing: List<BuildRequirement> get() = requirements.filterNot { it.available }
    val statusLine: String get() = if (ready) "Ready" else "${missing.size} requirement(s) missing"
}

/** A built (or imported) APK that really exists on disk. */
data class BuildArtifact(
    val file: File,
    val variant: String,
    val report: ApkReport,
    val createdAt: Long,
) {
    val name: String get() = file.name
    val sizeLabel: String get() = "%.1f MB".format(file.length() / 1024.0 / 1024.0)
}

sealed interface BuildOutcome {
    data class Success(val artifact: BuildArtifact, val log: List<String>) : BuildOutcome
    /** The build could not even start; [requirement] says which piece is missing. */
    data class Blocked(val requirement: BuildRequirement, val explanation: String) : BuildOutcome
    data class Failed(val exitCode: Int, val log: List<String>, val diagnosis: Diagnosis) : BuildOutcome
}

/**
 * §34-§39 — the Android build pipeline.
 *
 * Every claim here is evidence-based:
 *  * the environment report comes from running `java -version`, `gradle -v`, and looking for a real
 *    SDK directory — never from assuming what a phone has,
 *  * a build only reports success when Gradle exits 0 **and** [ApkVerifier] confirms the produced
 *    file is a real APK,
 *  * when the toolchain is absent the build is *blocked* with an explanation and the alternatives
 *    that genuinely work (push to GitHub and build in CI, or export the project). It never fakes an
 *    APK (§3).
 */
class AndroidBuildService(private val context: Context, private val linux: LinuxRuntime) {

    /** Detects the build toolchain by executing the real probes. */
    suspend fun detect(projectDir: File): BuildEnvironment = withContext(Dispatchers.IO) {
        val requirements = mutableListOf<BuildRequirement>()

        val isAndroidProject = File(projectDir, "settings.gradle.kts").exists() ||
            File(projectDir, "settings.gradle").exists() ||
            File(projectDir, "build.gradle.kts").exists() ||
            File(projectDir, "build.gradle").exists()
        requirements += BuildRequirement(
            "project", "Gradle project", isAndroidProject,
            if (isAndroidProject) "settings.gradle / build.gradle found." else "No Gradle build files in this project.",
            remedy = if (isAndroidProject) null else "Create the project as an Android App, or add Gradle build files.",
        )

        val manifest = File(projectDir, "app/src/main/AndroidManifest.xml").exists()
        requirements += BuildRequirement(
            "manifest", "Android manifest", manifest,
            if (manifest) "app/src/main/AndroidManifest.xml found." else "app/src/main/AndroidManifest.xml is missing.",
            remedy = if (manifest) null else "An Android module needs a manifest at app/src/main/AndroidManifest.xml.",
        )

        val java = ShellSession.exec("java -version", projectDir, 20_000)
        val javaOk = java.ok
        requirements += BuildRequirement(
            "jdk", "JDK 17", javaOk,
            if (javaOk) (java.stderr + java.stdout).lineSequence().firstOrNull()?.trim().orEmpty()
            else "No `java` on PATH.",
            remedy = if (javaOk) null else
                "Android does not ship a JDK. Install one inside the Linux runtime, or build this project on a computer / in CI.",
        )

        val wrapper = File(projectDir, "gradlew").exists()
        val gradleProbe = ShellSession.exec("gradle --version", projectDir, 30_000)
        val gradleOk = gradleProbe.ok || (wrapper && javaOk)
        requirements += BuildRequirement(
            "gradle", "Gradle", gradleOk,
            when {
                gradleProbe.ok -> gradleProbe.stdout.lineSequence().firstOrNull { it.startsWith("Gradle") }?.trim()
                    ?: "gradle on PATH"
                wrapper && javaOk -> "Using the project's Gradle wrapper (./gradlew)."
                wrapper -> "Wrapper present but it needs a JDK to run."
                else -> "No `gradle` on PATH and no Gradle wrapper in the project."
            },
            remedy = if (gradleOk) null else "Install Gradle in the Linux runtime, or add a Gradle wrapper to the project.",
        )

        val sdkDir = androidSdkDir()
        requirements += BuildRequirement(
            "sdk", "Android SDK", sdkDir != null,
            sdkDir?.absolutePath ?: "ANDROID_HOME / ANDROID_SDK_ROOT is not set and no SDK directory was found.",
            remedy = if (sdkDir != null) null else
                "The Android SDK (platform 34 + build-tools) is required. It cannot be installed from the Play Store build of this app; build in CI or on a computer instead.",
        )

        val platform = sdkDir?.let { File(it, "platforms").listFiles()?.isNotEmpty() == true } ?: false
        requirements += BuildRequirement(
            "platform", "SDK platform", platform,
            if (platform) "Installed platforms found under ${sdkDir?.name}/platforms." else "No SDK platform installed.",
            remedy = if (platform) null else "Install an SDK platform (android-34) in the SDK directory.",
        )

        val buildTools = sdkDir?.let { File(it, "build-tools").listFiles()?.isNotEmpty() == true } ?: false
        requirements += BuildRequirement(
            "buildtools", "Build tools", buildTools,
            if (buildTools) "Build tools found." else "No build-tools installed.",
            remedy = if (buildTools) null else "Install build-tools 34.0.0 in the SDK directory.",
        )

        val gradleCommand = when {
            wrapper && javaOk -> "./gradlew"
            gradleProbe.ok -> "gradle"
            else -> null
        }
        BuildEnvironment(requirements, requirements.all { it.available }, gradleCommand)
    }

    /** Looks for a real SDK: the env vars first, then the usual on-device locations. */
    private fun androidSdkDir(): File? {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            File(context.filesDir, "android-sdk").absolutePath,
            "/sdcard/android-sdk",
        )
        return candidates.map { File(it) }.firstOrNull { it.isDirectory && File(it, "platforms").exists() }
    }

    /**
     * Runs the real Gradle build. [onLine] receives genuine build output as it arrives.
     * Returns [BuildOutcome.Blocked] rather than attempting a build that cannot possibly work.
     */
    suspend fun build(
        projectDir: File,
        variant: String = "debug",
        onLine: (String) -> Unit,
    ): BuildOutcome = withContext(Dispatchers.IO) {
        val env = detect(projectDir)
        env.missing.firstOrNull()?.let { missing ->
            return@withContext BuildOutcome.Blocked(
                missing,
                buildString {
                    append(missing.detail)
                    missing.remedy?.let { append("\n\n"); append(it) }
                    append(
                        "\n\nWhat does work right now: push this project to GitHub and let a CI runner build " +
                            "the APK, or export the project as a ZIP and build it on a computer. Both keep the " +
                            "code you wrote here.",
                    )
                },
            )
        }

        val task = if (variant.equals("release", true)) "assembleRelease" else "assembleDebug"
        val command = "${env.gradleCommand} $task --no-daemon --console=plain"
        val log = mutableListOf<String>()
        onLine("$ $command")
        log += "$ $command"

        val result = execStreaming(command, projectDir) { line ->
            log += line
            onLine(line)
        }

        if (result != 0) {
            val probe = Probe(
                executable = CommandDiagnostics.executableOf(command),
                onPath = env.gradleCommand != null,
                path = System.getenv("PATH").orEmpty(),
                runtimeLabel = if (linux.rootfsPresent()) "Linux runtime" else "Android shell",
                linuxRuntimeReady = linux.rootfsPresent() && linux.prootAvailable(),
            )
            return@withContext BuildOutcome.Failed(
                result, log,
                CommandDiagnostics.diagnose(command, result, log.takeLast(40).joinToString("\n"), probe = probe),
            )
        }

        val apk = findApk(projectDir, variant)
            ?: return@withContext BuildOutcome.Failed(
                0, log,
                Diagnosis(
                    "Build reported success but produced no APK",
                    "Gradle exited 0, yet no .apk was found under app/build/outputs/apk/$variant.",
                    "Check the build output above for a skipped or filtered task.",
                ),
            )

        val report = ApkVerifier.verify(apk)
        if (!report.valid) {
            return@withContext BuildOutcome.Failed(
                0, log,
                Diagnosis(
                    "The produced file is not a valid APK",
                    report.problem ?: "Verification failed.",
                    "Delete app/build and build again.",
                ),
            )
        }
        onLine("APK verified: ${report.summary}")
        BuildOutcome.Success(BuildArtifact(apk, variant, report, apk.lastModified()), log)
    }

    private fun execStreaming(command: String, dir: File, onLine: (String) -> Unit): Int = try {
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(true)
        pb.environment()["HOME"] = dir.absolutePath
        androidSdkDir()?.let { pb.environment()["ANDROID_HOME"] = it.absolutePath }
        val process = pb.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        process.waitFor()
    } catch (e: Exception) {
        onLine(e.message ?: "The build process could not be started.")
        -1
    }

    fun findApk(projectDir: File, variant: String): File? =
        File(projectDir, "app/build/outputs/apk/$variant").listFiles()
            ?.filter { it.extension == "apk" }
            ?.maxByOrNull { it.lastModified() }
            ?: File(projectDir, "build/outputs/apk/$variant").listFiles()
                ?.filter { it.extension == "apk" }
                ?.maxByOrNull { it.lastModified() }

    /** §39 — every APK in the project, verified on the spot. */
    fun artifacts(projectDir: File): List<BuildArtifact> {
        val outputs = File(projectDir, "app/build/outputs/apk")
        val dirs = listOfNotNull(outputs.takeIf { it.isDirectory }, File(projectDir, "build/outputs/apk").takeIf { it.isDirectory })
        return dirs.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        }.map { file ->
            BuildArtifact(file, file.parentFile?.name ?: "unknown", ApkVerifier.verify(file), file.lastModified())
        }.sortedByDescending { it.createdAt }
    }

    fun delete(artifact: BuildArtifact): Boolean = artifact.file.delete()

    /**
     * §37 — hands the APK to Android's package installer. Installation itself is performed by the
     * system, which is the only supported path; this returns the intent result honestly and never
     * claims the app was installed.
     */
    fun installIntent(artifact: BuildArtifact): Result<Intent> = runCatching {
        require(artifact.report.valid) { "This file is not a valid APK, so it cannot be installed." }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            artifact.file,
        )
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** True when the OS will let this app ask to install packages (API 26+ requires the permission). */
    fun canRequestInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else true

    fun shareIntent(artifact: BuildArtifact): Result<Intent> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", artifact.file)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
