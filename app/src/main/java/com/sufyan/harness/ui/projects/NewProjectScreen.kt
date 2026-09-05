package com.sufyan.harness.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.data.Template
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

@Composable
fun NewProjectScreen(vm: HarnessViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(Template.Web) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Create Project", subtitle = "New workspace", onBack = onDone)
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

            Column {
                Text("Location", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.sm))
                HarnessCard {
                    Text(
                        vm.workspace.root.absolutePath,
                        style = com.sufyan.harness.ui.theme.MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
            }

            Column {
                Text("Template", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(Spacing.sm))
                HarnessCard {
                    Template.entries.forEachIndexed { i, t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = template == t, onClick = { template = t })
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = template == t, onClick = { template = t })
                            Spacer(Modifier.width(Spacing.sm))
                            Column {
                                Text(t.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    t.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (i < Template.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            if (template == Template.React) {
                UnavailableNotice(
                    "npm install",
                    "React scaffolding writes real source files, but dependencies must be installed from the Terminal once Node is available.",
                )
            }

            error?.let { ErrorState("Could not create project", it) }
        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(Spacing.md))
                PrimaryButton(
                    if (creating) "Creating..." else "Create Project",
                    onClick = {
                        creating = true
                        vm.createProject(name, template).fold(
                            { creating = false; onDone() },
                            { creating = false; error = it.message ?: "Unknown error." },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = name.isNotBlank() && !creating,
                    icon = Icons.Default.Add,
                )
            }
        }
    }
}
