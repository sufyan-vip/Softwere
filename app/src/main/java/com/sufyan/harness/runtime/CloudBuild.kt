package com.sufyan.harness.runtime

import java.io.File
import java.util.zip.ZipInputStream

/**
 * §34-§39 — building an APK when the phone itself cannot.
 *
 * Stock Android has no JDK, no Gradle and no Android SDK, and Google publishes `aapt2` and the rest
 * of build-tools only for x86_64 desktops — so a real Gradle build cannot run on an ARM phone. That
 * is not a limitation this app can code its way around, so instead of pretending, it uses the one
 * machine that *does* have a toolchain: GitHub Actions. The project is pushed, a workflow is
 * dispatched, the run is followed step by step, and the APK it produces is downloaded back to the
 * phone, verified with [ApkVerifier] and handed to the system installer.
 *
 * Everything in this file is pure: no network, no Android. It is what the unit tests cover.
 */
object CloudBuild {

    /** Where the workflow lives inside the *user's* project repository. */
    const val WORKFLOW_PATH = ".github/workflows/sufyan-harness-build.yml"

    const val WORKFLOW_FILE = "sufyan-harness-build.yml"

    /** Second workflow: runs one shell command in the repository and returns its real output. */
    const val COMMAND_WORKFLOW_PATH = ".github/workflows/sufyan-harness-command.yml"

    const val COMMAND_WORKFLOW_FILE = "sufyan-harness-command.yml"

    const val COMMAND_ARTIFACT = "sufyan-harness-command-log"

    const val COMMAND_LOG_FILE = "command-output.txt"

    /** How long to follow a run before giving up and telling the user to check GitHub. */
    const val TIMEOUT_MS = 30 * 60 * 1000L

    /** Gap between status polls. GitHub's REST limit is 5 000/hour, so 6 s is comfortably safe. */
    const val POLL_MS = 6_000L

    /**
     * The workflow the app installs into the project. Deliberately small and readable — the user
     * owns this file and must be able to audit it. `workflow_dispatch` is what lets the phone start
     * a build; the `variant` input decides debug or release.
     */
    fun workflowYaml(): String = """
        # Added by Sufyan Harness so the app can build this project's APK on GitHub's machines.
        # You can edit or delete it; the app only re-adds it if you ask for a cloud build again.
        name: Sufyan Harness cloud build

        on:
          workflow_dispatch:
            inputs:
              variant:
                description: Which APK to build
                type: choice
                default: debug
                options: [debug, release]

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - name: Set up JDK 17
                uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '17'

              - name: Set up Android SDK
                uses: android-actions/setup-android@v3

              - name: Make the wrapper executable
                run: chmod +x ./gradlew || true

              - name: Build ${'$'}{{ inputs.variant }} APK
                run: ./gradlew assemble${'$'}{{ inputs.variant == 'release' && 'Release' || 'Debug' }} --stacktrace

              - name: Verify the APK is real
                run: |
                  set -e
                  apk=${'$'}(find . -path '*/outputs/apk/*' -name '*.apk' | head -1)
                  test -n "${'$'}apk" || { echo "No APK was produced"; exit 1; }
                  test -s "${'$'}apk" || { echo "${'$'}apk is empty"; exit 1; }
                  unzip -l "${'$'}apk" | grep -q AndroidManifest.xml
                  unzip -l "${'$'}apk" | grep -q classes.dex
                  echo "Verified ${'$'}apk"

              - name: Upload the APK
                uses: actions/upload-artifact@v4
                with:
                  name: sufyan-harness-${'$'}{{ inputs.variant }}-apk
                  path: '**/outputs/apk/**/*.apk'
                  if-no-files-found: error
    """.trimIndent() + "\n"

    /**
     * The command workflow. Android cannot install `apt`/`npm`/`pip` packages — an app targeting a
     * modern API level may not execute binaries from its own data directory, and this build ships no
     * PRoot loader — so instead of pretending, the command runs on a real Ubuntu machine that has
     * node, npm, python, java, git and `sudo apt-get`, inside the user's own repository.
     *
     * The command is passed through an environment variable, never interpolated into the shell
     * line, so the workflow file itself cannot be broken (or exploited) by an odd command string.
     */
    fun commandWorkflowYaml(): String = """
        # Added by Sufyan Harness so the app can run commands on a real Linux machine.
        # It runs exactly the command you type in the app's terminal, in this repository.
        name: Sufyan Harness cloud command

        on:
          workflow_dispatch:
            inputs:
              command:
                description: Shell command to run in the repository
                required: true
                type: string

        jobs:
          run:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-node@v4
                with:
                  node-version: '20'

              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '17'

              - name: Run the command
                env:
                  HARNESS_COMMAND: ${'$'}{{ inputs.command }}
                run: |
                  set -o pipefail
                  echo "${'$'} ${'$'}HARNESS_COMMAND" | tee -a $COMMAND_LOG_FILE
                  bash -lc "${'$'}HARNESS_COMMAND" 2>&1 | tee -a $COMMAND_LOG_FILE
                  code=${'$'}?
                  echo "" | tee -a $COMMAND_LOG_FILE
                  echo "exit code: ${'$'}code" | tee -a $COMMAND_LOG_FILE
                  exit ${'$'}code

              - name: Upload the output
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: $COMMAND_ARTIFACT
                  path: $COMMAND_LOG_FILE
                  if-no-files-found: warn
    """.trimIndent() + "\n"

    /** Artifact name the workflow uploads for [variant]. */
    fun artifactName(variant: String): String = "sufyan-harness-${normalise(variant)}-apk"

    fun normalise(variant: String): String = if (variant.equals("release", true)) "release" else "debug"

    /**
     * Chooses which uploaded artifact holds the APK. The exact name is preferred; otherwise
     * anything that mentions the variant, then anything that mentions "apk" — a workflow the user
     * wrote themselves is still usable as long as it uploads an APK somewhere.
     */
    fun pickArtifact(names: List<String>, variant: String): String? {
        val wanted = artifactName(variant)
        return names.firstOrNull { it == wanted }
            ?: names.firstOrNull { it.contains(normalise(variant), true) && it.contains("apk", true) }
            ?: names.firstOrNull { it.contains("apk", true) }
    }

    /** What a run's `status`/`conclusion` pair means, in words a user can act on. */
    sealed interface Progress {
        data object Queued : Progress
        data object Running : Progress
        data object Succeeded : Progress
        data class Ended(val conclusion: String, val explanation: String) : Progress
    }

    fun progressOf(status: String?, conclusion: String?): Progress = when {
        status == "completed" && conclusion == "success" -> Progress.Succeeded
        status == "completed" -> Progress.Ended(
            conclusion ?: "unknown",
            when (conclusion) {
                "failure" -> "The build failed on GitHub. Open the run to see which step broke."
                "cancelled" -> "The run was cancelled before it finished."
                "timed_out" -> "GitHub stopped the run because it took too long."
                "action_required" -> "GitHub is waiting for a manual approval on this run."
                "startup_failure" -> "The workflow file itself could not start — check its syntax."
                else -> "The run ended as \"$conclusion\"."
            },
        )
        status == "in_progress" -> Progress.Running
        else -> Progress.Queued
    }

    /**
     * Unpacks the artifact zip and returns the APK inside it. GitHub always wraps artifacts in a
     * zip, so this is the last step before the file can be verified and installed.
     *
     * Entries are resolved against [destDir] and rejected if they escape it (zip-slip), and the
     * extraction stops at [maxBytes] so a hostile or corrupt archive cannot fill the device.
     */
    fun extractApk(zip: File, destDir: File, maxBytes: Long = 300L * 1024 * 1024): Result<File> =
        extractFirst(zip, destDir, ".apk", maxBytes)

    /**
     * Reads every text entry of a run's log bundle into one string, newest content last.
     * Capped, because a Gradle log can be tens of megabytes and this runs on a phone.
     */
    fun readLogBundle(zip: File, maxBytes: Long = 6L * 1024 * 1024): Result<String> = runCatching {
        val out = StringBuilder()
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".txt", true)) {
                    zis.closeEntry(); continue
                }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = zis.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) break
                    out.append(String(buffer, 0, read))
                }
                zis.closeEntry()
                if (total > maxBytes) break
            }
        }
        if (out.isEmpty()) throw java.io.IOException("The log bundle contained no readable text.")
        out.toString()
    }

    private val TIMESTAMP = Regex("""^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?""")

    /**
     * Picks the lines that actually explain a failed build.
     *
     * A CI log is mostly noise; what matters is the compiler's own `e:` lines, Gradle's `FAILURE`
     * block, test failures and `Caused by`. Timestamps are stripped so the result reads like the
     * compiler output it is, and the list is capped so it can be shown on a phone and handed to the
     * model without eating the whole context.
     */
    fun buildErrors(log: String, limit: Int = 40): List<String> {
        val interesting = Regex(
            """(^|\s)(e:|error:|ERROR:|FAILURE:|FAILED|Caused by:|Execution failed for task|Unresolved reference|Compilation error|\* What went wrong)""",
        )
        val noise = Regex("""^\s*at [\w.]+""")
        val seen = LinkedHashSet<String>()
        for (raw in log.lineSequence()) {
            val line = raw.replace(TIMESTAMP, "").trimEnd()
            if (line.isBlank() || noise.containsMatchIn(line)) continue
            if (!interesting.containsMatchIn(line)) continue
            seen += line.trim().take(400)
            if (seen.size >= limit) break
        }
        return seen.toList()
    }

    /** Same, for the command log the cloud terminal brings back. */
    fun extractLog(zip: File, destDir: File): Result<File> =
        extractFirst(zip, destDir, ".txt", 8L * 1024 * 1024)

    private fun extractFirst(zip: File, destDir: File, suffix: String, maxBytes: Long): Result<File> = runCatching {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalPath
        var found: File? = null
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || !name.endsWith(suffix, true)) {
                    zis.closeEntry(); continue
                }
                val out = File(destDir, name)
                if (!out.canonicalPath.startsWith(canonicalDest)) {
                    throw SecurityException("The archive tried to write outside the download directory.")
                }
                out.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw java.io.IOException("The artifact is larger than ${maxBytes / 1024 / 1024} MB.")
                        output.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                found = out
                break
            }
        }
        found ?: throw java.io.IOException("The artifact contained no $suffix file.")
    }
}

/** One GitHub Actions run, reduced to what the build screen shows. */
data class CloudRun(
    val id: Long,
    val status: String?,
    val conclusion: String?,
    val htmlUrl: String,
    val createdAtMs: Long,
)

/** One step of the run, so progress is real rather than a spinner. */
data class CloudStep(val name: String, val status: String?, val conclusion: String?)

data class CloudArtifact(val id: Long, val name: String, val sizeBytes: Long)

/** One cloud command: what was asked, what really came back. */
data class CloudCommandState(
    val running: Boolean = false,
    val command: String = "",
    val phase: String = "",
    val output: List<String> = emptyList(),
    val exitCode: Int? = null,
    val runUrl: String? = null,
    val error: String? = null,
)

/** Everything the Build screen needs to render the cloud path honestly. */
data class CloudBuildState(
    val running: Boolean = false,
    val phase: String = "",
    val steps: List<CloudStep> = emptyList(),
    val runUrl: String? = null,
    val error: String? = null,
    val lastResult: String? = null,
    /** The real compiler/Gradle lines from a failed run — empty until they have been fetched. */
    val errorLines: List<String> = emptyList(),
    val fetchingLogs: Boolean = false,
    val runId: Long? = null,
)
