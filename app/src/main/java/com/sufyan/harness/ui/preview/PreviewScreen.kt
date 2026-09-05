package com.sufyan.harness.ui.preview

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val project by vm.active.collectAsState()
    val server = vm.devServer
    val state by (server?.state ?: kotlinx.coroutines.flow.MutableStateFlow(com.sufyan.harness.runtime.ServerState())).collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val consoleLines = remember { mutableStateListOf<String>() }
    var showStartSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Preview",
            subtitle = if (state.running) state.url else project?.name ?: "No project",
            onBack = onBack,
            actions = {
                if (state.running) {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { vm.stopPreview() }) { Icon(Icons.Default.Stop, contentDescription = "Stop server") }
                }
            },
        )

        if (project == null) {
            EmptyState(Icons.Outlined.PlayCircle, "No project open", "Open a project to preview it.")
            return@Column
        }
        // The `project` property is a delegated State read, so it does not smart-cast; take one
        // snapshot of the non-null value for the rest of the composable.
        val current = project!!

        if (!state.running) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                EmptyState(
                    Icons.Outlined.PlayCircle,
                    "Server not running",
                    "Serve this project over a real local HTTP server on 127.0.0.1, then view it in the embedded browser.",
                )
                PrimaryButton("Start static server", { vm.startPreviewStatic() }, Modifier.fillMaxWidth(), icon = Icons.Default.PlayArrow)
                val type = current.kind
                if (type.devCommand != null) {
                    SecondaryButton(
                        "Run default (${type.devCommand})",
                        { showStartSheet = true },
                        Modifier.fillMaxWidth(),
                        icon = Icons.Default.Terminal,
                    )
                } else {
                    SecondaryButton("Run a dev command", { showStartSheet = true }, Modifier.fillMaxWidth(), icon = Icons.Default.Terminal)
                }
                Text(
                    "This ${type.label} previews on port ${current.previewPort}." +
                        if (type.devCommand != null) " The dev command is \u201c${type.devCommand}\u201d." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.console.isNotEmpty()) {
                    ConsolePanel(state.console)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
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
            // Default the dialog to this project type's real dev command (§11/45): the field is never
            // pre-filled with a command that does not apply to the chosen type.
            var cmd by remember(current.id, showStartSheet) { mutableStateOf(current.kind.devCommand ?: "npm run dev") }
            AlertDialog(
                onDismissRequest = { showStartSheet = false },
                title = { Text("Run dev command") },
                text = {
                    Column {
                        Text(
                            "The command runs in the project directory. Preview only opens if something actually listens on port ${current.previewPort}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        OutlinedTextField(value = cmd, onValueChange = { cmd = it }, singleLine = true, textStyle = MonoStyle)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.startPreviewProcess(cmd); showStartSheet = false }) { Text("Run") }
                },
                dismissButton = { TextButton(onClick = { showStartSheet = false }) { Text("Cancel") } },
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }
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
