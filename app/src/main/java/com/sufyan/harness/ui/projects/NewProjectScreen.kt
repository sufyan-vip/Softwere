package com.sufyan.harness.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.data.ProjectType
import com.sufyan.harness.ui.components.AppTopBar
import com.sufyan.harness.ui.components.ErrorState
import com.sufyan.harness.ui.components.HarnessCard
import com.sufyan.harness.ui.components.PrimaryButton
import com.sufyan.harness.ui.components.SectionHeader
import com.sufyan.harness.ui.components.SettingRow
import com.sufyan.harness.ui.components.StatusChip
import com.sufyan.harness.ui.components.StatusKind
import com.sufyan.harness.ui.components.UnavailableNotice
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

/**
 * §11 — the creation flow first asks what the user is building, then shows exactly what that
 * choice writes on disk. The chosen type is stored in the project metadata so the rest of the app
 * can behave per type. A type whose pipeline does not exist yet is shown but not selectable —
 * creating it would produce a project that cannot be built, which is the fake success §3 forbids.
 */
@Composable
fun NewProjectScreen(vm: HarnessViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<ProjectType?>(null) }
    var initGit by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Create New Project", subtitle = "What are you building?", onBack = onDone)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column {
                Text("Project name", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    placeholder = { Text("Kitchen POS") },
                    singleLine = true,
                    isError = error != null,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ProjectType.entries.forEach { option ->
                    TypeCard(
                        option = option,
                        selected = type == option,
                        onSelect = { if (option.canCreate) type = option },
                    )
                }
            }

            type?.let { chosen ->
                HarnessCard {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        SectionHeader("This will be created")
                        Text(
                            chosen.template.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SettingRow(
                            "Framework / language",
                            subtitle = chosen.languages,
                            icon = null,
                        )
                        SettingRow("Runtime", subtitle = if (chosen == ProjectType.Node || chosen == ProjectType.WebApp) "Node — install from Toolchains" else "None needed", icon = null)
                        SettingRow(
                            "Initialise a git repository",
                            subtitle = "Runs a real git init in the project directory",
                            trailing = {
                                Switch(checked = initGit, onCheckedChange = { initGit = it })
                            },
                        )
                    }
                }
            }

            if (type == ProjectType.WebApp || type == ProjectType.Node) {
                UnavailableNotice(
                    "npm install",
                    "The scaffold writes real source files. Dependencies still have to be installed from the Terminal once Node is available in this runtime.",
                )
            }

            Column {
                Text("Location", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.sm))
                HarnessCard {
                    Text(
                        vm.workspace.root.absolutePath,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
            }

            error?.let { ErrorState("Could not create project", it) }
        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    if (type == null) "Pick what you are building first." else "Writes: ${type.template.description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                PrimaryButton(
                    if (creating) "Creating..." else "Create Project",
                    onClick = {
                        val chosen = type ?: return@PrimaryButton
                        creating = true
                        error = null
                        vm.createProject(name.trim(), chosen.template, chosen).fold(
                            { project ->
                                creating = false
                                if (initGit) vm.gitInit()
                                vm.notify("Created ${project.name}")
                                onDone()
                            },
                            { failure ->
                                creating = false
                                error = failure.message ?: "The project directory could not be created."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && type != null && !creating,
                    icon = Icons.Default.Add,
                )
            }
        }
    }
}

@Composable
private fun TypeCard(option: ProjectType, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            )
            .then(
                if (option.canCreate) Modifier.clickable(onClick = onSelect) else Modifier,
            )
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.sm)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                if (!option.canCreate) {
                    Spacer(Modifier.width(Spacing.sm))
                    StatusChip("not in this build", StatusKind.Warn)
                }
            }
            Text(
                if (option.canCreate) option.blurb else "Needs the on-device build pipeline (V3 phase 11).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (option.canCreate) {
            Text(
                option.template.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Scaffold ${option.template.label}" },
            )
        }
    }
}
