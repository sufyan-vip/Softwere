package com.sufyan.harness.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.Routes
import com.sufyan.harness.data.Project
import com.sufyan.harness.data.ProjectType
import com.sufyan.harness.runtime.RuntimeState
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.components.AppTopBar
import com.sufyan.harness.ui.components.HarnessCard
import com.sufyan.harness.ui.components.PrimaryButton
import com.sufyan.harness.ui.components.SecondaryButton
import com.sufyan.harness.ui.components.SectionHeader
import com.sufyan.harness.ui.components.SettingRow
import com.sufyan.harness.ui.components.StatusChip
import com.sufyan.harness.ui.components.StatusKind
import com.sufyan.harness.ui.components.UnavailableNotice
import com.sufyan.harness.ui.theme.Spacing

/**
 * §12 — the project's own screen. Everything on it is read from the real workspace, the runtime and
 * git; nothing here is a placeholder status.
 */
@Composable
fun ProjectDetailScreen(
    vm: HarnessViewModel,
    project: Project?,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    if (project == null) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("Project", subtitle = "No project open", onBack = onBack)
            com.sufyan.harness.ui.components.EmptyState(
                Icons.Default.Code,
                "Open a project first",
                "Project details, git state and checkpoints come from the project that is currently open.",
            )
        }
        return
    }
    val git by vm.git.collectAsState()
    val runtime by vm.linux.status.collectAsState()
    val checkpoints by vm.checkpoints.collectAsState()

    LaunchedEffect(project.id) {
        vm.refreshGit()
        vm.refreshCheckpoints()
        vm.refreshRuntime()
    }

    val files = vm.workspace.fileCount(project)
    val size = vm.workspace.sizeOf(project)

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = project.name,
            subtitle = "Updated ${relativeTime(project.updatedAt)}",
            onBack = onBack,
            actions = {
                IconButton(onClick = { onNavigate(Routes.PROJECT_SETTINGS) }) {
                    Icon(Icons.Default.Tune, contentDescription = "Project settings")
                }
            },
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            HarnessCard {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        listOf(project.kind.label, project.template.replaceFirstChar { it.uppercase() })
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(
                            when (runtime.state) {
                                RuntimeState.Installed -> "Linux runtime ready"
                                RuntimeState.NotInstalled -> "Linux runtime not installed"
                                RuntimeState.Failed -> "Linux runtime failed"
                                else -> "Linux runtime ${runtime.state.name.lowercase()}"
                            },
                            when (runtime.state) {
                                RuntimeState.Installed -> StatusKind.Ok
                                RuntimeState.Failed -> StatusKind.Error
                                else -> StatusKind.Warn
                            },
                        )
                        StatusChip(
                            when {
                                git == null -> "Git not checked"
                                git?.isRepo != true -> "No git repo"
                                else -> "${git?.branch ?: "detached"} • ${git?.changes?.size ?: 0} changed"
                            },
                            when {
                                git?.isRepo == true && git?.changes?.isEmpty() == true -> StatusKind.Ok
                                git?.isRepo == true -> StatusKind.Warn
                                else -> StatusKind.Neutral
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "$files files • ${formatBytes(size)} • ${project.kind.languages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        vm.workspace.projectDir(project).absolutePath,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader("Actions")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryButton(
                        "AI build",
                        { vm.open(project); onNavigate(Routes.CHAT) },
                        Modifier.weight(1f),
                        icon = Icons.Default.AutoAwesome,
                    )
                    SecondaryButton("Editor", { vm.open(project); onNavigate(Routes.EDITOR) }, Modifier.weight(1f), icon = Icons.Default.Code)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecondaryButton("Terminal", { vm.open(project); onNavigate(Routes.TERMINAL) }, Modifier.weight(1f), icon = Icons.Default.Terminal)
                    SecondaryButton("Preview", { vm.open(project); onNavigate(Routes.PREVIEW) }, Modifier.weight(1f), icon = Icons.Default.PlayArrow)
                }
            }

            if (project.kind == ProjectType.AndroidApp) {
                UnavailableNotice(
                    "Android build",
                    "This project type needs the on-device Gradle build and install pipeline (V3 phase 11). " +
                        "The files and the agent already work; only the APK build step is missing.",
                )
            }

            Column {
                SectionHeader("Sections")
                SettingRow(
                    "Files",
                    subtitle = "$files files in the workspace",
                    icon = Icons.Default.Code,
                    onClick = { vm.open(project); onNavigate(Routes.EDITOR) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Git & checkpoints",
                    subtitle = if (checkpoints.isEmpty()) "No checkpoints yet" else "${checkpoints.size} checkpoints",
                    icon = Icons.Default.PlayArrow,
                    onClick = { vm.open(project); onNavigate(Routes.GIT) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Project settings",
                    subtitle = "Model, preview port, delete",
                    icon = Icons.Default.Tune,
                    onClick = { vm.open(project); onNavigate(Routes.PROJECT_SETTINGS) },
                )
            }

            Text(
                "Preview port ${project.previewPort} • model ${project.modelId ?: vm.settings.modelId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
