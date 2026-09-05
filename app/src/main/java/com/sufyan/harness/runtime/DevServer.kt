package com.sufyan.harness.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection

data class ServerState(
    val running: Boolean = false,
    val port: Int = 0,
    val url: String = "",
    val kind: String = "",
    val console: List<String> = emptyList(),
)

/**
 * Live preview backend.
 *
 * Two real modes:
 *  1. Static mode — a built-in HTTP server serving the project directory. Works
 *     on every device with no Linux runtime required.
 *  2. Process mode — runs the project's own dev command (npm run dev, node ...)
 *     and streams its output, used when Node is actually available.
 */
class DevServer(private val projectDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state

    private var serverSocket: ServerSocket? = null
    private var process: Process? = null

    private fun log(line: String) {
        _state.value = _state.value.copy(console = (_state.value.console + line).takeLast(300))
    }

    private fun freePort(preferred: Int): Int =
        (listOf(preferred) + (5173..5199)).firstOrNull { p ->
            runCatching { ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", p)); true } }.getOrDefault(false)
        } ?: 0

    /** Detects a port a child process started listening on, by probing. */
    private suspend fun waitForPort(port: Int, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
            }.getOrDefault(false)
            if (open) return true
            delay(400)
        }
        return false
    }

    fun startStatic(preferredPort: Int = 5173): Result<Unit> = runCatching {
        check(!_state.value.running) { "A server is already running for this project." }
        val port = freePort(preferredPort)
        check(port != 0) { "No free local port available between 5173 and 5199." }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = socket
        _state.value = ServerState(true, port, "http://127.0.0.1:$port", "Static server", listOf("✓ Server running on port $port"))
        scope.launch {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                launch { serve(client) }
            }
        }
        // launch hands back a Job; the block has to end on Unit so that runCatching
        // settles on the declared Result<Unit>.
        Unit
    }.onFailure { log("Could not start server: ${it.message}") }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                val reader = s.getInputStream().bufferedReader()
                val request = reader.readLine() ?: return
                val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                val rel = path.trimStart('/').ifEmpty { "index.html" }
                val target = File(projectDir, rel).canonicalFile
                val out = s.getOutputStream()
                if (!target.path.startsWith(projectDir.canonicalPath)) {
                    respond(out, 403, "text/plain", "Forbidden".toByteArray()); return
                }
                val file = if (target.isDirectory) File(target, "index.html") else target
                if (!file.isFile) {
                    log("404 $path")
                    respond(out, 404, "text/html", "<h1>404</h1><p>$rel not found in this project.</p>".toByteArray())
                    return
                }
                val type = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
                respond(out, 200, type, file.readBytes())
            } catch (e: IOException) {
                log("Request error: ${e.message}")
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    /** Runs the project's real dev command; only succeeds if the port opens. */
    suspend fun startProcess(command: String, preferredPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(!_state.value.running) { "A server is already running for this project." }
            val pb = ProcessBuilder("/system/bin/sh", "-c", command).directory(projectDir)
            pb.environment()["PORT"] = preferredPort.toString()
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            _state.value = ServerState(true, preferredPort, "http://127.0.0.1:$preferredPort", "Dev process", listOf("$ $command"))
            scope.launch {
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach { log(it) } }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                log("Process exited with code $code")
                _state.value = _state.value.copy(running = false)
            }
            if (!waitForPort(preferredPort)) {
                stop()
                error("The dev command started but nothing is listening on port $preferredPort. Check the console output.")
            }
            log("✓ Server running on port $preferredPort")
        }.onFailure { log("Could not start dev server: ${it.message}") }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { process?.destroy() }
        process = null
        _state.value = _state.value.copy(running = false, console = _state.value.console + "Server stopped.")
    }

    fun clearConsole() {
        _state.value = _state.value.copy(console = emptyList())
    }
}
