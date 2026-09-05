plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}

// TEMPORARY CI DIAGNOSTICS — delete once ci/android-workflow.yml is activated under
// .github/workflows/ (that pipeline already turns "e:" lines into annotations).
// The runner log is not readable from the environment maintaining this repo, so on a
// compile failure the Kotlin diagnostics are re-emitted as workflow commands, which
// GitHub shows as annotations on the run page.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    gradle.addBuildListener(object : org.gradle.BuildAdapter() {
        override fun buildFinished(result: org.gradle.BuildResult) {
            val failure = result.failure ?: return
            val text = generateSequence<Throwable>(failure) { it.cause }
                .joinToString("\n") { it.message ?: it.toString() }
            val errors = text.lineSequence()
                .filter { it.startsWith("e: ") || it.contains(": error:") }
                .map { it.trim() }
                .distinct()
                .toMutableList()
            if (errors.isEmpty()) {
                val home = System.getProperty("user.home")
                val logs = file("$home/.gradle/daemon").walkTopDown()
                    .filter { it.name.endsWith(".out.log") }
                    .toList()
                for (log in logs) {
                    val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
                    errors += lines.asReversed().asSequence()
                        .map { it.trim() }
                        .filter { it.startsWith("e: ") }
                        .distinct()
                        .take(8)
                    if (errors.isNotEmpty()) break
                }
            }
            if (errors.isEmpty()) errors += text.take(1200)
            for (line in errors.take(8)) {
                println("::error::" + line.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"))
            }
        }
    })
}
