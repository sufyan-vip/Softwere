package com.sufyan.harness.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.Checkpoint
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

@Composable
fun GitScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val git by vm.git.collectAsState()
    val diff by vm.diff.collectAsState()
    val log by vm.gitLog.collectAsState()
    val checkpoints by vm.checkpoints.collectAsState()
    var commitMessage by remember { mutableStateOf<String?>(null) }
    var checkpointLabel by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<Checkpoint?>(null) }

    LaunchedEffect(Unit) { vm.refreshGit(); vm.refreshCheckpoints(); vm.loadDiff() }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Changes",
            subtitle = git?.branch?.let { "branch $it" } ?: "Git & checkpoints",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.refreshGit(); vm.loadDiff(); vm.refreshCheckpoints() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            },
        )

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Status", "Diff", "History", "Checkpoints").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> StatusTab(vm, git, onCommit = { commitMessage = "" })
                1 -> DiffTab(diff)
                2 -> HistoryTab(log)
                else -> CheckpointsTab(vm, checkpoints, onNew = { checkpointLabel = "" }, onRestore = { restoring = it })
            }
        }
    }

    commitMessage?.let {
        var msg by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { commitMessage = null },
            title = { Text("Commit changes") },
            text = { OutlinedTextField(value = msg, onValueChange = { m -> msg = m }, label = { Text("Message") }, singleLine = true) },
            confirmButton = { TextButton(enabled = msg.isNotBlank(), onClick = { vm.commit(msg); commitMessage = null }) { Text("Commit") } },
            dismissButton = { TextButton(onClick = { commitMessage = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    checkpointLabel?.let {
        var label by remember { mutableStateOf("Before AI changes") }
        AlertDialog(
            onDismissRequest = { checkpointLabel = null },
            title = { Text("Create checkpoint") },
            text = {
                Column {
                    Text(
                        "Copies every file in this project so you can roll back later. Works without git.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(value = label, onValueChange = { l -> label = l }, singleLine = true, label = { Text("Label") })
                }
            },
            confirmButton = { TextButton(onClick = { vm.createCheckpoint(label); checkpointLabel = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { checkpointLabel = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    restoring?.let { cp ->
        ConfirmDialog(
            "Restore “${cp.label}”?",
            "Every file in the project is replaced with the checkpoint contents from ${cp.when_}. Unsaved work will be lost.",
            "Restore",
            destructive = true,
            onConfirm = { vm.restoreCheckpoint(cp); restoring = null },
            onDismiss = { restoring = null },
        )
    }
}

@Composable
private fun StatusTab(vm: HarnessViewModel, git: com.sufyan.harness.runtime.GitStatus?, onCommit: () -> Unit) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        when {
            git == null -> LoadingState("Reading repository status...")
            git.error != null -> UnavailableNotice("Git", git.error!!)
            !git.isRepo -> {
                EmptyState(
                    Icons.Default.Difference,
                    "Not a Git repository",
                    "Initialise a repository to track changes, view diffs and commit. Checkpoints work either way.",
                    "git init",
                    { vm.gitInit() },
                )
            }
            else -> {
                HarnessCard {
                    Column(Modifier.padding(Spacing.lg)) {
                        Text("Changes", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            StatusChip("${git.modified} modified", StatusKind.Warn)
                            StatusChip("${git.added} new", StatusKind.Ok)
                            StatusChip("${git.deleted} deleted", StatusKind.Error)
                        }
                    }
                }
                if (git.changes.isEmpty()) {
                    Text("Working tree is clean.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.weight(1f, false), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        items(git.changes) { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(c.label, if (c.label == "deleted") StatusKind.Error else StatusKind.Info)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(c.path, style = MonoStyle.copy(fontSize = 12.sp), maxLines = 1)
                            }
                        }
                    }
                    PrimaryButton("Commit all changes", onCommit, Modifier.fillMaxWidth(), icon = Icons.Default.Check)
                }
            }
        }
    }
}

@Composable
private fun DiffTab(diff: String?) {
    when {
        diff == null -> LoadingState("Computing diff...")
        diff.isBlank() -> EmptyState(Icons.Default.Difference, "No differences", "Nothing has changed since the last commit.")
        else -> LazyColumn(
            Modifier.fillMaxSize().background(HarnessColors.Base).padding(Spacing.md),
        ) {
            items(diff.lines()) { line ->
                Text(
                    line,
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = when {
                        line.startsWith("+++") || line.startsWith("---") -> HarnessColors.TextMuted
                        line.startsWith("+") -> HarnessColors.Ok
                        line.startsWith("-") -> HarnessColors.Danger
                        line.startsWith("@@") -> HarnessColors.Info
                        else -> HarnessColors.TextSecondary
                    },
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun HistoryTab(log: List<String>) {
    if (log.isEmpty()) {
        EmptyState(Icons.Default.History, "No commits", "Commit your changes to build a history.")
    } else {
        LazyColumn(contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(log) { entry ->
                val parts = entry.split('\t')
                HarnessCard {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(parts.getOrElse(2) { entry }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "${parts.getOrElse(0) { "" }} • ${parts.getOrElse(1) { "" }}",
                            style = MonoStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointsTab(
    vm: HarnessViewModel,
    checkpoints: List<Checkpoint>,
    onNew: () -> Unit,
    onRestore: (Checkpoint) -> Unit,
) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        PrimaryButton("New checkpoint", onNew, Modifier.fillMaxWidth(), icon = Icons.Default.Save)
        if (checkpoints.isEmpty()) {
            EmptyState(Icons.Default.Backup, "No checkpoints", "Create one before letting the agent make large changes.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(checkpoints, key = { it.id }) { cp ->
                    HarnessCard {
                        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cp.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(cp.when_, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRestore(cp) }) { Text("Restore") }
                            IconButton(onClick = { vm.deleteCheckpoint(cp) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete checkpoint", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
