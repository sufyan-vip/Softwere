package com.sufyan.harness.ui.preview

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.ServerState
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * §40-§44 — live preview and export.
 *
 * The preview only appears when a real HTTP server is listening; when the dev command dies the
 * screen shows the actual process output, the exit code, and a button that hands that output to the
 * AI (§44). Nothing here is mocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: HarnessViewModel, onBack: () -> Unit, onOpenChat: () -> Unit = {}) {
    val context = LocalContext.current
    val project by vm.active.collectAsState()
    val server = vm.devServer
    val fallback = remember { MutableStateFlow(ServerState()) }
    val state by (server?.state ?: fallback).collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val consoleLines = remember { mutableStateListOf<String>() }
    var showStartSheet by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Preview",
            subtitle = if (state.running) state.url else project?.name ?: "No project",
            onBack = onBack,
            actions = {
                if (state.running) {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload page") }
                    IconButton(onClick = { vm.restartPreview() }) { Icon(Icons.Default.RestartAlt, contentDescription = "Restart server") }
                    IconButton(onClick = { vm.stopPreview() }) { Icon(Icons.Default.Stop, contentDescription = "Stop server") }
                }
                IconButton(onClick = { showExport = true }) { Icon(Icons.Default.Archive, contentDescription = "Export") }
            },
        )

        if (project == null) {
            EmptyState(Icons.Outlined.PlayCircle, "No project open", "Open a project to preview it.")
            return@Column
        }
        val current = project!!

        if (!state.running) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                state.errorReport()?.let { report ->
                    HarnessCard {
                        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Icon(Icons.Default.ErrorOutline, null, tint = HarnessColors.Danger, modifier = Modifier.size(18.dp))
                                Text("The server stopped", style = MaterialTheme.typography.titleMedium, color = HarnessColors.Danger)
                            }
                            Text(
                                state.error ?: "The process exited with code ${state.exitCode}.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            CodeBlock(report.take(1200))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                SecondaryButton("Retry", { vm.restartPreview() }, icon = Icons.Default.Refresh)
                                PrimaryButton(
                                    "Ask AI to fix",
                                    { vm.askAiToFixPreview(); onOpenChat() },
                                    icon = Icons.Default.AutoFixHigh,
                                )
                            }
                        }
                    }
                }

                EmptyState(
                    Icons.Outlined.PlayCircle,
                    "Server not running",
                    "Serve this project over a real local HTTP server on 127.0.0.1, then view it in the embedded browser.",
                )
                PrimaryButton("Start static server", { vm.startPreviewStatic() }, Modifier.fillMaxWidth(), icon = Icons.Default.PlayArrow)
                val type = current.kind
                SecondaryButton(
                    if (type.devCommand != null) "Run default (${type.devCommand})" else "Run a dev command",
                    { showStartSheet = true },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Default.Terminal,
                )
                Text(
                    "This ${type.label} previews on port ${current.previewPort}." +
                        if (type.devCommand != null) " The dev command is \u201c${type.devCommand}\u201d." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.console.isNotEmpty()) ConsolePanel(state.console)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                StatusChip(state.kind, StatusKind.Ok, Icons.Default.Dns)
                Text(state.url, style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                consoleLines += "${m.messageLevel()}: ${m.message()} (line ${m.lineNumber()})"
                                if (consoleLines.size > 100) consoleLines.removeAt(0)
                                return true
                            }
                        }
                        loadUrl(state.url)
                        webView = this
                    }
                },
                update = { it.takeIf { v -> v.url != state.url }?.loadUrl(state.url) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .clip(RoundedCornerShape(Radius.md)),
            )
            Column(Modifier.heightIn(max = 180.dp).padding(Spacing.lg)) {
                ConsolePanel(state.console + consoleLines)
            }
        }

        if (showStartSheet) {
            var cmd by remember(current.id, showStartSheet) { mutableStateOf(current.kind.devCommand ?: "npm run dev") }
            AlertDialog(
                onDismissRequest = { showStartSheet = false },
                title = { Text("Run dev command") },
                text = {
                    Column {
                        Text(
                            "The command runs in the project directory. Preview only opens if something actually " +
                                "listens on port ${current.previewPort}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        OutlinedTextField(value = cmd, onValueChange = { cmd = it }, singleLine = true, textStyle = MonoStyle)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.startPreviewProcess(cmd); showStartSheet = false }) { Text("Run") } },
                dismissButton = { TextButton(onClick = { showStartSheet = false }) { Text("Cancel") } },
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }
    }

    // §41-§42 — export, with the outcome reported from the file that was really written.
    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false; exportResult = null; exportError = null },
            title = { Text("Export project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    fun run(block: () -> Result<File>) {
                        block().fold({ exportResult = it; exportError = null }, { exportError = it.message; exportResult = null })
                    }
                    SettingRow(
                        "Everything",
                        "Every file in the project, minus caches and node_modules.",
                        Icons.Default.FolderZip,
                        onClick = { run { vm.exportProject() } },
                    )
                    SettingRow(
                        "Source only",
                        "Source files, without build output.",
                        Icons.Default.Code,
                        onClick = { run { vm.exportSourceOnly() } },
                    )
                    SettingRow(
                        "Production build",
                        if (vm.hasProductionBuild()) "The contents of the build output directory."
                        else "No build output found \u2014 run the build first.",
                        Icons.Default.RocketLaunch,
                        onClick = { if (vm.hasProductionBuild()) run { vm.exportProduction() } },
                    )
                    exportResult?.let {
                        Text(
                            "Wrote ${it.name} (${it.length() / 1024} KB) to ${it.parentFile?.name}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HarnessColors.Ok,
                        )
                    }
                    exportError?.let {
                        Text("Export failed: $it", style = MaterialTheme.typography.bodySmall, color = HarnessColors.Danger)
                    }
                }
            },
            confirmButton = {
                val file = exportResult
                if (file != null) {
                    TextButton(onClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share export"))
                    }) { Text("Share") }
                } else {
                    TextButton(onClick = { showExport = false }) { Text("Close") }
                }
            },
            dismissButton = {
                if (exportResult != null) TextButton(onClick = { showExport = false; exportResult = null }) { Text("Done") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun ConsolePanel(lines: List<String>) {
    Column {
        Text("CONSOLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(HarnessColors.Base)
                .padding(Spacing.sm),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = if (line.contains("error", true)) HarnessColors.Danger else HarnessColors.TextSecondary,
                )
            }
        }
    }
}
