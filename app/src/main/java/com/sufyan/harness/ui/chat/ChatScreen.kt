package com.sufyan.harness.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.sufyan.harness.AgentPhase
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.UiMessage
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The agent workspace (V3 §13-§17). Three layers, top to bottom:
 * the conversation, each turn's collapsible agent activity, and the final answer with what changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: HarnessViewModel,
    onPickModel: () -> Unit,
    onReviewChanges: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
) {
    val project by vm.active.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    val phase by vm.agentPhase.collectAsState()
    val status by vm.agentStatus.collectAsState()
    var input by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var openActivity by remember { mutableStateOf(setOf<Long>()) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    // Clipboard.setText is a suspend call, so the copy action needs a coroutine scope.
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size, generating) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // §4 — an editor "AI action" can pre-fill the composer via a pending prompt.
    val pendingPrompt by vm.pendingPrompt.collectAsState()
    LaunchedEffect(pendingPrompt, project?.id) {
        if (pendingPrompt != null) {
            input = pendingPrompt!!
            vm.consumePendingPrompt()
        }
    }

    val model = project?.modelId ?: vm.settings.modelId
    val previewRunning = vm.devServer?.state?.value?.running == true

    Column(Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            title = project?.name ?: "AI Chat",
            subtitle = "${model.substringAfterLast('/')} · ${if (project != null) "ready" else "no project"}",
            actions = {
                TextButton(onClick = onPickModel) {
                    Text(model.substringAfterLast('/').take(16), style = MaterialTheme.typography.labelSmall)
                    Icon(Icons.Default.ExpandMore, contentDescription = "Change model", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Chat menu")
                }
                DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Clear conversation") }, onClick = { vm.clearConversation(); showMenu = false })
                    DropdownMenuItem(text = { Text("Retry last message") }, onClick = { vm.retryLast(); showMenu = false })
                }
            },
        )

        when {
            project == null -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.AutoAwesome,
                    "No project open",
                    "Open or create a project from the Projects tab. The agent works inside one project at a time.",
                )
            }
            messages.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column {
                    EmptyState(
                        Icons.Outlined.AutoAwesome,
                        "Ask the agent to build something",
                        "It inspects your project, edits real files and runs real commands. Every change is reviewable in Git & Checkpoints.",
                    )
                    Column(Modifier.padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(
                            "List the files in this project",
                            "Add a dark theme to the page",
                            "Explain what this project does",
                        ).forEach { s ->
                            SuggestionChip(s) { input = s }
                        }
                    }
                }
            }
            else -> LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                items(messages) { msg ->
                    val isLast = msg.timestamp == messages.last().timestamp
                    val expanded = generating && isLast || msg.timestamp in openActivity
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        MessageTurn(
                            msg = msg,
                            expanded = expanded,
                            onToggleActivity = {
                                openActivity = if (msg.timestamp in openActivity) openActivity - msg.timestamp
                                else openActivity + msg.timestamp
                            },
                            onCopy = { scope.launch { clipboard.setText(AnnotatedString(msg.text)) } },
                            onRetry = { vm.retryLast() },
                            onRerun = { vm.runCommand(it) },
                            onReviewChanges = onReviewChanges,
                            onOpenPreview = onOpenPreview,
                        )
                        if (msg.role == "assistant" && msg.tools.isNotEmpty() && !(generating && isLast)) {
                            SessionSummaryCard(
                                summarizeTurn(msg, previewRunning),
                                onReviewDiff = onReviewChanges,
                                onOpenPreview = onOpenPreview,
                                onContinue = { vm.send("Continue from where you stopped. Verify with a build or a test run first.") },
                                enabled = !generating,
                            )
                        }
                    }
                }
            }
        }

        AgentStateBar(phase, status, onStop = { vm.stopGeneration() }, modifier = Modifier.padding(bottom = Spacing.sm))

        Composer(
            value = input,
            onValueChange = { input = it },
            generating = generating,
            enabled = project != null,
            model = model,
            onAttach = { showAttach = true },
            onPickModel = onPickModel,
            onSend = { vm.send(input); input = "" },
            onStop = { vm.stopGeneration() },
        )
    }

    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = Spacing.xl)) {
                Text("Add context", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(Spacing.lg))
                val files = vm.files
                SettingRow("Mention a file", subtitle = "Insert @path into the prompt", icon = Icons.Default.AlternateEmail)
                val list = files?.tree(emptySet())?.filter { !it.isDir }?.take(30).orEmpty()
                list.forEach { node ->
                    val rel = files!!.relativePath(node.file)
                    SettingRow(rel, icon = Icons.Default.InsertDriveFile, onClick = {
                        input = if (input.isBlank()) "@$rel " else "$input @$rel "
                        showAttach = false
                    })
                }
                if (list.isEmpty()) {
                    Text(
                        "This project has no files yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MessageTurn(
    msg: UiMessage,
    expanded: Boolean,
    onToggleActivity: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onRerun: (String) -> Unit,
    onReviewChanges: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    when (msg.role) {
        "user" -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(Spacing.md),
            ) { Text(msg.text, style = MaterialTheme.typography.bodyMedium) }
        }

        "error" -> ErrorState(msg.text.substringBefore('\n'), msg.text.substringAfter('\n').trim(), onRetry = onRetry)

        else -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Sufyan Harness AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (msg.tools.isNotEmpty()) {
                ActivityTimeline(
                    tools = msg.tools,
                    expanded = expanded,
                    onToggle = onToggleActivity,
                    onRerun = onRerun,
                )
            }
            if (msg.text.isNotBlank()) {
                FinalAnswerCard(
                    text = msg.text,
                    changedFiles = changedFilesOf(msg),
                    onReviewChanges = onReviewChanges.takeIf { msg.tools.any { it.done && it.name in setOf("write_file", "edit_file", "delete_file") } },
                    onOpenPreview = onOpenPreview.takeIf { msg.tools.any { it.done && it.ok && it.name == "run_command" } || msg.tools.any { it.name == "write_file" } },
                    onCopy = onCopy,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    enabled: Boolean,
    model: String,
    onAttach: () -> Unit,
    onPickModel: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(Modifier.padding(Spacing.md)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    IconButton(onClick = onAttach, enabled = enabled) {
                        Icon(Icons.Default.Add, contentDescription = "Attach file context")
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                        placeholder = { Text("Ask Sufyan Harness AI...", style = MaterialTheme.typography.bodyMedium) },
                        enabled = enabled,
                        maxLines = 6,
                        shape = RoundedCornerShape(Radius.lg),
                    )
                    if (generating) {
                        FilledIconButton(onClick = onStop, shape = RoundedCornerShape(Radius.md)) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop generating")
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = enabled && value.isNotBlank(),
                            shape = RoundedCornerShape(Radius.md),
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Send message")
                        }
                    }
                }
                Row(
                    Modifier.padding(start = Spacing.xxl, top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Model: ${model.substringAfterLast('/')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onPickModel),
                    )
                }
            }
        }
    }
}
