package com.sufyan.harness.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * §11 of the V3 spec — what the user said they are building. This is stored in the project
 * index (Project.type) rather than being guessed from the file list, so the rest of the app
 * (scaffold, preview, build, toolchain hints, agent prompts) can behave differently per type.
 *
 * Every field that pretends to be a "default" (port, dev/run/build command, required tools,
 * language) is metadata the rest of the app genuinely consumes — it is not a label. See
 * docs/V3-PROGRESS.md for the phase-2 audit.
 */
enum class ProjectType(
    val id: String,
    val label: String,
    val blurb: String,
    val template: Template,
    /** False when the pipeline this type needs is not implemented yet (see docs/V3-PROGRESS.md). */
    val canCreate: Boolean = true,
    val languages: String = "",
    /** Default local port the dev/preview server binds to for this type. */
    val defaultPort: Int,
    /** Default dev-server command (process preview), or null when the built-in static server is right. */
    val devCommand: String?,
    /** Command to run the app in its normal mode. */
    val runCommand: String?,
    /** Command that produces a buildable/installable artifact, or null when there is none. */
    val buildCommand: String?,
    /** Tool ids from [com.sufyan.harness.runtime.Toolchains.CORE] this type needs. */
    val requiredTools: List<String> = emptyList(),
) {
    AndroidApp(
        id = "ANDROID_APP", label = "Android App", blurb = "Kotlin + Gradle \u2192 APK",
        template = Template.Android,
        languages = "Kotlin \u2022 Gradle",
        defaultPort = 8080, devCommand = null, runCommand = null, buildCommand = "./gradlew assembleDebug",
        requiredTools = listOf("java", "gradle"),
    ),
    Website(
        id = "WEBSITE", label = "Website", blurb = "HTML / CSS / JS",
        template = Template.Web,
        languages = "HTML • CSS • JS",
        defaultPort = 8080, devCommand = null, runCommand = null, buildCommand = null,
        requiredTools = listOf("sh"),
    ),
    WebApp(
        id = "WEB_APP", label = "Web App", blurb = "React / Vite / Node",
        template = Template.React,
        languages = "JSX • Vite • Node",
        defaultPort = 5173, devCommand = "npm run dev", runCommand = "npm run dev", buildCommand = "npm run build",
        requiredTools = listOf("node", "npm"),
    ),
    Node(
        id = "NODE", label = "Node.js", blurb = "Backend / CLI",
        template = Template.Node,
        languages = "JavaScript • Node",
        defaultPort = 5173, devCommand = "npm start", runCommand = "npm start", buildCommand = null,
        requiredTools = listOf("node", "npm"),
    ),
    Empty(
        id = "EMPTY", label = "Empty Project", blurb = "Start from scratch",
        template = Template.Empty,
        languages = "Any",
        defaultPort = 5173, devCommand = null, runCommand = null, buildCommand = null,
    );

    companion object {
        /** Older projects only recorded a template, so fall back to that instead of showing "unknown". */
        fun from(id: String?, template: String?): ProjectType =
            entries.firstOrNull { it.id == id } ?: when (template) {
                "web" -> ProjectType.Website
                "node" -> ProjectType.Node
                "react" -> ProjectType.WebApp
                "android" -> ProjectType.AndroidApp
                else -> ProjectType.Empty
            }
    }
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val template: String,
    val createdAt: Long,
    var updatedAt: Long,
    var modelId: String? = null,
    var previewPort: Int = 5173,
    var type: String = "",
    /** §29 — "owner/repo" this project is linked to, or null when it is local-only. */
    var repoFullName: String? = null,
    /** §29 — branch used for pull/push. */
    var repoBranch: String? = null,
    /** §32 — the remote commit this project was last synchronised with; drives conflict detection. */
    var lastSyncSha: String? = null,
) {
    /** Derived, so it is never trusted from disk: [type] may be missing on older projects. */
    val kind: ProjectType get() = ProjectType.from(type, template)
}

@Serializable
private data class ProjectIndex(val projects: List<Project> = emptyList())

/**
 * §11 — a template must only ever claim files it actually writes. `declaredFiles` is the contract;
 * [ProjectScaffold.write] fails creation if any declared file is missing on disk, so a template that
 * claims a file it does not create is a hard error (§3), never silent.
 */
enum class Template(val id: String, val label: String, val description: String, val declaredFiles: List<String>) {
    Empty("empty", "Empty", "No files. Start from scratch.", emptyList()),
    Web("web", "HTML/CSS/JS", "index.html, styles.css, main.js", listOf("index.html", "styles.css", "main.js")),
    Node("node", "Node.js", "package.json, index.js", listOf("package.json", "index.js")),
    React(
        "react", "React",
        "package.json, index.html, vite.config.js, src/main.jsx, src/App.jsx",
        listOf("package.json", "index.html", "vite.config.js", "src/main.jsx", "src/App.jsx"),
    ),
    Android(
        "android", "Android App",
        "Gradle project: settings.gradle.kts, app/build.gradle.kts, manifest, MainActivity.kt",
        listOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/com/example/app/MainActivity.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values/themes.xml",
            ".gitignore",
            "README.md",
        ),
    ),
}

/**
 * Real filesystem workspace. Every project is a directory under
 * <filesDir>/workspace/<id>. Nothing here is simulated.
 */
class Workspace(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val root: File by lazy { File(context.filesDir, "workspace").apply { mkdirs() } }
    private val indexFile: File get() = File(root, "projects.json")

    fun projectDir(project: Project): File = File(root, project.id)

    fun list(): List<Project> {
        if (!indexFile.exists()) return emptyList()
        return runCatching { json.decodeFromString(ProjectIndex.serializer(), indexFile.readText()).projects }
            .getOrElse { emptyList() }
            .filter { File(root, it.id).isDirectory }
            .sortedByDescending { it.updatedAt }
    }

    private fun save(projects: List<Project>) {
        indexFile.writeText(json.encodeToString(ProjectIndex.serializer(), ProjectIndex(projects)))
    }

    fun create(name: String, template: Template, type: ProjectType = ProjectType.from(null, template.id)): Result<Project> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Project name cannot be empty." }
        require(list().none { it.name.equals(clean, ignoreCase = true) }) { "A project named \"$clean\" already exists." }
        val id = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "project" } + "-" + System.currentTimeMillis().toString(36)
        val dir = File(root, id)
        check(dir.mkdirs()) { "Could not create project directory." }
        val now = System.currentTimeMillis()
        val project = Project(id, clean, template.id, now, now, previewPort = type.defaultPort, type = type.id)
        scaffold(dir, clean, template)
        save(list() + project)
        project
    }

    private fun scaffold(dir: File, name: String, template: Template) {
        ProjectScaffold.write(dir, name, template)
    }

    fun touch(project: Project) {
        val all = list().map { if (it.id == project.id) it.copy(updatedAt = System.currentTimeMillis()) else it }
        save(all)
    }

    fun update(project: Project) {
        save(list().map { if (it.id == project.id) project else it })
    }

    fun rename(project: Project, newName: String): Result<Unit> = runCatching {
        val clean = newName.trim()
        require(clean.isNotEmpty()) { "Project name cannot be empty." }
        save(list().map { if (it.id == project.id) it.copy(name = clean, updatedAt = System.currentTimeMillis()) else it })
    }

    fun delete(project: Project): Result<Unit> = runCatching {
        projectDir(project).deleteRecursively()
        save(list().filterNot { it.id == project.id })
    }

    fun sizeOf(project: Project): Long =
        projectDir(project).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun fileCount(project: Project): Int =
        projectDir(project).walkTopDown().count { it.isFile }

    /** Total bytes used by every project (§52). */
    fun totalSize(): Long = list().sumOf { sizeOf(it) }

    /**
     * §41 — creates a project and fills it from a real zip archive. The directory is created first,
     * then the archive is extracted; no template scaffold is applied, because the archive *is* the
     * project.
     */
    fun createFromZip(name: String, type: ProjectType, zipFile: File): Result<Project> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Project name cannot be empty." }
        require(list().none { it.name.equals(clean, ignoreCase = true) }) { "A project named \"$clean\" already exists." }
        val dir = File(root, idFor(clean, type))
        check(dir.mkdirs()) { "Could not create project directory." }
        ProjectArchive.importZip(dir, zipFile).getOrThrow()
        val now = System.currentTimeMillis()
        val project = Project(dir.name, clean, type.template.id, now, now, previewPort = type.defaultPort, type = type.id)
        save(list() + project)
        project
    }

    /** §41 — creates a project and copies a folder's real files into it. */
    fun createFromFolder(name: String, type: ProjectType, srcDir: File): Result<Project> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Project name cannot be empty." }
        require(list().none { it.name.equals(clean, ignoreCase = true) }) { "A project named \"$clean\" already exists." }
        val dir = File(root, idFor(clean, type))
        check(dir.mkdirs()) { "Could not create project directory." }
        ProjectArchive.importFolder(dir, srcDir).getOrThrow()
        val now = System.currentTimeMillis()
        val project = Project(dir.name, clean, type.template.id, now, now, previewPort = type.defaultPort, type = type.id)
        save(list() + project)
        project
    }

    /** §41 — merges a folder's files into an existing project (used for "import into this project"). */
    fun mergeFolder(project: Project, srcDir: File): Result<Unit> = runCatching {
        ProjectArchive.importFolder(projectDir(project), srcDir).getOrThrow()
        touch(project)
    }

    /** §41 — zip the project (real bytes) into [dest]. */
    fun exportZip(project: Project, dest: File): Result<Unit> {
        val dir = projectDir(project)
        if (!dir.isDirectory) return Result.failure(IOException("Project directory is missing."))
        return ProjectArchive.exportZip(dir, dest)
    }

    private fun idFor(clean: String, type: ProjectType): String {
        val stem = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }
        return "$stem-${System.currentTimeMillis().toString(36)}"
    }
}

/**
 * §11 — the only place a scaffold's file contents live. Kept out of [Workspace] (pure Kotlin, no
 * Android dependency) so it is unit-testable, and so the "does this template really write its files"
 * contract (§3) can be asserted rather than assumed.
 */
object ProjectScaffold {

    /** The exact bytes a template writes, keyed by relative path. Empty for [Template.Empty]. */
    fun files(name: String, template: Template): Map<String, String> = when (template) {
        Template.Empty -> emptyMap()
        Template.Web -> mapOf(
            "index.html" to """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <title>$name</title>
                    <link rel="stylesheet" href="styles.css" />
                  </head>
                  <body>
                    <main><h1>$name</h1><p>Built with Sufyan Harness.</p></main>
                    <script src="main.js"></script>
                  </body>
                </html>
            """.trimIndent(),
            "styles.css" to "body{font-family:system-ui;background:#07080a;color:#e7eaf0;display:grid;place-items:center;min-height:100vh;margin:0}\n",
            "main.js" to "console.log('$name ready');\n",
        )
        Template.Node -> mapOf(
            "package.json" to """
                {
                  "name": "${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                  "version": "1.0.0",
                  "private": true,
                  "main": "index.js",
                  "scripts": { "start": "node index.js" }
                }
            """.trimIndent() + "\n",
            "index.js" to """
                const http = require('http');
                const port = process.env.PORT || 5173;
                http.createServer((req, res) => {
                  res.writeHead(200, { 'Content-Type': 'text/html' });
                  res.end('<h1>$name</h1>');
                }).listen(port, '127.0.0.1', () => console.log('listening on ' + port));
            """.trimIndent() + "\n",
        )
        Template.React -> mapOf(
            "package.json" to """
                {
                  "name": "${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                  "private": true,
                  "type": "module",
                  "scripts": { "dev": "vite", "build": "vite build" },
                  "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1" },
                  "devDependencies": { "vite": "^5.3.1", "@vitejs/plugin-react": "^4.3.1" }
                }
            """.trimIndent() + "\n",
            "index.html" to "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>$name</title></head>" +
                "<body><div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script></body></html>\n",
            "vite.config.js" to "import react from '@vitejs/plugin-react';\nexport default { plugins: [react()], server: { host: '127.0.0.1', port: 5173 } };\n",
            "src/main.jsx" to "import React from 'react';\nimport { createRoot } from 'react-dom/client';\nimport App from './App.jsx';\ncreateRoot(document.getElementById('root')).render(<App />);\n",
            "src/App.jsx" to "export default function App() {\n  return <main><h1>$name</h1></main>;\n}\n",
        )
        Template.Android -> androidFiles(name)
    }

    /** A package id derived from the project name; always a legal Java package. */
    fun packageFor(name: String): String {
        val stem = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "")
            .ifEmpty { "app" }
            .let { if (it.first().isDigit()) "a$it" else it }
        return "com.example.$stem"
    }

    /**
     * §34 — a real, complete Gradle Android project. These are the actual files a Gradle build
     * consumes; the app does not pretend to have created an Android project without them. The
     * package directory is fixed at `com/example/app` so [Template.declaredFiles] can be verified,
     * and the namespace/applicationId are set from the project name.
     */
    private fun androidFiles(name: String): Map<String, String> {
        val pkg = packageFor(name)
        val label = name.replace("\"", "'")
        return mapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "${name.replace("\"", "'")}"
                include(":app")
            """.trimIndent() + "\n",
            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.5.2" apply false
                    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
                }
            """.trimIndent() + "\n",
            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                android.useAndroidX=true
                kotlin.code.style=official
            """.trimIndent() + "\n",
            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "$pkg"
                    compileSdk = 34

                    defaultConfig {
                        applicationId = "$pkg"
                        minSdk = 24
                        targetSdk = 34
                        versionCode = 1
                        versionName = "1.0"
                    }

                    buildTypes {
                        release {
                            isMinifyEnabled = false
                            // Debug-signed so a debug build is always installable; replace with a
                            // real keystore before publishing.
                            signingConfig = signingConfigs.getByName("debug")
                        }
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                }

                dependencies {
                    implementation("androidx.core:core-ktx:1.13.1")
                    implementation("androidx.appcompat:appcompat:1.7.0")
                    implementation("com.google.android.material:material:1.12.0")
                }
            """.trimIndent() + "\n",
            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:allowBackup="true"
                        android:label="@string/app_name"
                        android:supportsRtl="true"
                        android:theme="@style/Theme.App">
                        <activity
                            android:name="com.example.app.MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",
            "app/src/main/java/com/example/app/MainActivity.kt" to """
                package com.example.app

                import android.os.Bundle
                import android.widget.TextView
                import androidx.appcompat.app.AppCompatActivity

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        val text = TextView(this).apply {
                            text = getString(R.string.hello)
                            textSize = 22f
                            setPadding(48, 48, 48, 48)
                        }
                        setContentView(text)
                    }
                }
            """.trimIndent() + "\n",
            "app/src/main/res/values/strings.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">$label</string>
                    <string name="hello">$label is running.</string>
                </resources>
            """.trimIndent() + "\n",
            "app/src/main/res/values/themes.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <style name="Theme.App" parent="Theme.Material3.DayNight.NoActionBar" />
                </resources>
            """.trimIndent() + "\n",
            ".gitignore" to "build/\n.gradle/\nlocal.properties\n*.apk\n",
            "README.md" to "# $name\n\nAndroid app created with Sufyan Harness.\n\n" +
                "Build it with `./gradlew assembleDebug` (JDK 17, Android SDK and Gradle required) " +
                "or push it to GitHub and let CI build the APK.\n",
        )
    }

    /**
     * Writes the template's files under [dir] and then verifies every [Template.declaredFiles] entry
     * is really on disk. A mismatch throws, so a project is never created while a template silently
     * skips a file it advertised.
     */
    fun write(dir: File, name: String, template: Template): List<String> {
        files(name, template).forEach { (rel, content) ->
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        val missing = template.declaredFiles.filter { !File(dir, it).isFile }
        check(missing.isEmpty()) {
            "Template '${template.id}' did not create all declared files: ${missing.joinToString()}"
        }
        return template.declaredFiles
    }
}
