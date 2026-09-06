package com.sufyan.harness.ui.settings

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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.RuntimeState
import com.sufyan.harness.ui.components.AppTopBar
import com.sufyan.harness.ui.components.ConfirmDialog
import com.sufyan.harness.ui.components.HarnessCard
import com.sufyan.harness.ui.components.SectionHeader
import com.sufyan.harness.ui.components.SettingRow
import com.sufyan.harness.ui.components.StatusChip
import com.sufyan.harness.ui.components.StatusKind
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Spacing

/**
 * §52 — storage manager. Every number is computed from the real files/directories it names; the
 * cleanup actions are deliberately safe and never touch project files silently.
 */
@Composable
fun StorageScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val projects by vm.projects.collectAsState()
    val runtime by vm.linux.status.collectAsState()
    var confirmClearExports by remember { mutableStateOf(false) }
    var confirmClearBuild by remember { mutableStateOf(false) }

    // Sizes are measured off the main thread and delivered through the ViewModel (§52).
    val snapshot by vm.storage.collectAsState()
    LaunchedEffect(projects.size) { vm.refreshStorage() }
    val total = snapshot.projectsTotal + snapshot.runtimeSize + snapshot.exportsSize + snapshot.buildCacheSize

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Storage",
            subtitle = "Total ${formatBytes(total)}",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.refreshStorage() }) { Icon(Icons.Default.Refresh, contentDescription = "Recalculate") }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            HarnessCard {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StorageRow("Projects", snapshot.projectsTotal)
                    StorageRow("Linux runtime", snapshot.runtimeSize)
                    StorageRow("Exported archives", snapshot.exportsSize)
                    StorageRow("Build output & caches", snapshot.buildCacheSize)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row {
                        Text("Total", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text(formatBytes(total), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            SectionHeader("Projects")
            if (snapshot.projects.isEmpty()) {
                Text(
                    "No projects yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                snapshot.projects.forEach { (name, size) ->
                    HarnessCard {
                        SettingRow(name, formatBytes(size), Icons.Default.Storage)
                    }
                }
            }

            SectionHeader("Runtime")
            HarnessCard {
                Column(Modifier.padding(Spacing.lg)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Linux runtime", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        StatusChip(
                            runtime.state.name,
                            when (runtime.state) {
                                RuntimeState.Installed -> StatusKind.Ok
                                RuntimeState.Failed -> StatusKind.Error
                                else -> StatusKind.Neutral
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(runtime.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionHeader("Clean up")
            HarnessCard {
                SettingRow(
                    "Clear exported archives",
                    "Removes .zip files this app created. Project files are untouched.",
                    Icons.Default.DeleteSweep,
                    onClick = { confirmClearExports = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Clear build output & caches",
                    "Deletes build, .gradle, dist and node_modules/.cache in every project. Source files are untouched.",
                    Icons.Default.DeleteSweep,
                    onClick = { confirmClearBuild = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Clear terminal output & history",
                    "Empties every session buffer and the saved command history. Running shells keep running.",
                    Icons.Default.DeleteSweep,
                    onClick = { vm.clearTerminalLogs() },
                )
            }

            Text(
                "Never deletes project files silently. Project data is only removed from the project's own Delete action.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmClearExports) {
        ConfirmDialog(
            "Clear exported archives?",
            "Deletes the .zip files this app generated under exports. It does not touch any project.",
            "Clear",
            destructive = true,
            onConfirm = { vm.clearExports(); confirmClearExports = false },
            onDismiss = { confirmClearExports = false },
        )
    }

    if (confirmClearBuild) {
        ConfirmDialog(
            "Clear build output?",
            "Deletes generated build directories and caches in all projects. Nothing you wrote is removed, " +
                "but the next build will take longer.",
            "Clear",
            destructive = true,
            onConfirm = { vm.clearBuildCache(); confirmClearBuild = false },
            onDismiss = { confirmClearBuild = false },
        )
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(formatBytes(bytes), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format("%.1f MB", b / 1048576.0)
}
