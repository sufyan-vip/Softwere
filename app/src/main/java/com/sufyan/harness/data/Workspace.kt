package com.sufyan.harness.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Project(
    val id: String,
    val name: String,
    val template: String,
    val createdAt: Long,
    var updatedAt: Long,
    var modelId: String? = null,
    var previewPort: Int = 5173,
)

@Serializable
private data class ProjectIndex(val projects: List<Project> = emptyList())

enum class Template(val id: String, val label: String, val description: String) {
    Empty("empty", "Empty", "No files. Start from scratch."),
    Web("web", "HTML/CSS/JS", "index.html, styles.css, main.js"),
    Node("node", "Node.js", "package.json + index.js server"),
    React("react", "React", "Vite-style React scaffold"),
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
        return runCatching { json.decodeFromString<ProjectIndex>(indexFile.readText()).projects }
            .getOrElse { emptyList() }
            .filter { File(root, it.id).isDirectory }
            .sortedByDescending { it.updatedAt }
    }

    private fun save(projects: List<Project>) {
        indexFile.writeText(json.encodeToString(ProjectIndex.serializer(), ProjectIndex(projects)))
    }

    fun create(name: String, template: Template): Result<Project> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Project name cannot be empty." }
        require(list().none { it.name.equals(clean, ignoreCase = true) }) { "A project named \"$clean\" already exists." }
        val id = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifEmpty { "project" } + "-" + System.currentTimeMillis().toString(36)
        val dir = File(root, id)
        check(dir.mkdirs()) { "Could not create project directory." }
        val now = System.currentTimeMillis()
        val project = Project(id, clean, template.id, now, now)
        scaffold(dir, clean, template)
        save(list() + project)
        project
    }

    private fun scaffold(dir: File, name: String, template: Template) {
        when (template) {
            Template.Empty -> Unit
            Template.Web -> {
                File(dir, "index.html").writeText(
                    """
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
                )
                File(dir, "styles.css").writeText(
                    "body{font-family:system-ui;background:#07080a;color:#e7eaf0;display:grid;place-items:center;min-height:100vh;margin:0}\n",
                )
                File(dir, "main.js").writeText("console.log('$name ready');\n")
            }
            Template.Node -> {
                File(dir, "package.json").writeText(
                    """
                    {
                      "name": "${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                      "version": "1.0.0",
                      "private": true,
                      "main": "index.js",
                      "scripts": { "start": "node index.js" }
                    }
                    """.trimIndent(),
                )
                File(dir, "index.js").writeText(
                    """
                    const http = require('http');
                    const port = process.env.PORT || 5173;
                    http.createServer((req, res) => {
                      res.writeHead(200, { 'Content-Type': 'text/html' });
                      res.end('<h1>$name</h1>');
                    }).listen(port, '127.0.0.1', () => console.log('listening on ' + port));
                    """.trimIndent(),
                )
            }
            Template.React -> {
                File(dir, "package.json").writeText(
                    """
                    {
                      "name": "${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                      "private": true,
                      "type": "module",
                      "scripts": { "dev": "vite", "build": "vite build" },
                      "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1" },
                      "devDependencies": { "vite": "^5.3.1", "@vitejs/plugin-react": "^4.3.1" }
                    }
                    """.trimIndent(),
                )
                File(dir, "index.html").writeText(
                    "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>$name</title></head>" +
                        "<body><div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script></body></html>\n",
                )
                File(dir, "vite.config.js").writeText(
                    "import react from '@vitejs/plugin-react';\nexport default { plugins: [react()], server: { host: '127.0.0.1', port: 5173 } };\n",
                )
                File(dir, "src").mkdirs()
                File(dir, "src/main.jsx").writeText(
                    "import React from 'react';\nimport { createRoot } from 'react-dom/client';\nimport App from './App.jsx';\ncreateRoot(document.getElementById('root')).render(<App />);\n",
                )
                File(dir, "src/App.jsx").writeText(
                    "export default function App() {\n  return <main><h1>$name</h1></main>;\n}\n",
                )
            }
        }
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
}
