package com.sufyan.harness.ai

/**
 * §46 — the smart command planner.
 *
 * Nothing in the app may run `npm run build` because a project "feels" like a web project. Every
 * command offered here is backed by [PlannedCommand.evidence]: a file that exists, or a script that
 * is actually declared in package.json. If there is no evidence, no command is produced and the UI
 * says so instead of guessing (§3).
 *
 * Pure Kotlin — unit-tested off-device.
 */
data class PlannedCommand(
    /** "install" | "dev" | "build" | "test" | "run" */
    val kind: String,
    val command: String,
    /** The concrete file / script that proves this command exists. */
    val evidence: String,
)

data class ProjectPlan(
    val stack: String,
    val commands: List<PlannedCommand>,
    val notes: List<String> = emptyList(),
) {
    fun of(kind: String): PlannedCommand? = commands.firstOrNull { it.kind == kind }
    val isEmpty: Boolean get() = commands.isEmpty()
}

object CommandPlanner {

    /**
     * @param rootEntries names of the entries in the project root (files and directories)
     * @param packageJson raw contents of package.json when it exists
     */
    fun plan(rootEntries: Set<String>, packageJson: String? = null): ProjectPlan {
        val commands = mutableListOf<PlannedCommand>()
        val notes = mutableListOf<String>()
        var stack = "Unknown"

        val hasNodeManifest = "package.json" in rootEntries && packageJson != null
        if (hasNodeManifest) {
            stack = "Node"
            val scripts = scriptsOf(packageJson!!)
            val installer = when {
                "pnpm-lock.yaml" in rootEntries -> "pnpm"
                "yarn.lock" in rootEntries -> "yarn"
                else -> "npm"
            }
            val lock = when (installer) {
                "pnpm" -> "pnpm-lock.yaml"
                "yarn" -> "yarn.lock"
                else -> if ("package-lock.json" in rootEntries) "package-lock.json" else "package.json"
            }
            commands += PlannedCommand("install", if (installer == "npm") "npm install" else "$installer install", lock)

            fun script(name: String, kind: String) {
                if (scripts.contains(name)) {
                    val run = when {
                        installer == "npm" && name == "start" -> "npm start"
                        installer == "npm" && name == "test" -> "npm test"
                        installer == "npm" -> "npm run $name"
                        else -> "$installer run $name"
                    }
                    commands += PlannedCommand(kind, run, "package.json scripts.$name")
                }
            }
            script("dev", "dev")
            script("start", if (scripts.contains("dev")) "run" else "dev")
            script("build", "build")
            script("test", "test")

            if (scripts.isEmpty()) notes += "package.json declares no scripts, so there is no build or dev command to run."
            if ("node_modules" !in rootEntries) notes += "Dependencies are not installed yet (no node_modules directory)."
            if (packageJson.contains("\"vite\"")) stack = "Vite / Node"
            else if (packageJson.contains("\"next\"")) stack = "Next.js"
            else if (packageJson.contains("\"react\"")) stack = "React / Node"
        }

        val gradleFiles = rootEntries.filter { it.startsWith("build.gradle") || it.startsWith("settings.gradle") }
        if (gradleFiles.isNotEmpty()) {
            stack = "Gradle / Android"
            val wrapper = "gradlew" in rootEntries
            val gradle = if (wrapper) "./gradlew" else "gradle"
            commands += PlannedCommand("build", "$gradle assembleDebug", gradleFiles.first())
            commands += PlannedCommand("test", "$gradle testDebugUnitTest", gradleFiles.first())
            if (!wrapper) notes += "No Gradle wrapper in this project — a system Gradle installation is required."
        }

        if ("requirements.txt" in rootEntries || "pyproject.toml" in rootEntries) {
            stack = "Python"
            if ("requirements.txt" in rootEntries) {
                commands += PlannedCommand("install", "pip install -r requirements.txt", "requirements.txt")
            }
            val entry = listOf("main.py", "app.py", "manage.py").firstOrNull { it in rootEntries }
            if (entry != null) commands += PlannedCommand("run", "python3 $entry", entry)
        }

        if (commands.none { it.kind == "dev" } && "index.html" in rootEntries) {
            if (stack == "Unknown") stack = "Static site"
            commands += PlannedCommand("dev", "(built-in static server)", "index.html")
        }

        if (commands.isEmpty()) {
            notes += "No build system was detected in the project root, so no command can be offered yet."
        }
        return ProjectPlan(stack, commands.distinctBy { it.kind to it.command }, notes)
    }

    /**
     * Extracts the script names from the `"scripts"` object of a package.json without a JSON parser
     * dependency, tolerating whitespace and nesting. Returns an empty set when the block is absent.
     */
    fun scriptsOf(packageJson: String): Set<String> {
        val idx = packageJson.indexOf("\"scripts\"")
        if (idx < 0) return emptySet()
        val open = packageJson.indexOf('{', idx)
        if (open < 0) return emptySet()
        var depth = 0
        var end = -1
        for (i in open until packageJson.length) {
            when (packageJson[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return emptySet()
        val block = packageJson.substring(open + 1, end)
        return Regex("\"([^\"]+)\"\\s*:").findAll(block).map { it.groupValues[1] }.toSet()
    }
}
