plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}

// TEMPORARY CI DIAGNOSTICS
if (System.getenv("GITHUB_ACTIONS") == "true") {
    gradle.addBuildListener(object : org.gradle.BuildAdapter() {
        override fun buildFinished(result: org.gradle.BuildResult) {
            val failure = result.failure ?: return
            val text = generateSequence<Throwable>(failure) { it.cause }
                .joinToString("\n") { it.message ?: it.toString() }
            val errors = text.lineSequence().filter { it.startsWith("e: ") || it.contains(": error:") }
                .map { it.trim() }.distinct().toMutableList()
            if (errors.isEmpty()) {
                val logs = file("${System.getProperty("user.home")}/.gradle/daemon").walkTopDown()
                    .filter { it.name.endsWith(".out.log") }.toList()
                for (log in logs) {
                    val lines = runCatching { log.readLines() }.getOrDefault(emptyList())
                    errors += lines.asReversed().asSequence().map { it.trim() }
                        .filter { it.startsWith("e: ") }.distinct().take(8)
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
