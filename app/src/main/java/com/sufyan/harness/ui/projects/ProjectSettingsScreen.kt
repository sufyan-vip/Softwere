package com.sufyan.harness.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

@Composable
fun ProjectSettingsScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val project by vm.active.collectAsState()
    val p = project
    if (p == null) {
        Column {
            AppTopBar("Project Settings", onBack = onBack)
            EmptyState(Icons.Default.Folder, "No project open", "Open a project first.")
        }
        return
    }

    var name by remember(p.id) { mutableStateOf(p.name) }
    var port by remember(p.id) { mutableStateOf(p.previewPort.toString()) }
    val git by vm.git.collectAsState()

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Project Settings", subtitle = p.name, onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),
        ) {
            SectionHeader("General")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                Column(Modifier.padding(Spacing.lg)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton("Save name", { vm.renameProject(p, name) }, enabled = name != p.name && name.isNotBlank())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Location", vm.workspace.projectDir(p).absolutePath, Icons.Default.Folder)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Default shell", "/system/bin/sh", Icons.Default.Terminal)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Storage",
                    "${formatBytes(vm.workspace.sizeOf(p))} • ${vm.workspace.fileCount(p)} files",
                    Icons.Default.Storage,
                )
            }

            SectionHeader("AI")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow("Model", p.modelId ?: "${vm.settings.modelId} (global default)", Icons.Default.Memory)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Agent permissions",
                    "File read/write and shell commands are enabled inside this project directory only.",
                    Icons.Default.Security,
                )
            }

            SectionHeader("Preview")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                Column(Modifier.padding(Spacing.lg)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(Radius.md),
                        textStyle = MonoStyle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton("Save port", {
                        p.previewPort = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 5173
                        vm.workspace.update(p)
                        vm.notify("Preview port set to ${p.previewPort}")
                    })
                }
            }

            SectionHeader("Git")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow(
                    "Repository",
                    when {
                        git == null -> "Checking..."
                        git!!.error != null -> git!!.error!!
                        git!!.isRepo -> "Initialised"
                        else -> "Not a repository"
                    },
                    Icons.Default.Difference,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Branch", git?.branch ?: "—", Icons.Default.AltRoute)
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
