package com.sufyan.harness.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.RuntimeState
import com.sufyan.harness.runtime.Toolchains
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Spacing

@Composable
fun ToolchainScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val statuses by vm.toolStatuses.collectAsState()
    val scanning by vm.toolsScanning.collectAsState()
    val runtime by vm.linux.status.collectAsState()
    val diagnosis by vm.runtimeDiagnosis.collectAsState()
    val runtimeBusy by vm.runtimeBusy.collectAsState()
    val project by vm.active.collectAsState()
    var confirmRepair by remember { mutableStateOf<com.sufyan.harness.runtime.RuntimeRepairAction?>(null) }
    val type = project?.kind

    LaunchedEffect(Unit) {
        vm.refreshRuntime()
        if (statuses.isEmpty()) vm.scanToolchains()
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Toolchains",
            subtitle = "Verified by running each tool",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.scanToolchains(); vm.refreshRuntime() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                }
            },
        )

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            HarnessCard {
                Column(Modifier.padding(Spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Linux runtime", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        StatusChip(
                            runtime.state.name,
                            when (runtime.state) {
                                RuntimeState.Installed -> StatusKind.Ok
                                RuntimeState.Failed -> StatusKind.Error
                                RuntimeState.Downloading, RuntimeState.Extracting -> StatusKind.Info
                                RuntimeState.NotInstalled -> StatusKind.Neutral
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(runtime.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (runtime.state == RuntimeState.Downloading || runtime.state == RuntimeState.Extracting) {
                        Spacer(Modifier.height(Spacing.sm))
                        LinearProgressIndicator(progress = { runtime.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton(
                            "Diagnose",
                            { vm.diagnoseRuntime() },
                            enabled = !runtimeBusy,
                            icon = Icons.Default.MonitorHeart,
                        )
                        if (runtimeBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    diagnosis?.let { d ->
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            if (d.healthy) "All runtime checks passed." else "Blocked by: ${d.blocker?.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (d.healthy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        d.checks.forEach { check ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    if (check.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                                    tint = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Column {
                                    Text(check.label, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        check.detail,
                                        style = MonoStyle.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (d.repairs.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.sm))
                            Text("Repairs that work on this device", style = MaterialTheme.typography.labelLarge)
                            d.repairs.forEach { action ->
                                SettingRow(
                                    action.label,
                                    action.blurb,
                                    Icons.Default.Handyman,
                                    onClick = { confirmRepair = action },
                                )
                            }
                        }
                    }
                    if (!vm.linux.prootAvailable()) {
                        Spacer(Modifier.height(Spacing.md))
                        UnavailableNotice(
                            "PRoot runtime",
                            "This build does not bundle a PRoot loader, so a Linux rootfs cannot be executed. " +
                                "Installation is deliberately refused rather than reporting a fake success. " +
                                "The Android shell below still runs real commands.",
                        )
                    }
                }
            }

            if (type != null && type.requiredTools.isNotEmpty()) {
                SectionHeader("This ${type.label} needs")
                val byId = statuses.associateBy { it.tool.id }
                if (statuses.isEmpty() && scanning) {
                    LoadingState("Probing the tools this project needs...")
                }
                type.requiredTools.forEach { id ->
                    val st = byId[id]
                    HarnessCard {
                        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Toolchains.labelFor(id), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    st?.tool?.description ?: "Required for this project type.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusChip(
                                when {
                                    st == null -> "checking"
                                    st.available -> "Available"
                                    else -> "Missing"
                                },
                                when {
                                    st == null -> StatusKind.Info
                                    st.available -> StatusKind.Ok
                                    else -> StatusKind.Neutral
                                },
                            )
                        }
                    }
                }
            }

            SectionHeader("Detected tools")
            if (scanning && statuses.isEmpty()) {
                LoadingState("Probing each tool by running it...")
            }
            statuses.forEach { st ->
                HarnessCard {
                    Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(st.tool.label, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(Spacing.sm))
                                StatusChip(
                                    if (st.available) "Available" else "Not available",
                                    if (st.available) StatusKind.Ok else StatusKind.Neutral,
                                )
                            }
                            Text(
                                st.tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                st.version ?: st.detail,
                                style = MonoStyle.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            UnavailableNotice(
                "Package installation",
                "Sufyan Harness never marks a tool as installed unless its probe command actually runs. " +
                    "On stock Android only the tools shipped with the system are present; a full toolchain " +
                    "requires the Linux runtime.",
            )
        }
    }

    confirmRepair?.let { action ->
        ConfirmDialog(
            action.label,
            action.blurb + " The result is reported exactly as it happens \u2014 nothing is assumed.",
            "Run repair",
            destructive = action == com.sufyan.harness.runtime.RuntimeRepairAction.Reinstall,
            onConfirm = { vm.repairRuntime(action); confirmRepair = null },
            onDismiss = { confirmRepair = null },
        )
    }
}
