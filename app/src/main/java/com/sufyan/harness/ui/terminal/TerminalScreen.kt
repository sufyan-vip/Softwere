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

@Composable
fun TerminalScreen(vm: HarnessViewModel) {
    val project by vm.active.collectAsState()
    val lines by vm.terminalLines.collectAsState()
    val running by vm.shellRunning.collectAsState()
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val fontSize = vm.settings.terminalFontSize.sp

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            "Terminal",
            subtitle = project?.let { "${it.name} • /system/bin/sh" } ?: "No project open",
            actions = {
                if (running) {
                    IconButton(onClick = { vm.interruptShell() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Interrupt command")
                    }
                }
                IconButton(onClick = { vm.clearTerminal() }) {
                    Icon(Icons.Default.CleaningServices, contentDescription = "Clear output")
                }
                IconButton(onClick = { if (running) vm.stopShell() else vm.startShell() }) {
                    Icon(
                        if (running) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                        contentDescription = if (running) "Stop shell" else "Start shell",
                    )
                }
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
        } else if (!running && lines.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.Terminal,
                    "Shell not started",
                    "Starts a real /system/bin/sh process in ${project!!.name}. Output is genuine — nothing is simulated.",
                    "Start shell",
                    { vm.startShell() },
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(HarnessColors.Base),
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
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank()) {
                                if (input.trim() == "clear") vm.clearTerminal() else vm.runCommand(input)
                                input = ""; historyIndex = -1
                            }
                        }),
                    )
                    FilledIconButton(
                        onClick = {
                            if (input.trim() == "clear") vm.clearTerminal() else vm.runCommand(input)
                            input = ""; historyIndex = -1
                        },
                        enabled = project != null && input.isNotBlank(),
                        shape = RoundedCornerShape(Radius.md),
                    ) { Icon(Icons.Default.KeyboardReturn, contentDescription = "Run command") }
                }
            }
        }
    }
}
