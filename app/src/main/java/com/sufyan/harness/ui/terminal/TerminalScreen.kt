package com.sufyan.harness.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.Diagnosis
import com.sufyan.harness.runtime.EnvReport
import com.sufyan.harness.runtime.FixAction
import com.sufyan.harness.runtime.LineKind
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

private val QUICK = listOf("ls -la", "pwd", "cat ", "node --version", "git status", "clear")

/**
 * §21-§26 — the terminal.
 *
 * Real process, real exit codes: the prompt shows the directory the shell itself reports, the
 * session tabs are separate processes, and when a command fails the screen explains WHAT failed,
 * WHY, and HOW to fix it with buttons that run the fix (§4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: HarnessViewModel, onOpenRuntime: () -> Unit = {}) {
    val project by vm.active.collectAsState()
    val lines by vm.terminalLines.collectAsState()
    val running by vm.shellRunning.collectAsState()
    val busy by vm.commandRunning.collectAsState()
    val cwd by vm.shellCwd.collectAsState()
    val sessions by vm.sessionInfo.collectAsState()
    val activeId by vm.activeSessionId.collectAsState()
    val diagnosis by vm.lastDiagnosis.collectAsState()
    val envReport by vm.envReport.collectAsState()
    val envScanning by vm.envScanning.collectAsState()

    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var showHistory by remember { mutableStateOf(false) }
    var showHealth by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fontSize = vm.settings.terminalFontSize.sp
    val wrap = vm.settings.terminalWordWrap

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    fun submit(value: String) {
        val cmd = value.trim()
        if (cmd.isEmpty()) return
        if (cmd == "clear") vm.clearTerminal() else vm.runCommand(cmd)
        input = ""
        historyIndex = -1
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            "Terminal",
            subtitle = project?.let { p ->
                if (running) "${p.name} \u00b7 ${sessions.count { it.running }} session(s)" else "${p.name} \u00b7 no session"
            } ?: "No project open",
            actions = {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                }
                IconButton(onClick = { showHealth = true; vm.inspectEnvironment() }) {
                    Icon(Icons.Outlined.MonitorHeart, contentDescription = "Environment health")
                }
                StatusChip(if (running) "running" else "stopped", if (running) StatusKind.Ok else StatusKind.Neutral)
                Spacer(Modifier.width(Spacing.sm))
            },
        )

        if (project == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.Terminal,
                    "No project open",
                    "The terminal runs inside a project directory. Open a project first.",
                )
            }
        } else {
            Column(Modifier.weight(1f).background(HarnessColors.Base)) {
                // §26 — one tab per real process.
                if (sessions.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sessions.forEach { info ->
                            val selected = info.id == activeId
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.surface,
                                    )
                                    .padding(start = Spacing.sm, end = Spacing.xs, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Dot(
                                    when {
                                        info.busy -> HarnessColors.Warn
                                        info.running -> HarnessColors.Ok
                                        else -> HarnessColors.TextMuted
                                    },
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    info.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clickable(onClick = { vm.selectSession(info.id) }),
                                )
                                IconButton(onClick = { vm.closeSession(info.id) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Close, "Close ${info.name}", modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                        IconButton(onClick = { vm.openSession("shell ${sessions.size + 1}") }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Add, "New session", modifier = Modifier.size(16.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }

                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    Text(
                        cwd ?: vm.workspace.projectDir(project!!).absolutePath,
                        style = MonoStyle.copy(fontSize = (fontSize.value - 1f).sp),
                        color = HarnessColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarButton(
                        if (running) "Restart" else "New session",
                        if (running) Icons.Default.Refresh else Icons.Default.PlayArrow,
                    ) { if (running) vm.restartShell() else vm.startShell() }
                    ToolbarButton("Interrupt", Icons.Default.Stop, enabled = running && busy) { vm.interruptShell() }
                    ToolbarButton("Stop all", Icons.Default.PowerSettingsNew, enabled = running) { vm.stopShell() }
                    ToolbarButton("History", Icons.Default.History) { showHistory = true }
                    ToolbarButton("Clear", Icons.Default.CleaningServices) { vm.clearTerminal() }
                }

                if (lines.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EmptyState(
                            Icons.Outlined.Terminal,
                            if (running) "Session ready" else "No session",
                            if (running) {
                                "Type a command below. It runs in ${project!!.name} on this device \u2014 output is genuine."
                            } else {
                                "Starts a real ${vm.settings.terminalShell} process in ${project!!.name}. " +
                                    "Output is genuine \u2014 nothing is simulated."
                            },
                            if (running) null else "New session",
                            if (running) null else ({ vm.startShell() }),
                        )
                    }
                } else {
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = listState, contentPadding = PaddingValues(Spacing.md)) {
                            items(lines) { line ->
                                val color = when (line.kind) {
                                    LineKind.Input -> HarnessColors.Accent
                                    LineKind.Stderr -> HarnessColors.Danger
                                    LineKind.System -> HarnessColors.TextMuted
                                    LineKind.Stdout -> HarnessColors.TextPrimary
                                }
                                val text = if (line.kind == LineKind.Input) "$ ${line.text}" else line.text
                                if (wrap) {
                                    Text(text, style = MonoStyle.copy(fontSize = fontSize), color = color)
                                } else {
                                    Text(
                                        text,
                                        style = MonoStyle.copy(fontSize = fontSize),
                                        color = color,
                                        maxLines = 1,
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    )
                                }
                            }
                        }
                    }
                }

                diagnosis?.let { d ->
                    DiagnosisCard(
                        diagnosis = d,
                        onDismiss = { vm.dismissDiagnosis() },
                        onAction = { action ->
                            if (action is FixAction.OpenRuntime) onOpenRuntime() else vm.applyFix(action)
                        },
                    )
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    QUICK.forEach { q ->
                        AssistChip(
                            onClick = { input = q },
                            label = { Text(q, style = MonoStyle.copy(fontSize = 11.sp)) },
                            shape = RoundedCornerShape(Radius.sm),
                        )
                    }
                }
                Row(
                    Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconButton(
                        onClick = {
                            val h = vm.settings.commandHistory()
                            if (h.isNotEmpty()) {
                                historyIndex = (historyIndex + 1).coerceAtMost(h.lastIndex)
                                input = h[historyIndex]
                            }
                        },
                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous command") }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter command", style = MonoStyle) },
                        textStyle = MonoStyle,
                        singleLine = true,
                        enabled = project != null,
                        shape = RoundedCornerShape(Radius.md),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false),
                        keyboardActions = KeyboardActions(onSend = { submit(input) }),
                    )
                    FilledIconButton(
                        onClick = { submit(input) },
                        enabled = project != null && input.isNotBlank(),
                        shape = RoundedCornerShape(Radius.md),
                    ) { Icon(Icons.Default.KeyboardReturn, contentDescription = "Run command") }
                }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = Spacing.xl)) {
                Row(Modifier.fillMaxWidth().padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text("Command history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.settings.clearCommandHistory(); showHistory = false }) { Text("Clear") }
                }
                val entries = vm.settings.commandHistory()
                if (entries.isEmpty()) {
                    Text(
                        "Nothing has been run in this app yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(entries) { cmd ->
                            SettingRow(cmd, icon = Icons.Default.PlayArrow, onClick = { input = cmd; showHistory = false })
                        }
                    }
                }
            }
        }
    }

    if (showHealth) {
        ModalBottomSheet(onDismissRequest = { showHealth = false }, containerColor = MaterialTheme.colorScheme.surface) {
            HealthSheet(
                report = envReport,
                scanning = envScanning,
                onRescan = { vm.inspectEnvironment() },
                onOpenRuntime = { showHealth = false; onOpenRuntime() },
            )
        }
    }
}

@Composable
private fun ToolbarButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** §4 / §23 — WHAT / WHY / HOW plus buttons that actually perform the fix. */
@Composable
private fun DiagnosisCard(diagnosis: Diagnosis, onDismiss: () -> Unit, onAction: (FixAction) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.md)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = HarnessColors.Danger, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(diagnosis.what, style = MaterialTheme.typography.titleSmall, color = HarnessColors.Danger, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(14.dp))
            }
        }
        Text(diagnosis.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(diagnosis.how, style = MaterialTheme.typography.bodySmall)
        if (diagnosis.actions.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                diagnosis.actions.forEach { action ->
                    val label = when (action) {
                        is FixAction.RunCommand -> action.label
                        is FixAction.InstallTool -> action.label
                        FixAction.Retry -> "Retry"
                        FixAction.OpenRuntime -> "Linux runtime"
                    }
                    AssistChip(
                        onClick = { onAction(action) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        shape = RoundedCornerShape(Radius.sm),
                    )
                }
            }
        }
    }
}

/** §22 — health report, every row produced by running something. */
@Composable
private fun HealthSheet(report: EnvReport?, scanning: Boolean, onRescan: () -> Unit, onOpenRuntime: () -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Environment health", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRescan, enabled = !scanning) { Text("Re-scan") }
        }
        if (scanning && report == null) {
            LoadingState("Running probes\u2026")
            return@Column
        }
        if (report == null) {
            Text(
                "No scan has been run yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            "${report.runtimeLabel} \u00b7 ${if (report.healthy) "healthy" else "issues found"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (report.healthy) HarnessColors.Ok else HarnessColors.Warn,
        )
        Spacer(Modifier.height(Spacing.sm))
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(report.items) { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                    Icon(
                        if (item.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (item.ok) HarnessColors.Ok else HarnessColors.Danger,
                        modifier = Modifier.size(15.dp).padding(top = 2.dp),
                    )
                    Column {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                        Text(item.value, style = MonoStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = HarnessColors.Warn) }
                    }
                }
            }
            if (report.missingTools.isNotEmpty()) {
                item {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(Spacing.sm))
                        Text("Missing tools", style = MaterialTheme.typography.labelLarge)
                        report.missingTools.forEach { tool ->
                            Text(
                                "${tool.tool.label} \u2014 ${tool.detail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        SecondaryButton("Open Linux runtime", onOpenRuntime, icon = Icons.Default.Terminal)
                    }
                }
            }
            item {
                Text(
                    "PATH: ${report.path}",
                    style = MonoStyle.copy(fontSize = 10.sp),
                    color = HarnessColors.TextMuted,
                )
            }
        }
    }
}
