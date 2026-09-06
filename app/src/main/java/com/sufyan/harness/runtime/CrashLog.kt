package com.sufyan.harness.runtime

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * §56 — a crash log the app itself can show you.
 *
 * When the process dies from an uncaught exception the user is left with a system dialog and no
 * information; if the crash happens during start-up they cannot even reach a screen that could
 * report it. So the handler writes the failure to disk first, and the next launch shows exactly
 * what died and when, with the stack trace available to copy (RULE 4 — no hidden failures).
 *
 * The file format is deliberately the plain three-field text a user can paste into a bug report:
 *
 * ```
 * time: 1788640315747
 * msg: java.lang.NullPointerException: ...
 * stacktrace: java.lang.RuntimeException: ...
 * ```
 *
 * Nothing here touches the Android framework, so it is fully unit-tested.
 */
class CrashLog(private val dir: File) {

    data class Report(val time: Long, val message: String, val stackTrace: String) {
        /** Exactly what gets written to disk — what the user copies out of the dialog. */
        fun render(): String = "time: $time\n\nmsg: $message\n\nstacktrace: $stackTrace"
    }

    private val file: File get() = File(dir, FILE_NAME)

    /**
     * Installs the handler, chaining to whatever was there before so the platform still gets to
     * kill the process (swallowing the exception would leave a frozen, half-dead app).
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes [error] to disk, replacing any older report. Never throws. */
    fun record(error: Throwable, now: Long = System.currentTimeMillis()) {
        runCatching {
            dir.mkdirs()
            val trace = StringWriter().also { sw -> PrintWriter(sw).use { error.printStackTrace(it) } }.toString()
            val message = "${error::class.java.name}: ${error.message ?: "(no message)"}"
            file.writeText(Report(now, message, trace.trim()).render())
        }
    }

    /** The crash from the previous run, or `null` if the last run ended normally. */
    fun last(): Report? {
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull() ?: return null
        return parse(text)
    }

    /** Removes the report; called when the user has seen it. */
    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "last-crash.txt"

        /** Tolerant parser: a truncated or hand-edited file yields whatever is still readable. */
        fun parse(text: String): Report? {
            if (text.isBlank()) return null
            val time = Regex("""(?m)^time:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val message = Regex("""(?m)^msg:\s*(.*)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val traceStart = text.indexOf("stacktrace:")
            val trace = if (traceStart >= 0) text.substring(traceStart + "stacktrace:".length).trim() else ""
            if (message.isEmpty() && trace.isEmpty()) return null
            return Report(time, message.ifEmpty { trace.lineSequence().firstOrNull().orEmpty() }, trace)
        }
    }
}
