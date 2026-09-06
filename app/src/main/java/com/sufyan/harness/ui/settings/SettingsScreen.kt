package com.sufyan.harness.ui.settings

import androidx.compose.foundation.horizontalScroll
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
import com.sufyan.harness.data.AgentPermission
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
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
    onGithub: () -> Unit = {},
    onBuild: () -> Unit = {},
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
    var endpoint by remember { mutableStateOf(s.endpoint) }
    var confirmClear by remember { mutableStateOf(false) }
    var fallbackModel by remember { mutableStateOf(s.fallbackModelId) }
    var permission by remember { mutableStateOf(s.agentPermission) }
    var commandsEnabled by remember { mutableStateOf(s.agentCommandsEnabled) }
    var buildFixAttempts by remember { mutableIntStateOf(s.buildFixAttempts) }
    var agentSteps by remember { mutableIntStateOf(s.agentMaxSteps) }
    var autoContinue by remember { mutableStateOf(s.agentAutoContinue) }
    var contextBudget by remember { mutableIntStateOf(s.contextBudget) }
    var notifyDone by remember { mutableStateOf(s.notifyOnTaskComplete) }
    var termShell by remember { mutableStateOf(s.terminalShell) }
    var termEnv by remember { mutableStateOf(s.terminalEnv) }
    var termScrollback by remember { mutableIntStateOf(s.terminalScrollback) }
    var termClearOnNew by remember { mutableStateOf(s.terminalClearOnNewSession) }
    var termWrap by remember { mutableStateOf(s.terminalWordWrap) }
    var historyCount by remember { mutableIntStateOf(s.commandHistory().size) }

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
                    Text("Endpoint", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Blank uses the OpenRouter default. Any OpenAI-compatible provider works — tap one " +
                            "to fill it in, then paste that provider's own key above and pick one of its models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        PROVIDER_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = { endpoint = preset.url },
                                label = { Text(preset.label) },
                                shape = RoundedCornerShape(Radius.sm),
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        placeholder = { Text("https://openrouter.ai/api/v1") },
                        singleLine = true,
                        textStyle = MonoStyle,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    PROVIDER_PRESETS.firstOrNull { it.url == endpoint.trim() }?.let { preset ->
                        Text(
                            "${preset.label}: free tier ${preset.freeTier}. Key from ${preset.keyUrl}. " +
                                "Example model id: ${preset.exampleModel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    SecondaryButton("Save endpoint", { vm.setEndpoint(endpoint) }, enabled = endpoint != s.endpoint)
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

            // Agent ------------------------------------------------------------
            SectionHeader("Agent")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                Column(Modifier.padding(Spacing.lg)) {
                    Text("Permissions", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Controls when the agent must ask you before touching the device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    AgentPermission.entries.forEach { p ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(
                                selected = permission == p,
                                onClick = { permission = p; vm.setAgentPermission(p) },
                            )
                            Column {
                                Text(p.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    p.blurb,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    title = "Allow commands",
                    subtitle = if (commandsEnabled) {
                        "The agent can run real commands in the project directory."
                    } else {
                        "run_command is removed from the agent's tools entirely."
                    },
                    icon = Icons.Default.Terminal,
                    trailing = {
                        Switch(
                            checked = commandsEnabled,
                            onCheckedChange = { commandsEnabled = it; vm.setAgentCommandsEnabled(it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(Spacing.lg)) {
                    Text("Fallback model", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Used only when the main model is rate-limited or unavailable. The switch is announced in the chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = fallbackModel,
                        onValueChange = { fallbackModel = it },
                        placeholder = { Text("openai/gpt-4o-mini") },
                        singleLine = true,
                        textStyle = MonoStyle,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton(
                        "Save fallback",
                        { vm.setFallbackModel(fallbackModel.trim()) },
                        enabled = fallbackModel != s.fallbackModelId,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Agent steps per turn",
                    "$agentSteps tool steps before the turn pauses. Raise it for long tasks.",
                    icon = Icons.Default.Bolt,
                    trailing = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val v = (agentSteps - 4).coerceAtLeast(4); agentSteps = v; s.agentMaxSteps = v
                            }) { Icon(Icons.Default.Remove, contentDescription = "Fewer steps") }
                            IconButton(onClick = {
                                val v = (agentSteps + 4).coerceAtMost(100); agentSteps = v; s.agentMaxSteps = v
                            }) { Icon(Icons.Default.Add, contentDescription = "More steps") }
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Continue automatically",
                    if (autoContinue) {
                        "When a turn runs out of steps it resumes by itself, up to 5 times, and says so in the chat."
                    } else {
                        "A turn that runs out of steps waits for you to tap Continue."
                    },
                    icon = Icons.Default.PlayArrow,
                    trailing = {
                        Switch(checked = autoContinue, onCheckedChange = { autoContinue = it; s.agentAutoContinue = it })
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                StepperRow("Build-fix attempts", buildFixAttempts) {
                    val v = it.coerceIn(1, 10); buildFixAttempts = v; s.buildFixAttempts = v
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Context budget",
                    "$contextBudget tokens before older turns are summarised out",
                    Icons.Default.DataUsage,
                    trailing = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val v = (contextBudget - 4000).coerceAtLeast(4000); contextBudget = v; s.contextBudget = v
                            }) { Icon(Icons.Default.Remove, contentDescription = "Decrease context budget") }
                            IconButton(onClick = {
                                val v = (contextBudget + 4000).coerceAtMost(128000); contextBudget = v; s.contextBudget = v
                            }) { Icon(Icons.Default.Add, contentDescription = "Increase context budget") }
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Notify when a task finishes",
                    "A notification when a build, install or long command completes in the background",
                    Icons.Default.Notifications,
                    trailing = {
                        Switch(checked = notifyDone, onCheckedChange = { notifyDone = it; s.notifyOnTaskComplete = it })
                    },
                )
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Android build",
                    "Check the build requirements on this device and build an APK",
                    Icons.Default.Android,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    onClick = onBuild,
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
                SettingRow("Word wrap", icon = Icons.Default.WrapText, trailing = {
                    Switch(checked = termWrap, onCheckedChange = { termWrap = it; s.terminalWordWrap = it })
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Clear on new session",
                    "Wipes the visible output whenever a session starts",
                    Icons.Default.CleaningServices,
                    trailing = {
                        Switch(checked = termClearOnNew, onCheckedChange = { termClearOnNew = it; s.terminalClearOnNewSession = it })
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Scrollback",
                    "$termScrollback lines kept per session",
                    Icons.Default.UnfoldMore,
                    trailing = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val v = (termScrollback - 500).coerceAtLeast(200); termScrollback = v; s.terminalScrollback = v
                            }) { Icon(Icons.Default.Remove, contentDescription = "Decrease scrollback") }
                            IconButton(onClick = {
                                val v = (termScrollback + 500).coerceAtMost(20000); termScrollback = v; s.terminalScrollback = v
                            }) { Icon(Icons.Default.Add, contentDescription = "Increase scrollback") }
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(Spacing.lg)) {
                    Text("Shell binary", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The process the terminal actually launches. Change it only if the alternative really exists on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = termShell,
                        onValueChange = { termShell = it; s.terminalShell = it },
                        singleLine = true,
                        textStyle = MonoStyle,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text("Environment variables", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "One KEY=value per line, applied to every new session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = termEnv,
                        onValueChange = { termEnv = it; s.terminalEnv = it },
                        placeholder = { Text("TERM=xterm-256color") },
                        minLines = 2,
                        maxLines = 6,
                        textStyle = MonoStyle,
                        shape = RoundedCornerShape(Radius.md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "Command history",
                    "$historyCount commands remembered on this device",
                    Icons.Default.History,
                    trailing = {
                        TextButton(onClick = { s.clearCommandHistory(); historyCount = 0 }) { Text("Clear") }
                    },
                )
            }

            // Security ---------------------------------------------------------
            SectionHeader("Security & storage")
            HarnessCard(Modifier.padding(horizontal = Spacing.lg)) {
                SettingRow(
                    "Credentials",
                    listOfNotNull(
                        masked?.let { "OpenRouter key" },
                        vm.secure.maskedGithubToken()?.let { "GitHub token" },
                    ).ifEmpty { listOf("none") }.joinToString(", ") + " \u00b7 encrypted with the Android Keystore",
                    Icons.Default.Lock,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingRow(
                    "GitHub",
                    if (vm.githubConnected()) "Connected" else "Not connected",
                    Icons.Default.Hub,
                    trailing = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    onClick = onGithub,
                )
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
            "Your OpenRouter API key and GitHub token are permanently removed from this device. AI and GitHub features stop working until you add them again.",
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

/**
 * OpenAI-compatible providers with a standing free tier, so a user without an OpenRouter balance is
 * not stuck. The app only fills in the URL — the key and the model id stay the user's choice, and
 * the quotas below are the providers' own published free limits, not a promise from this app.
 */
private data class ProviderPreset(
    val label: String,
    val url: String,
    val freeTier: String,
    val keyUrl: String,
    val exampleModel: String,
)

private val PROVIDER_PRESETS = listOf(
    ProviderPreset(
        "OpenRouter", "https://openrouter.ai/api/v1",
        "~20 req/min, 50 req/day on :free models", "openrouter.ai/keys",
        "deepseek/deepseek-r1:free",
    ),
    ProviderPreset(
        "Groq", "https://api.groq.com/openai/v1",
        "~30 req/min, 1000+ req/day, open-weight models", "console.groq.com/keys",
        "llama-3.3-70b-versatile",
    ),
    ProviderPreset(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        "generous daily quota on Flash models", "aistudio.google.com/apikey",
        "gemini-2.5-flash",
    ),
    ProviderPreset(
        "Cerebras", "https://api.cerebras.ai/v1",
        "~1M tokens/day", "cloud.cerebras.ai",
        "llama-3.3-70b",
    ),
    ProviderPreset(
        "Mistral", "https://api.mistral.ai/v1",
        "free Experiment tier", "console.mistral.ai/api-keys",
        "mistral-small-latest",
    ),
    ProviderPreset(
        "GitHub Models", "https://models.github.ai/inference",
        "free for GitHub accounts, daily request cap", "github.com/settings/tokens",
        "openai/gpt-4o-mini",
    ),
)
