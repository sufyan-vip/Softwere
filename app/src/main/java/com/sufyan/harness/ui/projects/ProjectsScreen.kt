package com.sufyan.harness.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.data.Project
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing
import java.util.Calendar
import java.util.concurrent.TimeUnit

private enum class Sort(val label: String) { Recent("Recent"), Name("Name"), Size("Size") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    vm: HarnessViewModel,
    onNewProject: () -> Unit,
    onOpenChat: () -> Unit,
    onProjectSettings: () -> Unit,
    onOpenDetails: () -> Unit = {},
) {
    val projects by vm.projects.collectAsState()
    val active by vm.active.collectAsState()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.Recent) }
    var menuFor by remember { mutableStateOf<Project?>(null) }
    var confirmDelete by remember { mutableStateOf<Project?>(null) }
    var renaming by remember { mutableStateOf<Project?>(null) }

    LaunchedEffect(Unit) { vm.refreshProjects() }

    val filtered = remember(projects, query, sort) {
        projects.filter { it.name.contains(query, true) || it.template.contains(query, true) }
            .let {
                when (sort) {
                    Sort.Recent -> it.sortedByDescending { p -> p.updatedAt }
                    Sort.Name -> it.sortedBy { p -> p.name.lowercase() }
                    Sort.Size -> it.sortedByDescending { p -> vm.workspace.sizeOf(p) }
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "Sufyan Harness",
            subtitle = "Your Workspace · Build. Code. Run. Ship — from Android.",
            actions = {
                IconButton(onClick = { vm.refreshProjects() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh projects")
                }
            },
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                if (active != null) {
                    ActiveProjectBanner(vm, active!!, onOpenChat, onProjectSettings)
                    Spacer(Modifier.height(Spacing.xs))
                }
            }
            item {
                PrimaryButton(
                    "New Project",
                    onNewProject,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Add,
                )
            }
            if (projects.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search projects") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.md),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Sort.entries.forEach { s ->
                            FilterChip(
                                selected = sort == s,
                                onClick = { sort = s },
                                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(Radius.pill),
                            )
                        }
                    }
                }
                item { SectionHeader("Recent projects", Modifier.padding(horizontal = 0.dp)) }
            }

            if (projects.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.FolderOpen,
                        "No projects yet",
                        "Create your first project to start building. Everything lives in a private workspace on this device.",
                        "New Project",
                        onNewProject,
                    )
                }
            } else if (filtered.isEmpty()) {
                item {
                    EmptyState(Icons.Default.SearchOff, "No matches", "No project matches “$query”.")
                }
            } else {
                items(filtered, key = { it.id }) { project ->
                    ProjectCard(
                        vm = vm,
                        project = project,
                        isActive = project.id == active?.id,
                        onOpen = { vm.open(project); onOpenChat() },
                        onMenu = { menuFor = project },
                    )
                }
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }

    menuFor?.let { p ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = Spacing.xl)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
                SettingRow("Open", icon = Icons.Default.OpenInNew, onClick = { vm.open(p); menuFor = null; onOpenChat() })
                SettingRow("Details", subtitle = "Runtime, git, checkpoints, actions", icon = Icons.Default.Info, onClick = { vm.open(p); menuFor = null; onOpenDetails() })
                SettingRow("Rename", icon = Icons.Default.DriveFileRenameOutline, onClick = { renaming = p; menuFor = null })
                SettingRow(
                    "Storage",
                    subtitle = "${formatBytes(vm.workspace.sizeOf(p))} • ${vm.workspace.fileCount(p)} files",
                    icon = Icons.Default.Storage,
                )
                SettingRow("Delete", subtitle = "Removes all files permanently", icon = Icons.Default.DeleteOutline, onClick = {
                    confirmDelete = p; menuFor = null
                })
            }
        }
    }

    renaming?.let { p ->
        var name by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = { TextButton(onClick = { vm.renameProject(p, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    confirmDelete?.let { p ->
        ConfirmDialog(
            "Delete ${p.name}?",
            "All files in this project will be permanently deleted from the device. This cannot be undone.",
            "Delete",
            destructive = true,
            onConfirm = { vm.deleteProject(p); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun ActiveProjectBanner(
    vm: HarnessViewModel,
    project: Project,
    onOpenChat: () -> Unit,
    onSettings: () -> Unit,
) {
    HarnessCard {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip("Current workspace", StatusKind.Ok, Icons.Default.Bolt)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "Project settings", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(project.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${project.template} • ${vm.workspace.fileCount(project)} files • ${formatBytes(vm.workspace.sizeOf(project))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PrimaryButton("Open AI Chat", onOpenChat, Modifier.weight(1f), icon = Icons.Default.AutoAwesome)
                SecondaryButton("Close", { vm.closeProject() })
            }
        }
    }
}

@Composable
private fun ProjectCard(
    vm: HarnessViewModel,
    project: Project,
    isActive: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    HarnessCard(onClick = onOpen) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(accentFor(project.template).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(project.template),
                    contentDescription = null,
                    tint = accentFor(project.template),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (isActive) {
                        Spacer(Modifier.width(Spacing.sm))
                        Dot(HarnessColors.Ok)
                    }
                }
                Text(
                    "${project.kind.label} • ${project.kind.languages}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Updated ${relativeTime(project.updatedAt)} • ${formatBytes(vm.workspace.sizeOf(project))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    val git by vm.git.collectAsState()
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(
                            when {
                                git == null -> "workspace open"
                                git?.isRepo != true -> "no git repo"
                                git?.changes?.isEmpty() == true -> "clean · ${git?.branch ?: "detached"}"
                                else -> "${git?.changes?.size ?: 0} changed · ${git?.branch ?: "detached"}"
                            },
                            when {
                                git?.isRepo == true && git?.changes?.isEmpty() == true -> StatusKind.Ok
                                git?.isRepo == true -> StatusKind.Warn
                                else -> StatusKind.Neutral
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = "Project menu for ${project.name}")
            }
        }
    }
}

private fun iconFor(template: String) = when (template) {
    "react" -> Icons.Default.Web
    "node" -> Icons.Default.Dns
    "web" -> Icons.Default.Language
    else -> Icons.Default.Folder
}

private fun accentFor(template: String): Color = when (template) {
    "react" -> HarnessColors.Info
    "node" -> HarnessColors.Ok
    "web" -> HarnessColors.Warn
    else -> HarnessColors.Accent
}

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Working late"
}

fun relativeTime(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    val min = TimeUnit.MILLISECONDS.toMinutes(d)
    val hr = TimeUnit.MILLISECONDS.toHours(d)
    val day = TimeUnit.MILLISECONDS.toDays(d)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        hr < 24 -> "$hr h ago"
        day == 1L -> "yesterday"
        day < 30 -> "$day days ago"
        else -> "${day / 30} months ago"
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format("%.1f MB", b / 1048576.0)
}
