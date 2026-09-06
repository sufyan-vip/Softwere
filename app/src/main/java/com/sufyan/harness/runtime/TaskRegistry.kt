package com.sufyan.harness.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A long-running piece of work the user should be able to see and stop (§13, §51). */
data class RuntimeTask(
    val id: String,
    val label: String,
    val kind: Kind,
    val startedAt: Long = System.currentTimeMillis(),
) {
    enum class Kind { Server, Build, Agent, Install, Shell }
}

/**
 * §13 — the single source of truth for "is Sufyan Harness doing something in the background".
 *
 * The foreground notification is started when the first task registers and stopped when the last one
 * finishes, so the notification can never claim work that is not happening, and work can never run
 * invisibly. Everything registered here is genuinely running.
 */
class TaskRegistry(private val context: Context) {

    private val _tasks = MutableStateFlow<List<RuntimeTask>>(emptyList())
    val tasks: StateFlow<List<RuntimeTask>> = _tasks

    val busy: Boolean get() = _tasks.value.isNotEmpty()

    @Synchronized
    fun start(id: String, label: String, kind: RuntimeTask.Kind) {
        val existing = _tasks.value.filterNot { it.id == id }
        _tasks.value = existing + RuntimeTask(id, label, kind)
        syncService()
    }

    @Synchronized
    fun finish(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        syncService()
    }

    @Synchronized
    fun clear() {
        _tasks.value = emptyList()
        syncService()
    }

    private fun syncService() {
        val tasks = _tasks.value
        if (tasks.isEmpty()) {
            RuntimeService.stop(context)
        } else {
            RuntimeService.start(context, notificationText(tasks))
        }
    }

    private fun notificationText(tasks: List<RuntimeTask>): String = when (tasks.size) {
        1 -> tasks.first().label
        else -> "${tasks.size} tasks running \u00b7 " + tasks.joinToString(", ") { it.label }.take(80)
    }
}
