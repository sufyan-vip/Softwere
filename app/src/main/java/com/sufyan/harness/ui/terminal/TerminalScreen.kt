package com.sufyan.harness.ui.terminal

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.LineKind
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

private val QUICK = listOf("ls -la", "pwd", "cat ", "node --version", "git status", "clear")

/**
 * §25 — the terminal screen. The header shows the real session directory and the toolbar's every
 * button drives the actual process in [HarnessViewModel]; output is selectable so it can be copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: HarnessViewModel) {
    val project by vm.active.collectAsState()
    val lines by vm.terminalLines.collectAsState()
    val running by vm.shellRunning.collectAsState()
    val busy by vm.commandRunning.collectAsState()
    val cwd by vm.shellCwd.collectAsState()
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fontSize = vm.settings.terminalFontSize.sp

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
            subtitle = project?.let { p -> if (running) "${p.name} · session running" else "${p.name} · no session" } ?: "No project open",
            actions = {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                }
                StatusChip(if (running) "sh" else "stopped", if (running) StatusKind.Ok else StatusKind.Neutral)
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
            Column(
                Modifier
                    .weight(1f)
                    .background(HarnessColors.Base),
            ) {
                // Workspace + real session directory
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                    Text("Workspace: ${project!!.name}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        cwd ?: project!!.let { vm.workspace.projectDir(it).absolutePath },
                        style = MonoStyle.copy(fontSize = (fontSize.value - 1f).sp),
                        color = HarnessColors.TextMuted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                // Toolbar — each entry acts on the real process
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (running) vm.restartShell() else vm.startShell() }) {
                        Icon(if (running) Icons.Default.Refresh else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (running) "Restart" else "New session", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { vm.interruptShell() }, enabled = running && busy) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Interrupt", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { vm.stopShell() }, enabled = running) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Stop", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("History", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { vm.clearTerminal() }) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (lines.isEmpty()) {
                    val startAction: (() -> Unit)? = if (running) null else ({ vm.startShell() })
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EmptyState(
                            Icons.Outlined.Terminal,
                            if (running) "Session ready" else "No session",
                            if (running) {
                                "Type a command below. It runs in ${project!!.name} on this device — output is genuine."
                            } else {
                                "Starts a real /system/bin/sh process in ${project!!.name}. Output is genuine — nothing is simulated."
                            },
                            if (running) null else "New session",
                            startAction,
                        )
                    }
                } else {
                    SelectionContainer(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(Spacing.md),
                        ) {
                            items(lines) { line ->
                                Text(
                                    text = if (line.kind == LineKind.Input) "$ ${line.text}" else line.text,
                                    style = MonoStyle.copy(fontSize = fontSize),
                                    color = when (line.kind) {
                                        LineKind.Input -> HarnessColors.Accent
                                        LineKind.Stderr -> HarnessColors.Danger
                                        LineKind.System -> HarnessColors.TextMuted
                                        LineKind.Stdout -> HarnessColors.TextPrimary
                                    },
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
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
                            val h = vm.commandHistory
                            if (h.isNotEmpty()) {
                                historyIndex = (historyIndex + 1).coerceAtMost(h.lastIndex)
                                input = h[h.lastIndex - historyIndex]
                            }
                        },
                        enabled = vm.commandHistory.isNotEmpty(),
                    ) { Icon(Icons.Default.History, contentDescription = "Previous command") }
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
                Text("Command history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(Spacing.lg))
                val entries = vm.commandHistory.reversed().distinct().take(60)
                if (entries.isEmpty()) {
                    Text(
                        "Nothing has been run in this app yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                } else {
                    entries.forEach { cmd ->
                        SettingRow(cmd, icon = Icons.Default.PlayArrow, onClick = { input = cmd; showHistory = false })
                    }
                }
            }
        }
    }
}
