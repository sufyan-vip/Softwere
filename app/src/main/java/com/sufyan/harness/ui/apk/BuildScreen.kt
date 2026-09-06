package com.sufyan.harness.ui.apk

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.BuildArtifact
import com.sufyan.harness.runtime.BuildOutcome
import com.sufyan.harness.runtime.BuildRequirement
import com.sufyan.harness.runtime.CloudStep
import com.sufyan.harness.ui.components.AppTopBar
import com.sufyan.harness.ui.components.CodeBlock
import com.sufyan.harness.ui.components.ConfirmDialog
import com.sufyan.harness.ui.components.EmptyState
import com.sufyan.harness.ui.components.HarnessCard
import com.sufyan.harness.ui.components.PrimaryButton
import com.sufyan.harness.ui.components.SecondaryButton
import com.sufyan.harness.ui.components.SectionHeader
import com.sufyan.harness.ui.components.StatusChip
import com.sufyan.harness.ui.components.StatusKind
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Spacing

/**
 * §34-§39 — the Android build screen.
 *
 * Everything here is evidence. The requirement list is what was really probed on this device, the
 * log is the real Gradle output, and an APK only appears after [com.sufyan.harness.runtime.ApkVerifier]
 * has opened it and confirmed it is a valid package. A build that cannot run is *blocked* with the
 * reason and the remedy — never started so it can fail deep inside Gradle (RULE 3, RULE 4).
 */
@Composable
fun BuildScreen(
    vm: HarnessViewModel,
    onBack: () -> Unit,
    onOpenGithub: () -> Unit = {},
    onOpenRuntime: () -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    val state by vm.buildState.collectAsState()
    val cloud by vm.cloudBuildState.collectAsState()
    val project by vm.active.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingDelete by remember { mutableStateOf<BuildArtifact?>(null) }

    LaunchedEffect(project?.id) { vm.detectBuildEnvironment() }

    val env = state.environment

    pendingDelete?.let { artifact ->
        ConfirmDialog(
            title = "Delete ${artifact.name}?",
            message = "The APK file is removed from this device. The project source is untouched.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.deleteArtifact(artifact); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Build APK",
            subtitle = project?.name ?: "No project open",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.detectBuildEnvironment() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-check requirements")
                }
            },
        )

        if (project == null) {
            EmptyState(
                icon = Icons.Default.Android,
                title = "No project open",
                message = "Open an Android project first — this screen builds the project you have open.",
            )
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // ---- requirements ------------------------------------------------
            item {
                HarnessCard {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Build requirements", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            StatusChip(
                                env?.statusLine ?: "Checking...",
                                if (env == null) StatusKind.Neutral else if (env.ready) StatusKind.Ok else StatusKind.Warn,
                            )
                        }
                        Text(
                            "Each line below was probed on this device just now. Nothing is assumed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (env == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(Spacing.sm))
                                Text("Checking the toolchain...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            env.requirements.forEach { req -> RequirementRow(req) }
                            env.gradleCommand?.let { cmd ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Text("Command", style = MaterialTheme.typography.labelLarge)
                                Text(cmd, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // ---- cloud build (§34-§39) ---------------------------------------
            item {
                CloudBuildCard(
                    cloud = cloud,
                    blockers = vm.cloudBuildBlockers(),
                    repo = project?.repoFullName,
                    branch = project?.repoBranch ?: "main",
                    onBuild = { variant -> vm.startCloudBuild(variant) },
                    onStop = { vm.stopFollowingCloudBuild() },
                    onOpenRun = { url -> uriHandler.openUri(url) },
                    onOpenGithub = onOpenGithub,
                    onReadLog = { vm.fetchCloudBuildErrors() },
                    onAskAgent = {
                        vm.askAgentToFixCloudBuild()
                        onOpenChat()
                    },
                )
            }

            item { SectionHeader("Build on this device") }

            // ---- actions -----------------------------------------------------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryButton(
                        text = if (state.running) "Building..." else "Build debug",
                        onClick = { vm.buildApk("debug") },
                        modifier = Modifier.weight(1f),
                        enabled = env?.ready == true && !state.running,
                        icon = Icons.Default.Build,
                    )
                    SecondaryButton(
                        text = "Build release",
                        onClick = { vm.buildApk("release") },
                        modifier = Modifier.weight(1f),
                        enabled = env?.ready == true && !state.running,
                        icon = Icons.Default.Android,
                    )
                }
            }

            if (env != null && !env.ready) {
                item {
                    HarnessCard {
                        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(
                                "Building is disabled until the missing pieces are present",
                                style = MaterialTheme.typography.labelLarge,
                                color = HarnessColors.Warn,
                            )
                            env.missing.forEach { req ->
                                Text("• ${req.label}: ${req.detail}", style = MaterialTheme.typography.bodySmall)
                                req.remedy?.let {
                                    Text(
                                        "  → $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                TextButton(onClick = onOpenRuntime) { Text("Open runtime & toolchains") }
                                TextButton(onClick = onOpenGithub) { Text("Build on GitHub instead") }
                            }
                        }
                    }
                }
            }

            // ---- outcome -----------------------------------------------------
            state.outcome?.let { outcome ->
                item { OutcomeCard(outcome, onOpenRuntime) }
            }

            // ---- live log ----------------------------------------------------
            if (state.log.isNotEmpty()) {
                item {
                    SectionHeader(if (state.running) "Gradle output (live)" else "Gradle output")
                }
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CodeBlock(state.log.joinToString("\n"), language = "gradle")
                    }
                }
            }

            // ---- artifacts ---------------------------------------------------
            item { SectionHeader("APKs on this device") }
            if (state.artifacts.isEmpty()) {
                item {
                    Text(
                        "No APK has been built yet. A file only appears here after it has been verified as a real Android package.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.artifacts.size) { index ->
                    val artifact = state.artifacts[index]
                    ArtifactCard(
                        artifact = artifact,
                        canInstall = vm.canRequestInstall(),
                        onInstall = {
                            val intent = vm.installArtifact(artifact)
                            if (intent != null) context.startActivity(intent)
                        },
                        onShare = {
                            val intent = vm.shareArtifact(artifact)
                            if (intent != null) context.startActivity(Intent.createChooser(intent, "Share APK"))
                        },
                        onDelete = { pendingDelete = artifact },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(req: BuildRequirement) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (req.available) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (req.available) HarnessColors.Ok else HarnessColors.Warn,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(req.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                req.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeCard(outcome: BuildOutcome, onOpenRuntime: () -> Unit) {
    HarnessCard {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when (outcome) {
                is BuildOutcome.Success -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = HarnessColors.Ok,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Build succeeded", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(outcome.artifact.name, style = MonoStyle)
                    Text(
                        outcome.artifact.report.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is BuildOutcome.Blocked -> {
                    Text("Build did not start", style = MaterialTheme.typography.titleMedium, color = HarnessColors.Warn)
                    Text(outcome.explanation, style = MaterialTheme.typography.bodyMedium)
                    outcome.requirement.remedy?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onOpenRuntime) { Text("Open runtime & toolchains") }
                }

                is BuildOutcome.Failed -> {
                    Text(
                        "Build failed (exit ${outcome.exitCode})",
                        style = MaterialTheme.typography.titleMedium,
                        color = HarnessColors.Danger,
                    )
                    Text("What happened", style = MaterialTheme.typography.labelLarge)
                    Text(outcome.diagnosis.what, style = MaterialTheme.typography.bodyMedium)
                    Text("Why", style = MaterialTheme.typography.labelLarge)
                    Text(outcome.diagnosis.why, style = MaterialTheme.typography.bodyMedium)
                    Text("How to fix it", style = MaterialTheme.typography.labelLarge)
                    Text(outcome.diagnosis.how, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ArtifactCard(
    artifact: BuildArtifact,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    HarnessCard {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(artifact.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${artifact.variant} · ${artifact.sizeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    if (artifact.report.valid) "Verified" else "Invalid",
                    if (artifact.report.valid) StatusKind.Ok else StatusKind.Error,
                )
            }
            Text(
                artifact.report.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (artifact.report.nativeAbis.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    artifact.report.nativeAbis.forEach { abi -> StatusChip(abi, StatusKind.Info) }
                }
            }
            if (!canInstall) {
                Text(
                    "Android needs permission to install apps from Sufyan Harness. The installer will ask for it the first time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HarnessColors.Warn,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PrimaryButton(
                    text = "Install",
                    onClick = onInstall,
                    modifier = Modifier.weight(1f),
                    enabled = artifact.report.valid,
                    icon = Icons.Default.InstallMobile,
                )
                SecondaryButton(
                    text = "Share",
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Share,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${artifact.name}")
                }
            }
        }
    }
}

/**
 * The cloud path. It is presented *first* because on a phone it is usually the only one that can
 * actually produce an APK — and every line here reflects a real check: a token that exists, a repo
 * that is linked, a run that GitHub really started, and a file that has been verified after download.
 */
@Composable
private fun CloudBuildCard(
    cloud: com.sufyan.harness.runtime.CloudBuildState,
    blockers: List<String>,
    repo: String?,
    branch: String,
    onBuild: (String) -> Unit,
    onStop: () -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenGithub: () -> Unit,
    onReadLog: () -> Unit,
    onAskAgent: () -> Unit,
) {
    HarnessCard {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("Build in the cloud", style = MaterialTheme.typography.titleMedium)
                    Text(
                        repo?.let { "$it · $branch" } ?: "No repository linked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cloud.running) StatusChip("Running", StatusKind.Info)
            }

            Text(
                "Android phones ship no JDK, and Google builds aapt2 only for desktop CPUs, so a real " +
                    "Gradle build cannot run here. This pushes the project to GitHub, builds it on GitHub's " +
                    "machines, then downloads and verifies the APK so you can install it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (blockers.isNotEmpty()) {
                blockers.forEach { b ->
                    Text("• $b", style = MaterialTheme.typography.bodySmall, color = HarnessColors.Warn)
                }
                TextButton(onClick = onOpenGithub) { Text("Open GitHub setup") }
            }

            if (cloud.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(cloud.phase, style = MaterialTheme.typography.bodyMedium)
                }
                cloud.steps.takeIf { it.isNotEmpty() }?.let { steps ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        steps.forEach { step -> CloudStepRow(step) }
                    }
                }
            } else if (cloud.phase.isNotEmpty()) {
                Text(cloud.phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            cloud.error?.let { error ->
                Text("Cloud build stopped", style = MaterialTheme.typography.labelLarge, color = HarnessColors.Danger)
                Text(error, style = MaterialTheme.typography.bodySmall)

                // §38 — a failure is only useful with the reason attached, so the real compiler
                // lines from the run are fetched and can be handed straight to the agent.
                if (cloud.errorLines.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text("From the run log", style = MaterialTheme.typography.labelMedium)
                    CodeBlock(cloud.errorLines.joinToString("\n"))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PrimaryButton(
                            text = "Ask the AI to fix it",
                            onClick = onAskAgent,
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.AutoFixHigh,
                        )
                    }
                } else if (cloud.fetchingLogs) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Reading the run log\u2026", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (cloud.runId != null) {
                    SecondaryButton(text = "Read the error log", onClick = onReadLog)
                }
            }
            cloud.lastResult?.let { result ->
                Text(result, style = MaterialTheme.typography.bodySmall, color = HarnessColors.Ok)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (cloud.running) {
                    SecondaryButton(text = "Stop watching", onClick = onStop, modifier = Modifier.weight(1f))
                } else {
                    PrimaryButton(
                        text = "Cloud debug",
                        onClick = { onBuild("debug") },
                        modifier = Modifier.weight(1f),
                        enabled = blockers.isEmpty(),
                        icon = Icons.Default.CloudUpload,
                    )
                    SecondaryButton(
                        text = "Cloud release",
                        onClick = { onBuild("release") },
                        modifier = Modifier.weight(1f),
                        enabled = blockers.isEmpty(),
                    )
                }
            }
            cloud.runUrl?.let { url ->
                TextButton(onClick = { onOpenRun(url) }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Open the run on GitHub")
                }
            }
        }
    }
}

@Composable
private fun CloudStepRow(step: CloudStep) {
    val (tint, icon) = when {
        step.conclusion == "success" -> HarnessColors.Ok to Icons.Default.CheckCircle
        step.conclusion == "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Default.CheckCircle
        step.conclusion != null -> HarnessColors.Danger to Icons.Default.ErrorOutline
        step.status == "in_progress" -> HarnessColors.Info to Icons.Default.Build
        else -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Default.Build
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            step.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (step.status == "in_progress") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
