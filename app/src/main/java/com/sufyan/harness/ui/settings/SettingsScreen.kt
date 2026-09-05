package com.sufyan.harness.ui.settings

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sufyan.harness.BuildConfig
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing
import com.sufyan.harness.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    vm: HarnessViewModel,
    onThemeChanged: (ThemeMode) -> Unit,
    onModels: () -> Unit,
    onToolchains: () -> Unit,
    onStorage: () -> Unit,
) {
    val s = vm.settings
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var masked by remember { mutableStateOf(vm.secure.maskedApiKey()) }
    val connection by vm.connectionResult.collectAsState()
    var theme by remember { mutableStateOf(s.themeMode) }
    var editorFont by remember { mutableIntStateOf(s.editorFontSize) }
    var termFont by remember { mutableIntStateOf(s.terminalFontSize) }
    var lineNumbers by remember { mutableStateOf(s.lineNumbers) }
    var wordWrap by remember { mutableStateOf(s.wordWrap) }
    var tabSize by remember { mutableIntStateOf(s.tabSize) }
    var systemPrompt by remember { mutableStateOf(s.systemPrompt) }
    var temperature by remember { mutableFloatStateOf(s.temperature) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Settings", subtitle = "Sufyan Harness ${BuildConfig.VERSION_NAME}")
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            // AI ---------------------------------------------------------------
            SectionHeader("AI")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow("Provider", "OpenRouter", Icons.Default.Cloud)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Model",
                    vm.active.collectAsState().value?.modelId ?: s.modelId,
                    Icons.Default.Memory,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    onClick = onModels,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(Spacing.lg)) {
                    Text("OpenRouter API key", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        masked?.let { "Stored securely: $it" }
                            ?: "Encrypted with the Android Keystore. Never logged or displayed in full.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        placeholder = { Text("sk-or-v1-...") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.md),
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PrimaryButton("Save key", {
                            vm.saveApiKey(keyInput); keyInput = ""; masked = vm.secure.maskedApiKey()
                        }, enabled = keyInput.isNotBlank())
                        SecondaryButton("Test", { vm.testConnection() }, enabled = masked != null)
                        if (masked != null) {
                            SecondaryButton("Delete", { vm.clearApiKey(); masked = null })
                        }
                    }
                    connection?.let {
                        Spacer(Modifier.height(Spacing.sm))
                        StatusChip(it, if (it.startsWith("Failed")) StatusKind.Error else StatusKind.Ok)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(Spacing.lg)) {
                    Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = temperature, onValueChange = { temperature = it; s.temperature = it }, valueRange = 0f..1.5f)
                    Spacer(Modifier.height(Spacing.sm))
                    Text("System prompt (appended)", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it; s.systemPrompt = it },
                        placeholder = { Text("Project conventions, style rules...") },
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Runtime ----------------------------------------------------------
            SectionHeader("Runtime & toolchains")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow(
                    "Toolchains",
                    "Detect shell, git, Node, npm, curl, OpenSSL",
                    Icons.Default.Construction,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    onClick = onToolchains,
                )
            }

            // Appearance -------------------------------------------------------
            SectionHeader("Appearance")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                Column(Modifier.padding(Spacing.lg)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(
                                selected = theme == m,
                                onClick = { theme = m; s.themeMode = m; onThemeChanged(m) },
                                label = { Text(m.name) },
                                shape = RoundedCornerShape(Radius.pill),
                            )
                        }
                    }
                }
            }

            // Editor -----------------------------------------------------------
            SectionHeader("Editor")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                StepperRow("Font size", editorFont) { editorFont = it; s.editorFontSize = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Line numbers", icon = Icons.Default.FormatListNumbered, trailing = {
                    Switch(checked = lineNumbers, onCheckedChange = { lineNumbers = it; s.lineNumbers = it })
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Word wrap", icon = Icons.Default.WrapText, trailing = {
                    Switch(checked = wordWrap, onCheckedChange = { wordWrap = it; s.wordWrap = it })
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                StepperRow("Tab size", tabSize) { tabSize = it; s.tabSize = it }
            }

            // Terminal ---------------------------------------------------------
            SectionHeader("Terminal")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                StepperRow("Font size", termFont) { termFont = it; s.terminalFontSize = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Shell", "/system/bin/sh", Icons.Default.Terminal)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("History", "${vm.commandHistory.size} commands this session", Icons.Default.History)
            }

            // Security ---------------------------------------------------------
            SectionHeader("Security & storage")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow("Credentials", if (masked != null) "1 key stored (encrypted)" else "No credentials stored", Icons.Default.Lock)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Clear all credentials", "Removes stored keys from this device", Icons.Default.LockReset, onClick = { confirmClear = true })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Storage",
                    "Projects, runtime and exports in use on this device",
                    Icons.Default.Storage,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    onClick = onStorage,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Workspace", vm.workspace.root.absolutePath, Icons.Default.Folder)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Privacy",
                    "Prompts and project context are sent to OpenRouter only when you send a message. Nothing else leaves the device.",
                    Icons.Default.PrivacyTip,
                )
            }

            // About ------------------------------------------------------------
            SectionHeader("About")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow("Sufyan Harness", "AI Development Workspace for Android", Icons.Default.Info)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE}", Icons.Default.Tag)
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            "Clear all credentials?",
            "Your OpenRouter API key will be permanently removed from this device. AI features stop working until you add a key again.",
            "Clear",
            destructive = true,
            onConfirm = { vm.secure.clearAll(); masked = null; confirmClear = false; vm.notify("Credentials cleared.") },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun StepperRow(label: String, value: Int, onChange: (Int) -> Unit) {
    SettingRow(label, "$value", trailing = {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Default.Remove, contentDescription = "Decrease $label") }
            IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Default.Add, contentDescription = "Increase $label") }
        }
    })
}
