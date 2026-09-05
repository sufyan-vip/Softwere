package com.sufyan.harness.ui

import androidx.compose.foundation.layout.Box
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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ui.chat.ChatScreen
import com.sufyan.harness.ui.chat.ModelSelectorScreen
import com.sufyan.harness.ui.editor.EditorScreen
import com.sufyan.harness.ui.git.GitScreen
import com.sufyan.harness.ui.preview.PreviewScreen
import com.sufyan.harness.ui.projects.NewProjectScreen
import com.sufyan.harness.ui.projects.ProjectSettingsScreen
import com.sufyan.harness.ui.projects.ProjectDetailScreen
import com.sufyan.harness.ui.projects.ProjectsScreen
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

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.clearToast()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showBar = TABS.any { t -> current?.hierarchy?.any { it.route == t.route } == true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
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
                        onReviewChanges = { nav.navigate(Routes.GIT) },
                        onOpenPreview = { nav.navigate(Routes.PREVIEW) },
                    )
                }
                composable(Routes.TERMINAL) { TerminalScreen(vm) }
                composable(Routes.EDITOR) {
                    EditorScreen(
                        vm,
                        onPreview = { nav.navigate(Routes.PREVIEW) },
                        onGit = { nav.navigate(Routes.GIT) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm,
                        onThemeChanged = onThemeChanged,
                        onModels = { nav.navigate(Routes.MODELS) },
                        onToolchains = { nav.navigate(Routes.TOOLCHAINS) },
                        onStorage = { nav.navigate(Routes.STORAGE) },
                    )
                }
                composable(Routes.NEW_PROJECT) {
                    NewProjectScreen(vm, onDone = { nav.popBackStack() })
                }
                composable(Routes.MODELS) {
                    ModelSelectorScreen(vm, onBack = { nav.popBackStack() })
                }
                composable(Routes.PREVIEW) { PreviewScreen(vm, onBack = { nav.popBackStack() }) }
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
