package com.sufyan.harness.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sufyan.harness.AgentPhase
import com.sufyan.harness.ToolActivity
import com.sufyan.harness.UiMessage
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

/**
 * §16 — the live agent state. Only rendered while there is something real to report: every value
 * comes from the event stream in HarnessViewModel, and [onStop] cancels the coroutine that is
 * actually running the turn.
 */
@Composable
fun AgentStateBar(phase: AgentPhase, status: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    if (phase == AgentPhase.Idle) return
    val color = when (phase) {
        AgentPhase.Failed -> HarnessColors.Danger
        AgentPhase.Complete -> HarnessColors.Ok
        else -> HarnessColors.Accent
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .clip(RoundedCornerShape(Radius.md))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phase.busy) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = color)
        } else {
            Dot(color)
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(phase.label, style = MaterialTheme.typography.titleSmall)
            if (status.isNotBlank() && status != phase.label) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (phase.busy) {
            TextButton(
                onClick = onStop,
                modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = "Stop the agent" },
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp), tint = HarnessColors.Danger)
                Spacer(Modifier.width(Spacing.xs))
                Text("Stop", style = MaterialTheme.typography.labelMedium, color = HarnessColors.Danger)
            }
        }
    }
}

/** §14B — the collapsible execution timeline, one line per step instead of a card per call. */
@Composable
fun ActivityTimeline(
    tools: List<ToolActivity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRerun: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) return
    val running = tools.count { !it.done }
    val failed = tools.count { it.done && !it.ok }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Agent activity",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                when {
                    running > 0 -> "$running running · ${tools.size} steps"
                    failed > 0 -> "${tools.size} steps · $failed failed"
                    else -> "${tools.size} steps"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse agent activity" else "Expand agent activity",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running > 0) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = HarnessColors.Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Column {
                tools.forEach { tool ->
                    ToolCallLine(tool, onRerun = onRerun)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** §15 — a compact, expandable tool row: status, name, target, then the output if opened. */
@Composable
fun ToolCallLine(tool: ToolActivity, onRerun: ((String) -> Unit)? = null, modifier: Modifier = Modifier) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                when {
                    !tool.done -> Icons.Default.PlayArrow
                    tool.ok -> Icons.Default.CheckCircle
                    else -> Icons.Default.Cancel
                },
                contentDescription = when {
                    !tool.done -> "Running"
                    tool.ok -> "Completed"
                    else -> "Failed"
                },
                modifier = Modifier.size(14.dp),
                tint = when {
                    !tool.done -> MaterialTheme.colorScheme.onSurfaceVariant
                    tool.ok -> HarnessColors.Ok
                    else -> HarnessColors.Danger
                },
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tool.name, style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize))
                    if (tool.done && !tool.ok) {
                        Spacer(Modifier.width(Spacing.sm))
                        StatusChip("Exit ${exitCodeOf(tool.detail)}", StatusKind.Error)
                    }
                }
                if (tool.target.isNotBlank()) {
                    Text(
                        tool.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 40 else 1,
                    )
                }
                if (expanded && tool.summary.isNotBlank() && tool.summary != tool.target) {
                    Text(tool.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                if (!tool.done) {
                    Text(
                        "Running…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(bottom = Spacing.sm)) {
                val diagnosis = diagnosisFor(tool)
                if (diagnosis != null) {
                    Column(Modifier.padding(horizontal = Spacing.md)) {
                        Text("Why it failed", style = MaterialTheme.typography.labelLarge, color = HarnessColors.Danger)
                        Text(
                            diagnosis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onRerun != null && tool.name == "run_command" && tool.target.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.sm))
                            SecondaryButton("Retry in terminal", { onRerun(tool.target) }, icon = Icons.Default.PlayArrow)
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (tool.detail.isNotBlank()) {
                    CodeBlock(tool.detail.take(2000), modifier = Modifier.padding(horizontal = Spacing.md))
                }
            }
        }
    }
}

/** §14C — the answer itself, plus what changed and how to look at it. */
@Composable
fun FinalAnswerCard(
    text: String,
    changedFiles: List<String>,
    onReviewChanges: (() -> Unit)?,
    onOpenPreview: (() -> Unit)?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    usageLine: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Final answer",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 32.dp)) {
                Text("Copy", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        AssistantRichText(text)
        if (usageLine != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                usageLine,
                style = MonoStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (changedFiles.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Changed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                changedFiles.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("•", style = MonoStyle, color = HarnessColors.Accent)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(path, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (onReviewChanges != null || onOpenPreview != null) {
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (onReviewChanges != null) {
                    SecondaryButton("Review changes", onReviewChanges, Modifier.weight(1f), icon = Icons.Default.Check)
                }
                if (onOpenPreview != null) {
                    SecondaryButton("Open preview", onOpenPreview, Modifier.weight(1f), icon = Icons.Default.PlayArrow)
                }
            }
        }
    }
}

/** §17 — what one agent turn actually achieved. Every number is counted from the tool log. */
@Composable
fun SessionSummaryCard(
    summary: AgentTurnSummary,
    modifier: Modifier = Modifier,
    onReviewDiff: (() -> Unit)? = null,
    onOpenPreview: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    HarnessCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                if (summary.failed) "Task stopped with errors" else "Task summary",
                style = MaterialTheme.typography.titleSmall,
                color = if (summary.failed) HarnessColors.Danger else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SummaryStat("Files", summary.filesChanged.toString())
                SummaryStat("Commands", summary.commandsRun.toString())
                SummaryStat(
                    "Verification",
                    when (summary.verification) {
                        null -> "not run"
                        true -> "passed"
                        false -> "failed"
                    },
                )
                SummaryStat("Preview", if (summary.previewRunning) "running" else "stopped")
            }
            if (onReviewDiff != null || onOpenPreview != null || onContinue != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (onReviewDiff != null) {
                        SecondaryButton("Review diff", { if (enabled) onReviewDiff() }, Modifier.weight(1f))
                    }
                    if (onOpenPreview != null) {
                        SecondaryButton("Preview", { if (enabled) onOpenPreview() }, Modifier.weight(1f))
                    }
                    if (onContinue != null) {
                        PrimaryButton("Continue", { if (enabled) onContinue() }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

data class AgentTurnSummary(
    val filesChanged: Int,
    val commandsRun: Int,
    val verification: Boolean?,
    val previewRunning: Boolean,
    val failed: Boolean,
)

/** Counts derived from the finished turn — used by both the summary card and the final answer. */
fun summarizeTurn(msg: UiMessage, previewRunning: Boolean): AgentTurnSummary {
    val edits = msg.tools.filter { it.done && it.name in EDIT_TOOLS }
    val commands = msg.tools.filter { it.done && it.name == "run_command" }
    return AgentTurnSummary(
        filesChanged = edits.map { it.target }.filter { it.isNotBlank() }.distinct().size,
        commandsRun = commands.size,
        verification = commands.lastOrNull()?.ok,
        previewRunning = previewRunning,
        failed = msg.tools.any { it.done && !it.ok } || msg.role == "error",
    )
}

/** Real changed files of a turn, from the tool calls that actually wrote to disk. */
fun changedFilesOf(msg: UiMessage): List<String> =
    msg.tools.filter { it.done && it.ok && it.name in EDIT_TOOLS }.map { it.target }.filter { it.isNotBlank() }.distinct()

private val EDIT_TOOLS = setOf("write_file", "edit_file", "delete_file", "create_file")

/**
 * §4 — a failed command is never shown as a bare exit code. This reads the real output the tool
 * returned and names the likely cause plus what to do about it.
 */
private fun diagnosisFor(tool: ToolActivity): String? {
    if (tool.done && !tool.ok) {
        val d = tool.detail.lowercase()
        return when {
            d.contains("not found") || d.contains("127") ->
                "The command is not installed in this runtime. Install the matching toolchain from " +
                    "Settings → Toolchains, or run it in the Terminal once the binary exists."
            d.contains("permission denied") ->
                "The file or directory is not writable by the app. Project files live in the app's " +
                    "private workspace; anything outside it cannot be written."
            d.contains("no space") -> "The device is out of storage. Free space with Settings → Storage."
            d.contains("network") || d.contains("resolve host") || d.contains("connection refused") ->
                "The command needed the network and could not reach it. Check connectivity and retry."
            tool.name in EDIT_TOOLS ->
                "The edit did not apply. Open the file in the editor to check its current contents, then retry."
            else -> "Exit ${exitCodeOf(tool.detail)}. Open this step to read the output and fix the cause."
        }
    }
    return null
}

private fun exitCodeOf(detail: String): String {
    val match = Regex("exit\\s*(\\d{1,3})").find(detail) ?: return "?"
    return match.groupValues[1]
}

/**
 * Assistant prose with fenced blocks lifted out into real code blocks. Public so the chat list and
 * the final-answer card render identical markdown rather than two dialects of it.
 */
@Composable
fun AssistantRichText(text: String) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 0) {
                if (part.isNotBlank()) Text(part.trim(), style = MaterialTheme.typography.bodyMedium)
            } else {
                val lang = part.lineSequence().firstOrNull()?.trim().orEmpty()
                val body = if (lang.isNotEmpty() && !lang.contains(' ')) part.substringAfter('\n') else part
                CodeBlock(body.trimEnd(), lang.takeIf { it.isNotEmpty() && !it.contains(' ') })
            }
        }
    }
}
