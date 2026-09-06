package com.sufyan.harness.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.RuntimeTask
import com.sufyan.harness.ui.components.TaskStrip
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.chat.ChatScreen
import com.sufyan.harness.ui.apk.BuildScreen
import com.sufyan.harness.ui.chat.ModelSelectorScreen
import com.sufyan.harness.ui.editor.EditorScreen
import com.sufyan.harness.ui.git.GitScreen
import com.sufyan.harness.ui.github.GitHubScreen
import com.sufyan.harness.ui.preview.PreviewScreen
import com.sufyan.harness.ui.projects.NewProjectScreen
import com.sufyan.harness.ui.projects.ProjectSettingsScreen
import com.sufyan.harness.ui.projects.ProjectDetailScreen
import com.sufyan.harness.ui.projects.ProjectsScreen
import com.sufyan.harness.ui.review.ReviewScreen
import com.sufyan.harness.ui.settings.SettingsScreen
import com.sufyan.harness.ui.settings.ToolchainScreen
import com.sufyan.harness.ui.settings.StorageScreen
import com.sufyan.harness.ui.terminal.TerminalScreen
import com.sufyan.harness.ui.theme.ThemeMode

object Routes {
    const val PROJECTS = "projects"
    const val CHAT = "chat"
    const val TERMINAL = "terminal"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val NEW_PROJECT = "new_project"
    const val MODELS = "models"
    const val PREVIEW = "preview"
    const val GIT = "git"
    const val TOOLCHAINS = "toolchains"
    const val PROJECT_SETTINGS = "project_settings"
    const val PROJECT_DETAIL = "project_detail"
    const val STORAGE = "storage"
    const val GITHUB = "github"
    const val BUILD = "build"
    const val REVIEW = "review"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val TABS = listOf(
    Tab(Routes.PROJECTS, "Projects", Icons.Outlined.FolderOpen, Icons.Filled.Folder),
    Tab(Routes.CHAT, "AI Chat", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Tab(Routes.TERMINAL, "Terminal", Icons.Outlined.Terminal, Icons.Filled.Terminal),
    Tab(Routes.EDITOR, "Editor", Icons.Outlined.Code, Icons.Filled.Code),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun HarnessRoot(vm: HarnessViewModel, onThemeChanged: (ThemeMode) -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()
    val clipboard = LocalClipboardManager.current

    // Start-up work runs here, not in the view-model constructor: anything that throws while the
    // view model is being built takes the whole activity down before a screen exists to report it.
    LaunchedEffect(Unit) { vm.start() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    val tasks by vm.tasks.tasks.collectAsState()

    // §56 — the previous run died from an uncaught exception: show it instead of losing it.
    val crash by vm.lastCrash.collectAsState()
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.dismissCrashReport() },
            icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
            title = { Text("Sufyan Harness closed unexpectedly last time") },
            text = {
                Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "This is the error that ended the previous session. Your projects and files " +
                            "were not touched by it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(report.message, style = MonoStyle, color = MaterialTheme.colorScheme.error)
                    Text(
                        report.stackTrace.lineSequence().take(12).joinToString("\n"),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissCrashReport() }) { Text("Dismiss") } },
            dismissButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report.render())) }) { Text("Copy log") }
            },
        )
    }

    // §56 — if the process was killed mid-operation, say so once, with what to do next.
    val interrupted by vm.interrupted.collectAsState()
    if (interrupted.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.dismissRecovery() },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            title = { Text(if (interrupted.size == 1) interrupted.first().title else "Some work was interrupted") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Sufyan Harness closed while this was running. Nothing was reported as finished, " +
                            "so here is exactly what was in progress:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    interrupted.forEach { item ->
                        Column {
                            Text(item.operation.label, style = MaterialTheme.typography.labelLarge)
                            Text(
                                item.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissRecovery() }) { Text("Got it") } },
        )
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showBar = TABS.any { t -> current?.hierarchy?.any { it.route == t.route } == true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                // §13 — nothing runs invisibly: every registered background task is listed here.
                TaskStrip(
                    tasks = tasks,
                    onStop = { task ->
                        when (task.kind) {
                            RuntimeTask.Kind.Server -> vm.stopPreview()
                            RuntimeTask.Kind.Shell -> vm.stopShell()
                            RuntimeTask.Kind.Agent -> vm.stopGeneration()
                            RuntimeTask.Kind.Build, RuntimeTask.Kind.Install -> vm.notify(
                                "This task cannot be interrupted safely; it will report as soon as it finishes.",
                            )
                        }
                    },
                )
            if (showBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    TABS.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selected else tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Routes.PROJECTS) {
                composable(Routes.PROJECTS) {
                    ProjectsScreen(
                        vm,
                        onNewProject = { nav.navigate(Routes.NEW_PROJECT) },
                        onOpenChat = { nav.navigate(Routes.CHAT) },
                        onProjectSettings = { nav.navigate(Routes.PROJECT_SETTINGS) },
                        onOpenDetails = { nav.navigate(Routes.PROJECT_DETAIL) },
                    )
                }
                composable(Routes.CHAT) {
                    ChatScreen(
                        vm,
                        onPickModel = { nav.navigate(Routes.MODELS) },
                        onReviewChanges = { nav.navigate(Routes.REVIEW) },
                        onOpenPreview = { nav.navigate(Routes.PREVIEW) },
                    )
                }
                composable(Routes.TERMINAL) {
                    TerminalScreen(vm, onOpenRuntime = { nav.navigate(Routes.TOOLCHAINS) })
                }
                composable(Routes.EDITOR) {
                    EditorScreen(
                        vm,
                        onPreview = { nav.navigate(Routes.PREVIEW) },
                        onGit = { nav.navigate(Routes.GIT) },
                        onChat = { nav.navigate(Routes.CHAT) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm,
                        onThemeChanged = onThemeChanged,
                        onModels = { nav.navigate(Routes.MODELS) },
                        onToolchains = { nav.navigate(Routes.TOOLCHAINS) },
                        onStorage = { nav.navigate(Routes.STORAGE) },
                        onGithub = { nav.navigate(Routes.GITHUB) },
                        onBuild = { nav.navigate(Routes.BUILD) },
                    )
                }
                composable(Routes.NEW_PROJECT) {
                    NewProjectScreen(vm, onDone = { nav.popBackStack() })
                }
                composable(Routes.MODELS) {
                    ModelSelectorScreen(vm, onBack = { nav.popBackStack() })
                }
                composable(Routes.PREVIEW) {
                    PreviewScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onOpenChat = { nav.navigate(Routes.CHAT) },
                    )
                }
                composable(Routes.GITHUB) { GitHubScreen(vm, onBack = { nav.popBackStack() }) }
                composable(Routes.BUILD) {
                    BuildScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onOpenGithub = { nav.navigate(Routes.GITHUB) },
                        onOpenRuntime = { nav.navigate(Routes.TOOLCHAINS) },
                    )
                }
                composable(Routes.REVIEW) {
                    ReviewScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onOpenFile = { path ->
                            vm.openFile(path)
                            nav.navigate(Routes.EDITOR)
                        },
                    )
                }
                composable(Routes.GIT) { GitScreen(vm, onBack = { nav.popBackStack() }) }
                composable(Routes.TOOLCHAINS) { ToolchainScreen(vm, onBack = { nav.popBackStack() }) }
                composable(Routes.STORAGE) { StorageScreen(vm, onBack = { nav.popBackStack() }) }
                composable(Routes.PROJECT_SETTINGS) { ProjectSettingsScreen(vm, onBack = { nav.popBackStack() }) }
                composable(Routes.PROJECT_DETAIL) {
                    val active by vm.active.collectAsState()
                    ProjectDetailScreen(
                        vm,
                        active,
                        onBack = { nav.popBackStack() },
                        onNavigate = { route -> nav.navigate(route) },
                    )
                }
            }
        }
    }
}
