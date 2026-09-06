package com.sufyan.harness.ui.github

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.GitHubRepo
import com.sufyan.harness.ui.components.OfflineBanner
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

/**
 * §29-§32 — GitHub.
 *
 * Everything on this screen is backed by a real API call: the account is verified against
 * `GET /user`, repositories come from the account, clone downloads the actual branch, and push
 * creates a genuine commit. Nothing shows "connected" or "pushed" without the server confirming it.
 */
@Composable
fun GitHubScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val project by vm.active.collectAsState()
    val state by vm.githubState.collectAsState()
    var token by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("Update from Sufyan Harness") }
    var showRepoPicker by remember { mutableStateOf(false) }
    var showCreateRepo by remember { mutableStateOf(false) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showForceConfirm by remember { mutableStateOf(false) }
    var newRepoName by remember { mutableStateOf(project?.name?.replace(" ", "-")?.lowercase() ?: "") }
    var newRepoPrivate by remember { mutableStateOf(true) }
    var newBranch by remember { mutableStateOf("") }
    val connected = vm.githubConnected()

    LaunchedEffect(connected) {
        if (connected) {
            vm.refreshGithubUser()
            if (state.repos.isEmpty()) vm.loadRepos()
            if (project?.repoFullName != null) vm.refreshSync()
        }
    }

    val online by vm.online.collectAsState()

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "GitHub",
            subtitle = state.user?.let { "@${it.login}" } ?: "Not connected",
            onBack = onBack,
            actions = {
                if (connected) {
                    IconButton(onClick = { vm.loadRepos(); vm.refreshSync() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            },
        )

        if (!online) {
            OfflineBanner("GitHub", modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            state.busy?.let { busy ->
                item { LoadingState(busy) }
            }
            state.error?.let { error ->
                item { ErrorState("GitHub", error) }
            }
            state.lastResult?.let { result ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md))
                            .background(HarnessColors.Ok.copy(alpha = 0.10f)).padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = HarnessColors.Ok, modifier = Modifier.size(16.dp))
                        Text(result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!connected) {
                item {
                    HarnessCard {
                        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text("Connect GitHub", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Paste a personal access token with the `repo` scope — add `workflow` too if you want to " +
                                    "build APKs in the cloud from the Build screen. It is stored encrypted by the " +
                                    "Android Keystore, never written to the terminal, never logged, and never given " +
                                    "to the AI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                label = { Text("Personal access token") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions.Default,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PrimaryButton(
                                "Connect",
                                { vm.connectGithub(token.trim()); token = "" },
                                Modifier.fillMaxWidth(),
                                enabled = token.isNotBlank() && state.busy == null,
                                icon = Icons.Default.Link,
                            )
                            Text(
                                "Create one at github.com → Settings → Developer settings → Personal access tokens.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item {
                    HarnessCard {
                        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusChip("Connected", StatusKind.Ok, Icons.Default.CheckCircle)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.disconnectGithub() }) { Text("Disconnect") }
                            }
                            Text(
                                state.user?.let { "${it.name ?: it.login} (@${it.login})" } ?: "Verifying...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Token: ${vm.secure.maskedGithubToken() ?: "\u2014"}",
                                style = MonoStyle.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (project == null) {
                    item {
                        EmptyState(
                            Icons.Outlined.CloudOff,
                            "No project open",
                            "Open a project to link it to a repository, or clone a repository into a new project.",
                        )
                    }
                    item {
                        SecondaryButton("Browse repositories", { showRepoPicker = true }, Modifier.fillMaxWidth(), icon = Icons.Default.Folder)
                    }
                } else {
                    val current = project!!
                    item {
                        HarnessCard {
                            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text("Repository", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(current.repoFullName ?: "Not linked", style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    Icon(Icons.Default.AccountTree, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(current.repoBranch ?: "\u2014", style = MonoStyle.copy(fontSize = 12.sp))
                                    if (current.repoFullName != null) {
                                        TextButton(onClick = { showBranchDialog = true }) { Text("Branches") }
                                    }
                                }
                                state.status?.let { status ->
                                    Text(status.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!status.clean) {
                                        Text(
                                            (status.added.take(3) + status.modified.take(3)).joinToString(", ").take(120),
                                            style = MonoStyle.copy(fontSize = 11.sp),
                                            color = HarnessColors.TextMuted,
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    SecondaryButton(
                                        if (current.repoFullName == null) "Link repository" else "Change",
                                        { showRepoPicker = true },
                                        icon = Icons.Default.Link,
                                    )
                                    if (current.repoFullName != null) {
                                        TextButton(onClick = { vm.unlinkRepo() }) { Text("Unlink") }
                                    }
                                }
                            }
                        }
                    }

                    if (current.repoFullName != null) {
                        item {
                            OutlinedTextField(
                                value = commitMessage,
                                onValueChange = { commitMessage = it },
                                label = { Text("Commit message") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                PrimaryButton(
                                    "Push",
                                    { vm.pushProject(commitMessage) },
                                    Modifier.weight(1f),
                                    enabled = state.busy == null && commitMessage.isNotBlank(),
                                    icon = Icons.Default.CloudUpload,
                                )
                                SecondaryButton(
                                    "Pull",
                                    { vm.pullProject() },
                                    Modifier.weight(1f),
                                    enabled = state.busy == null,
                                    icon = Icons.Default.CloudDownload,
                                )
                            }
                        }
                        if (state.conflicts.isNotEmpty()) {
                            item {
                                HarnessCard {
                                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                            Icon(Icons.Default.Warning, null, tint = HarnessColors.Warn, modifier = Modifier.size(18.dp))
                                            Text("Merge conflict", style = MaterialTheme.typography.titleMedium, color = HarnessColors.Warn)
                                        }
                                        Text(
                                            "${state.conflicts.size} file(s) changed here and on GitHub since the last sync. " +
                                                "Choose a side for each — nothing is decided for you.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        state.conflicts.forEach { conflict ->
                                            Column {
                                                Text(conflict.path, style = MonoStyle.copy(fontSize = 12.sp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                                    TextButton(onClick = { vm.resolveConflictKeepLocal(conflict.path) }) { Text("Keep mine") }
                                                    TextButton(onClick = { vm.resolveConflictTakeRemote(conflict.path) }) { Text("Take theirs") }
                                                }
                                            }
                                        }
                                        SecondaryButton(
                                            "Force push (overwrite GitHub)",
                                            { showForceConfirm = true },
                                            Modifier.fillMaxWidth(),
                                            icon = Icons.Default.PriorityHigh,
                                        )
                                    }
                                }
                            }
                        }
                        if (state.commits.isNotEmpty()) {
                            item { SectionHeader("Recent commits") }
                            items(state.commits) { commit ->
                                SettingRow(
                                    title = commit.message.ifBlank { "(no message)" },
                                    subtitle = "${commit.sha.take(7)} \u00b7 ${commit.author} \u00b7 ${commit.date.take(10)}",
                                    icon = Icons.Default.Commit,
                                )
                            }
                        }
                    }
                }

                item {
                    SecondaryButton("Create new repository", { showCreateRepo = true }, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
                }
                item {
                    Text(
                        "The AI agent has no GitHub tool and cannot see this token, so it can never push or " +
                            "force-push on its own (\u00a733).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showRepoPicker) {
        RepoPickerSheet(
            repos = state.repos,
            onDismiss = { showRepoPicker = false },
            onLink = { repo ->
                showRepoPicker = false
                if (vm.active.value == null) vm.cloneRepo(repo, repo.defaultBranch)
                else vm.linkRepo(repo.fullName, repo.defaultBranch)
            },
            onClone = { repo ->
                showRepoPicker = false
                vm.cloneRepo(repo, repo.defaultBranch)
            },
        )
    }

    if (showCreateRepo) {
        AlertDialog(
            onDismissRequest = { showCreateRepo = false },
            title = { Text("Create repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newRepoPrivate, onCheckedChange = { newRepoPrivate = it })
                        Text("Private", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newRepoName.isNotBlank(),
                    onClick = { vm.createGithubRepo(newRepoName.trim(), newRepoPrivate); showCreateRepo = false },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateRepo = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showBranchDialog) {
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            title = { Text("Branches") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    state.branches.forEach { branch ->
                        TextButton(onClick = { vm.switchGithubBranch(branch); showBranchDialog = false }) {
                            Text(branch, style = MonoStyle.copy(fontSize = 13.sp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(
                        value = newBranch,
                        onValueChange = { newBranch = it },
                        label = { Text("New branch") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newBranch.isNotBlank(),
                    onClick = { vm.createGithubBranch(newBranch.trim()); newBranch = ""; showBranchDialog = false },
                ) { Text("Create branch") }
            },
            dismissButton = { TextButton(onClick = { showBranchDialog = false }) { Text("Close") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showForceConfirm) {
        ConfirmDialog(
            title = "Force push?",
            message = "This replaces the remote branch with your local files. Commits on GitHub that are not " +
                "here will be lost. This action is only ever taken because you asked for it.",
            confirmLabel = "Force push",
            destructive = true,
            onConfirm = { showForceConfirm = false; vm.pushProject(commitMessage, force = true) },
            onDismiss = { showForceConfirm = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoPickerSheet(
    repos: List<GitHubRepo>,
    onDismiss: () -> Unit,
    onLink: (GitHubRepo) -> Unit,
    onClone: (GitHubRepo) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = repos.filter { it.fullName.contains(query, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)) {
            Text("Repositories", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            if (repos.isEmpty()) {
                Text(
                    "No repositories were returned for this token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(filtered) { repo ->
                    Column(Modifier.padding(vertical = Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Icon(
                                if (repo.private) Icons.Default.Lock else Icons.Default.Public,
                                null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(repo.fullName, style = MaterialTheme.typography.bodyMedium)
                        }
                        repo.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextButton(onClick = { onLink(repo) }) { Text("Link") }
                            TextButton(onClick = { onClone(repo) }) { Text("Clone as new project") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}
