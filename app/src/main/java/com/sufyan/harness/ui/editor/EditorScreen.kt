package com.sufyan.harness.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: HarnessViewModel, onPreview: () -> Unit, onGit: () -> Unit) {
    val project by vm.active.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val activePath by vm.activeTab.collectAsState()
    val expanded by vm.expanded.collectAsState()
    var showTree by remember { mutableStateOf(true) }
    var newEntry by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            "Editor",
            subtitle = project?.name ?: "No project open",
            actions = {
                IconButton(onClick = { showTree = !showTree }) {
                    Icon(Icons.Default.AccountTree, contentDescription = "Toggle file tree")
                }
                IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Search, contentDescription = "Search in project") }
                IconButton(onClick = onPreview) { Icon(Icons.Default.PlayCircle, contentDescription = "Live preview") }
                IconButton(onClick = onGit) { Icon(Icons.Default.Difference, contentDescription = "Git and checkpoints") }
            },
        )

        if (project == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.Code, "No project open", "Open a project to browse and edit its files.")
            }
            return@Column
        }

        val files = vm.files!!

        if (showTree) {
            Column(Modifier.heightIn(max = 220.dp)) {
                Row(
                    Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("FILES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { newEntry = "" }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New file", modifier = Modifier.size(16.dp))
                    }
                }
                val nodes = remember(expanded, tabs, project) { files.tree(expanded) }
                if (nodes.isEmpty()) {
                    Text(
                        "This project has no files yet. Use + to create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                } else {
                    LazyColumn {
                        items(nodes, key = { it.file.absolutePath }) { node ->
                            val rel = files.relativePath(node.file)
                            FileRow(
                                name = node.file.name,
                                depth = node.depth,
                                isDir = node.isDir,
                                isOpen = rel in expanded,
                                selected = rel == activePath,
                                onClick = { if (node.isDir) vm.toggleDir(rel) else vm.openFile(rel) },
                                onLongClick = { vm.deleteEntry(rel) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }

        if (tabs.isNotEmpty()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                tabs.forEach { tab ->
                    val selected = tab.path == activePath
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { vm.selectTab(tab.path) }
                            .padding(horizontal = Spacing.sm, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.path.substringAfterLast('/') + if (tab.dirty) " •" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close ${tab.path}",
                            modifier = Modifier.size(13.dp).clickable { vm.closeTab(tab.path) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        val tab = tabs.find { it.path == activePath }
        if (tab == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.Code, "No file open", "Pick a file from the tree above to start editing.")
            }
        } else {
            CodeEditor(
                content = tab.content,
                fontSize = vm.settings.editorFontSize.sp,
                showLineNumbers = vm.settings.lineNumbers,
                wordWrap = vm.settings.wordWrap,
                onChange = { vm.updateTab(tab.path, it) },
                modifier = Modifier.weight(1f),
            )
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${tab.content.lines().size} lines" + if (tab.dirty) " • unsaved" else " • saved",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("Save", { vm.saveTab(tab.path) }, enabled = tab.dirty, icon = Icons.Default.Save)
                    }
                }
            }
        }
    }

    newEntry?.let {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newEntry = null },
            title = { Text("New file or folder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { v -> value = v },
                        singleLine = true,
                        label = { Text("Path (end with / for a folder)") },
                        placeholder = { Text("src/App.jsx") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (value.endsWith("/")) vm.createDir(value.trimEnd('/')) else vm.createFile(value)
                    newEntry = null
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newEntry = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    searchQuery?.let { q ->
        var value by remember { mutableStateOf(q) }
        val results = remember(value) { if (value.length > 1) vm.files?.search(value).orEmpty() else emptyList() }
        ModalBottomSheet(onDismissRequest = { searchQuery = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(Spacing.lg).heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Search project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.md))
                if (value.length > 1 && results.isEmpty()) {
                    Text("No matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn { items(results) { r ->
                    Text(
                        r,
                        style = MonoStyle.copy(fontSize = 11.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.openFile(r.substringBefore(':')); searchQuery = null }
                            .padding(vertical = 6.dp),
                    )
                } }
            }
        }
    }
}

@Composable
private fun FileRow(
    name: String,
    depth: Int,
    isDir: Boolean,
    isOpen: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = Spacing.lg + (depth * 14).dp, end = Spacing.lg, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                isDir && isOpen -> Icons.Default.FolderOpen
                isDir -> Icons.Default.Folder
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/** Editable code surface with gutter and lightweight keyword highlighting. */
@Composable
private fun CodeEditor(
    content: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    showLineNumbers: Boolean,
    wordWrap: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember(content.hashCode()) { mutableStateOf(TextFieldValue(content)) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val style = MonoStyle.copy(fontSize = fontSize, color = HarnessColors.TextPrimary)

    Row(
        modifier
            .fillMaxWidth()
            .background(HarnessColors.Base)
            .verticalScroll(vScroll),
    ) {
        if (showLineNumbers) {
            Column(Modifier.padding(start = Spacing.sm, top = Spacing.sm, end = Spacing.xs)) {
                repeat(field.text.lines().size) { i ->
                    Text(
                        "${i + 1}",
                        style = style.copy(color = HarnessColors.TextMuted),
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (wordWrap) Modifier else Modifier.horizontalScroll(hScroll))
                .padding(Spacing.sm),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = it; onChange(it.text) },
                textStyle = style,
                cursorBrush = SolidColor(HarnessColors.Accent),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = { text ->
                    androidx.compose.ui.text.input.TransformedText(
                        highlight(text.text),
                        androidx.compose.ui.text.input.OffsetMapping.Identity,
                    )
                },
            )
        }
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "return", "if", "else", "for", "while", "when",
    "import", "package", "const", "let", "function", "export", "default", "async", "await",
    "def", "from", "public", "private", "static", "void", "true", "false", "null", "new",
    "interface", "extends", "implements", "try", "catch", "finally", "throw",
)

private fun highlight(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' || c == '\'' -> {
                val end = text.indexOf(c, i + 1).let { if (it == -1) text.length - 1 else it }
                withStyle(SpanStyle(color = HarnessColors.Ok)) { append(text.substring(i, end + 1)) }
                i = end + 1
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                withStyle(SpanStyle(color = HarnessColors.TextMuted)) { append(text.substring(i, end)) }
                i = end
            }
            c == '#' -> {
                val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                withStyle(SpanStyle(color = HarnessColors.TextMuted)) { append(text.substring(i, end)) }
                i = end
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                if (word in KEYWORDS) {
                    withStyle(SpanStyle(color = HarnessColors.Accent)) { append(word) }
                } else append(word)
                i = j
            }
            c.isDigit() -> {
                var j = i
                while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                withStyle(SpanStyle(color = HarnessColors.Warn)) { append(text.substring(i, j)) }
                i = j
            }
            else -> { append(c); i++ }
        }
    }
}

